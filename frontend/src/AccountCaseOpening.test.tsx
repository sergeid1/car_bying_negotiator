import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

test("a case opened from the Buyer Account shows the workspace without a case link and closes at sign-out", async () => {
  let signedIn = true;
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const path = String(input);
    if (path === "/api/v1/account") return signedIn ? json({
      contact: { email: "buyer@example.com", firstName: null, lastName: null, phone: null }, createdAt: "", sessionExpiresAt: "",
      cases: [{ caseId: "case-1", attachedAt: "", state: "ACTIVE", startedAt: "2026-10-04T15:00:00Z", vehicle: "2021 Honda Accord", dealer: null }],
    }) : json({ code: "SESSION_ENDED" }, 401);
    if (path === "/api/v1/account/cases/case-1/open" && init?.method === "POST")
      return json({ caseId: "case-1", accessToken: "acct.case-1.1999999999.signature", accessExpiresAt: "2033-05-18T03:33:19Z" });
    if (path === "/api/v1/account/sign-out") { signedIn = false; return new Response(null, { status: 204 }); }
    if (path === "/api/v1/account/notifications" || path === "/api/v1/journeys") return json([]);
    return json({}, 404);
  });
  render(<App pollDelayMs={0} />);
  const user = userEvent.setup();

  // Signed-out copy shows until the account loads; then the intake says the case will be saved.
  expect(await screen.findByText(/this case will be saved to your Buyer Account/)).toBeVisible();
  await user.click(await screen.findByRole("button", { name: "Open 2021 Honda Accord, started Oct 4, 2026" }));

  const notice = await screen.findByRole("region", { name: "Opened from your Buyer Account" });
  expect(within(notice).getByText(/Signing out closes it here/)).toBeVisible();
  expect(screen.queryByRole("region", { name: "Private return link" })).toBeNull();
  expect(screen.queryByRole("heading", { name: "Start your Buying Case" })).toBeNull();
  expect(fetcher.mock.calls.some(([path, init]) => String(path).startsWith("/api/v1/cases/case-1")
    && (init?.headers as Record<string, string> | undefined)?.["Case-Access-Token"] === "acct.case-1.1999999999.signature")).toBe(true);

  await user.click(screen.getByRole("button", { name: "Sign out" }));
  await waitFor(() => expect(screen.queryByRole("region", { name: "Opened from your Buyer Account" })).toBeNull());
  expect(await screen.findByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  expect(await screen.findByText(/No account is required/)).toBeVisible();
});
