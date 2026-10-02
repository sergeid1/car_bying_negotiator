import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { ThemeMenu } from "./ThemeMenu";
import { THEME_STORAGE_KEY } from "./theme";

/** Node's own localStorage global can hide jsdom's, so each test gets a plain in-memory store. */
function useStorage(storage: Pick<Storage, "getItem" | "setItem" | "removeItem">) {
  Object.defineProperty(window, "localStorage", { value: storage, configurable: true });
}
function memoryStorage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value); },
    removeItem: (key: string) => { values.delete(key); },
  };
}

beforeEach(() => { useStorage(memoryStorage()); document.documentElement.removeAttribute("data-theme"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test("follows the device until the Buyer pins a theme, and remembers the choice", async () => {
  const user = userEvent.setup();
  render(<ThemeMenu />);
  const toggle = screen.getByRole("button", { name: "Appearance: System" });
  expect(toggle).toHaveAttribute("aria-expanded", "false");
  expect(document.documentElement).not.toHaveAttribute("data-theme");

  await user.click(toggle);
  expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveAttribute("aria-checked", "true");
  await user.click(screen.getByRole("menuitemradio", { name: "Dark" }));

  expect(document.documentElement).toHaveAttribute("data-theme", "dark");
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Appearance: Dark" })).toHaveFocus();

  cleanup();
  render(<ThemeMenu />);
  expect(screen.getByRole("button", { name: "Appearance: Dark" })).toBeInTheDocument();
});

test("returning to System clears the pinned theme", async () => {
  window.localStorage.setItem(THEME_STORAGE_KEY, "light");
  document.documentElement.setAttribute("data-theme", "light");
  const user = userEvent.setup();
  render(<ThemeMenu />);
  await user.click(screen.getByRole("button", { name: "Appearance: Light" }));
  await user.click(screen.getByRole("menuitemradio", { name: "System" }));
  expect(document.documentElement).not.toHaveAttribute("data-theme");
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
});

test("the menu is keyboard operable and Escape returns focus to the toggle", async () => {
  const user = userEvent.setup();
  render(<ThemeMenu />);
  await user.tab();
  await user.keyboard("{Enter}");
  expect(screen.getByRole("menuitemradio", { name: "System" })).toHaveFocus();
  await user.keyboard("{ArrowDown}");
  expect(screen.getByRole("menuitemradio", { name: "Light" })).toHaveFocus();
  await user.keyboard("{ArrowUp}{ArrowUp}");
  expect(screen.getByRole("menuitemradio", { name: "Dark" })).toHaveFocus();
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Appearance: System" })).toHaveFocus();
});

test("a click outside closes the menu without changing the theme", async () => {
  const user = userEvent.setup();
  render(<><ThemeMenu /><p>Elsewhere</p></>);
  await user.click(screen.getByRole("button", { name: "Appearance: System" }));
  await user.click(screen.getByText("Elsewhere"));
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  expect(document.documentElement).not.toHaveAttribute("data-theme");
});

test("blocked storage still applies the choice for this visit", async () => {
  const blocked = () => { throw new Error("blocked"); };
  useStorage({ getItem: blocked, setItem: blocked, removeItem: blocked });
  const user = userEvent.setup();
  render(<ThemeMenu />);
  await user.click(screen.getByRole("button", { name: "Appearance: System" }));
  await user.click(screen.getByRole("menuitemradio", { name: "Light" }));
  expect(document.documentElement).toHaveAttribute("data-theme", "light");
});

test("the open menu has no automated AA violations", async () => {
  const user = userEvent.setup();
  const { container } = render(<header><ThemeMenu /></header>);
  await user.click(screen.getByRole("button", { name: "Appearance: System" }));
  const result = await axe.run(container, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
    rules: { "color-contrast": { enabled: false } },
  });
  expect(result.violations.map(v => v.id)).toEqual([]);
});
