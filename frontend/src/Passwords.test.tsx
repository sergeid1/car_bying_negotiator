import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { Passwords } from "./Passwords";
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
