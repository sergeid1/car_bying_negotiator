import { StrictMode } from "react";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import App from "./App";
import { BuyerAccount } from "./BuyerAccount";
import { SignIn } from "./SignIn";

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const account = { contact: { email: "buyer@example.com" }, cases: [] };
beforeEach(() => window.history.replaceState(null, "", "/"));
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });

test("the card switches modes, keeps the email, and reopens in Log in mode", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/providers")
    ? json({ providers: ["GOOGLE", "APPLE"] }) : json({}, 401));
  render(<App />);
  const user = userEvent.setup();
  const opener = screen.getByRole("button", { name: "Sign in" });
  await user.click(opener);
  const card = screen.getByRole("dialog", { name: "Log in" });
  expect(card).toHaveClass("sign-in-card");
  await user.type(await within(card).findByLabelText("Email"), "buyer@example.com");
  await within(card).findByRole("button", { name: "Continue with Google" });
  const email = within(card).getByLabelText("Email");
  const google = within(card).getByRole("button", { name: "Continue with Google" });
  expect(email.compareDocumentPosition(google) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await user.click(within(card).getByRole("button", { name: "Sign up" }));
  expect(card).toHaveAccessibleName("Sign up");
  expect(within(card).getByLabelText("Email")).toHaveValue("buyer@example.com");
  expect(within(card).getByRole("button", { name: "Continue with Google" }).compareDocumentPosition(within(card).getByLabelText("Email")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(within(card).getByText("Optional. Keeps your cases in one place.")).toBeVisible();
  expect(within(card).getByText("Have a private case link?")).not.toBeVisible();
  await user.click(within(card).getByRole("button", { name: "Log in" }));
  expect(card).toHaveAccessibleName("Log in");
  await user.click(within(card).getByRole("button", { name: "Sign up" }));
  await user.keyboard("{Escape}");
  expect(opener).toHaveFocus();
  await user.click(opener);
  expect(screen.getByRole("dialog", { name: "Log in" })).toBeVisible();
  await user.click(document.querySelector(".side-panel-backdrop:not([hidden])")!);
  expect(screen.queryByRole("dialog")).toBeNull();
  expect(opener).toHaveFocus();
});

test.each([[], ["GOOGLE"], ["APPLE"], ["GOOGLE", "APPLE", "OTHER"]].map(providers => ({ providers })))("offers only discovered providers (%j)", async ({ providers }) => {
  const request = vi.fn().mockResolvedValue(json({ providers }));
  const { container } = render(<SignIn request={request} onSignedIn={async () => {}} />);
  await waitFor(() => expect(request).toHaveBeenCalledWith("/api/v1/account/social-sign-in/providers"));
  await waitFor(() => expect(screen.queryAllByRole("button", { name: /Continue with (Google|Apple)/ })).toHaveLength(providers.filter(p => p !== "OTHER").length));
  expect(container.querySelector(".sign-in-divider") !== null).toBe(providers.length > 0);
});

test("discovery failure hides providers and the divider without blocking email", async () => {
  const request = vi.fn().mockRejectedValue({ status: 503 });
  const { container } = render(<SignIn request={request} onSignedIn={async () => {}} />);
  await waitFor(() => expect(request).toHaveBeenCalled());
  expect(container.querySelector(".sign-in-divider")).toBeNull();
  expect(screen.queryByRole("button", { name: "Continue with Google" })).toBeNull();
  expect(screen.getByRole("button", { name: "Continue with email" })).toBeEnabled();
});

test.each(["GOOGLE", "APPLE"])("starts %s with the account header and navigates to authorization", async provider => {
  const assign = vi.fn();
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/providers")
    ? json({ providers: [provider] }) : String(input) === "/api/v1/account/social-sign-in"
      ? json({ authorizationUrl: "https://provider.example/authorize" }) : json({}, 401));
  render(<BuyerAccount />);
  const user = userEvent.setup();
  const button = await screen.findByRole("button", { name: `Continue with ${provider === "GOOGLE" ? "Google" : "Apple"}` });
  const realWindow = window;
  vi.stubGlobal("window", new Proxy(realWindow, { get: (target, key) => key === "location" ? { assign } : Reflect.get(target, key) }));
  await user.click(button);
  await waitFor(() => expect(assign).toHaveBeenCalledWith("https://provider.example/authorize"));
  const [, init] = vi.mocked(fetch).mock.calls.find(([path]) => path === "/api/v1/account/social-sign-in")!;
  expect(JSON.parse(String(init?.body))).toEqual({ provider });
  expect(init?.headers).toHaveProperty("Account-Request", "1");
});

