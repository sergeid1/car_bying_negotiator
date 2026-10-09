import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { Passwords, PasswordSignIn } from "./Passwords";
import { BuyerAccount } from "./BuyerAccount";

afterEach(() => { cleanup(); vi.restoreAllMocks(); window.history.replaceState(null, "", "/"); });

test("breached passwords and recent-sign-in problems are actionable and clear the secret", async () => {
  const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ configured: false })));
  render(<Passwords request={request} onSessionEnded={() => {}} />);
  const user = userEvent.setup();
  const input = await screen.findByLabelText("New password");
  for (const detail of ["This password appears in known data breaches. Choose another password.", "Sign in again before changing your password."]) {
    await user.type(input, "a sufficiently long phrase");
    request.mockRejectedValueOnce({ status: 400, detail });
    await user.click(screen.getByRole("button", { name: "Set password" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(detail);
    expect(input).toHaveValue("");
  }
});

test("password sign-in failures stay on the form and clear the password", async () => {
  const request = vi.fn().mockRejectedValue({ status: 401, detail: "Check your details or use an email link." });
  const signedIn = vi.fn();
  render(<PasswordSignIn request={request} onSignedIn={signedIn} />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Email for password sign-in"), "buyer@example.com");
  await user.type(screen.getByLabelText("Password", { exact: true }), "a long private phrase");
  await user.click(screen.getByRole("button", { name: "Sign in with a password" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("use an email link");
  expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
  expect(signedIn).not.toHaveBeenCalled();
});

test("an emailed recovery link can replace an already signed-in session", async () => {
  window.history.replaceState(null, "", "/#sign-in=fresh-link");
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const path = String(input);
    if (path.endsWith("/password")) return new Response(JSON.stringify({ configured: true }));
    if (path.endsWith("/passkeys") || path.endsWith("/notifications")) return new Response("[]");
    return new Response(JSON.stringify({ contact: { email: "buyer@example.com" }, cases: [] }));
  });
  render(<BuyerAccount />);
  await screen.findByText("buyer@example.com");
  await userEvent.setup().click(screen.getByRole("button", { name: "Finish signing in" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/account/sign-in/verify", expect.objectContaining({
    method: "POST", body: JSON.stringify({ token: "fresh-link" }),
  })));
  expect(window.location.hash).toBe("");
  await waitFor(() => expect(screen.queryByRole("button", { name: "Finish signing in" })).toBeNull());
});
