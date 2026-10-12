import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
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

test("with no case open the header has no case button, and case links live in the sign-in panel's disclosure", async () => {
  render(<App />);
  const user = userEvent.setup();
  const header = within(screen.getByRole("banner"));
  expect(header.queryByRole("button", { name: "This case" })).toBeNull();
  expect(header.queryByRole("button", { name: "Your case" })).toBeNull();
  expect(header.queryByRole("link", { name: "Scope" })).toBeNull();
  await user.click(header.getByRole("button", { name: "Sign in" }));
  const panel = screen.getByRole("dialog", { name: "Sign in" });
  const disclosure = within(panel).getByText("Have a private case link?").closest("details")!;
  expect(disclosure).not.toHaveAttribute("open");
  await user.click(within(panel).getByText("Have a private case link?"));
  expect(disclosure).toHaveAttribute("open");
  expect(within(panel).getByRole("region", { name: "Open an existing Buying Case" })).toBeVisible();
  expect(within(panel).getByRole("region", { name: "Recover Buying Case access" })).toHaveTextContent("Lost the link?");
});

test("an open case shows a This case button on the case page whose panel holds the case's options", async () => {
  window.history.replaceState(null, "", "/#case=case-1&access=token");
  vi.mocked(fetch).mockImplementation(async input => String(input) === "/api/v1/cases/case-1"
    ? new Response(JSON.stringify({ id: "case-1" })) : new Response(JSON.stringify({}), { status: 401 }));
  render(<App />);
  const user = userEvent.setup();
  await user.click(await within(screen.getByRole("main")).findByRole("button", { name: "This case" }));
  const panel = screen.getByRole("dialog", { name: "This case" });
  expect(within(panel).getByRole("region", { name: "Optional email recovery" })).toBeVisible();
  await user.click(within(panel).getByRole("button", { name: "Close This case" }));
  expect(screen.queryByRole("dialog")).toBeNull();
});

test("a case link that cannot be opened shows the sign-in panel with the disclosure open", async () => {
  window.history.replaceState(null, "", "/#case=gone&access=token");
  render(<App />);
  const panel = await screen.findByRole("dialog", { name: "Sign in" }, { timeout: 3000 });
  await waitFor(() => expect(within(panel).getByText("Have a private case link?").closest("details")).toHaveAttribute("open"));
  expect(await within(panel).findByRole("alert")).toHaveTextContent("This link cannot be opened");
});

test("an emailed recovery link opens the sign-in panel at its verify button", () => {
  window.history.replaceState(null, "", "/#recover=secret");
  render(<App />);
  const panel = screen.getByRole("dialog", { name: "Sign in" });
  expect(within(panel).getByText("Have a private case link?").closest("details")).toHaveAttribute("open");
  expect(within(panel).getByRole("button", { name: "Verify recovery link" })).toBeVisible();
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