test.each(["GOOGLE", "APPLE", undefined])("completes a return once, loads the account and closes the card (%s)", async provider => {
  window.history.replaceState(null, "", "/#social-sign-in=single-use-state");
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/complete")
    ? json({ expiresAt: "2026-10-27T00:00:00Z", provider }) : String(input) === "/api/v1/account"
      ? json(account) : json([]));
  render(<StrictMode><App /></StrictMode>);
  const notice = provider === "GOOGLE" ? "Signed in with Google." : provider === "APPLE" ? "Signed in with Apple." : "Signed in.";
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(notice));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
  expect(window.location.hash).toBe("");
  const calls = fetcher.mock.calls.filter(([path]) => String(path).endsWith("/social-sign-in/complete"));
  expect(calls).toHaveLength(1);
  expect(JSON.parse(String(calls[0][1]?.body))).toEqual({ state: "single-use-state" });
  expect(calls[0][1]?.headers).toHaveProperty("Account-Request", "1");
  await userEvent.click(await screen.findByRole("button", { name: account.contact.email }));
  expect(screen.getByRole("dialog", { name: "Your account" })).not.toHaveClass("sign-in-card");
});

test("a provider start failure stays in the card with the server detail", async () => {
  const detail = "Google sign-in is temporarily unavailable. Continue with your email.";
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/providers")
    ? json({ providers: ["GOOGLE"] }) : String(input) === "/api/v1/account/social-sign-in"
      ? json({ detail }, 503) : json({}, 401));
  render(<App />);
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await userEvent.click(await screen.findByRole("button", { name: "Continue with Google" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(detail);
  expect(screen.getByRole("dialog", { name: "Log in" })).toBeVisible();
  expect(screen.getByRole("button", { name: "Continue with email" })).toBeEnabled();
});

test.each(["cancelled", "failed"])("an error return opens the card and removes the %s hash", async error => {
  window.history.replaceState(null, "", `/#social-sign-in-error=${error}`);
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => json({}, 401));
  render(<App />);
  const card = screen.getByRole("dialog", { name: "Log in" });
  expect(await within(card).findByRole("alert")).toHaveTextContent(error === "cancelled"
    ? "Sign-in with your provider was cancelled." : "Sign-in with your provider could not be completed. Try again or continue with your email.");
  await within(card).findByLabelText("Email");
  expect(window.location.hash).toBe("");
});

test("a failed completion displays the server's detail and leaves email usable", async () => {
  window.history.replaceState(null, "", "/#social-sign-in=expired-state");
  const detail = "Sign-in with Apple could not be completed. Try again or continue with your email.";
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => String(input).endsWith("/complete")
    ? json({ detail }, 400) : json({}, 401));
  render(<App />);
  expect(await screen.findByRole("alert")).toHaveTextContent(detail);
  expect(await screen.findByRole("button", { name: "Continue with email" })).toBeEnabled();
});

test("a social return arriving on hashchange opens the card", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => json({}, 401));
  render(<App />);
  expect(screen.queryByRole("dialog")).toBeNull();
  window.location.hash = "social-sign-in-error=cancelled";
  expect(await screen.findByRole("dialog", { name: "Log in" })).toBeVisible();
  expect(await screen.findByRole("alert")).toHaveTextContent("cancelled");
  expect(window.location.hash).toBe("");
});
