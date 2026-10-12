import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "./App";

beforeEach(() => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input) === "/api/v1/account"
    ? new Response(JSON.stringify({ code: "SESSION_ENDED" }), { status: 401 })
    : new Response(JSON.stringify({}), { status: 404 }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

test("the logo returns to the home page", () => {
  render(<App />);
  expect(within(screen.getByRole("banner")).getByRole("link", { name: "otdog home" })).toHaveAttribute("href", "/");
});

test("sign in opens a panel over the page that Escape closes, returning focus to the button", async () => {
  render(<App />);
  const user = userEvent.setup();
  const signIn = within(screen.getByRole("banner")).getByRole("button", { name: "Sign in" });
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(screen.queryByRole("region", { name: "Buyer Account" })).toBeNull();

  await user.click(signIn);
  const panel = screen.getByRole("dialog", { name: "Sign in" });
  expect(panel).toHaveAttribute("aria-modal", "true");
  expect(within(panel).getByRole("region", { name: "Buyer Account" })).toBeVisible();
  expect(document.querySelector(".page")).toHaveAttribute("inert");

  await user.keyboard("{Escape}");
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(document.querySelector(".page")).not.toHaveAttribute("inert");
  expect(signIn).toHaveFocus();
});

test("case access lives in its own panel, closed with its close button", async () => {
  render(<App />);
  const user = userEvent.setup();
  expect(screen.queryByRole("region", { name: "Open an existing Buying Case" })).toBeNull();
  await user.click(screen.getByRole("button", { name: "Your case" }));
  const panel = screen.getByRole("dialog", { name: "Your Buying Case" });
  expect(within(panel).getByRole("region", { name: "Open an existing Buying Case" })).toBeVisible();
  expect(within(panel).getByRole("region", { name: "Recover Buying Case access" })).toBeVisible();
  await user.click(within(panel).getByRole("button", { name: "Close Your Buying Case" }));
  expect(screen.queryByRole("dialog")).toBeNull();
});

test("the intake's sign-in link opens the account panel", async () => {
  render(<App />);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("link", { name: "sign in with a Buyer Account" }));
  expect(screen.getByRole("dialog", { name: "Sign in" })).toBeVisible();
});

test("an emailed sign-in link opens the account panel to finish signing in", () => {
  window.history.replaceState(null, "", "/#sign-in=secret");
  render(<App />);
  expect(within(screen.getByRole("dialog", { name: "Sign in" })).getByRole("button", { name: "Finish signing in" })).toBeVisible();
});
