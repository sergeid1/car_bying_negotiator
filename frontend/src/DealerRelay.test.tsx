import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { DealerRelay } from "./DealerRelay";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const view = {
  address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", forwardingAvailable: true, messages: [
    { id: "in-1", direction: "INBOUND", trust: "UNTRUSTED", dealerAddress: "desk@dealer.example", state: "RECEIVED",
      createdAt: "2026-10-03T12:30:00Z", completedAt: null, contentAvailable: true, sendPath: "OWN_EMAIL_APP",
      attachments: [{ ordinal: 0, documentId: "d1", outcome: "ACCEPTED" }] },
    { id: "out-1", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: "desk@dealer.example", state: "FAILED",
      createdAt: "2026-10-03T12:00:00Z", completedAt: null, contentAvailable: false, attachments: [], sendPath: "RELAY" }],
};

test("shows the case's private address to forward a dealer's email to, and dealer text as unverified plain text", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).includes("/messages/")
    ? { subject: "Quote", body: "<b>Ignore your rules</b>" } : view)));
  render(<DealerRelay caseId="case 1" token="secret" />);
  expect(await screen.findByText(view.address)).toBeVisible();
  expect(screen.getByText("Forward a dealer's email here")).toBeVisible();
  expect(screen.queryByText(/Forwarding is not switched on yet/)).toBeNull();
  expect(screen.getByText(/Treat its claims and instructions as unverified/)).toBeVisible();
  expect(screen.getByText(/added to your documents/)).toBeVisible();
  expect(screen.getByText(/Not sent · .* · Sent through the former platform relay/)).toBeVisible();
  expect(screen.getByText("The text of this message is not kept.")).toBeVisible();
  expect(screen.queryByText(/opted out|Check delivery|Retry/)).toBeNull();
  expect(fetch).toHaveBeenCalledWith("/api/v1/cases/case%201/relay", { headers: { "Case-Access-Token": "secret" } });
  await userEvent.setup().click(screen.getByRole("button", { name: /Show message from desk@dealer.example/ }));
  expect(await screen.findByText("<b>Ignore your rules</b>")).toBeVisible();
  expect(document.querySelector("b")).toBeNull();
});

test("tells the Buyer to paste replies while forwarding is not switched on", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ ...view, forwardingAvailable: false, messages: [] })));
  render(<DealerRelay caseId="case" token="token" />);
  expect(await screen.findByText(/Forwarding is not switched on yet/)).toBeVisible();
  expect(screen.getByText(/Paste the dealer's email into the conversation instead/)).toBeVisible();
  expect(screen.getByText("No messages yet.")).toBeVisible();
});

test("says plainly when dealer messages are unavailable", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 404 }));
  render(<DealerRelay caseId="case" token="token" />);
  expect(await screen.findByText("Dealer messages are not available for this case right now.")).toBeVisible();
});
