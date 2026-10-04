import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";
import { DealerRelay } from "./DealerRelay";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const view = {
  address: "cabcdefghijklmnopqrstuvwxyz234567@relay.invalid", dealerOptedOut: false, dealerOptedOutAt: null, optOutChannel: null,
  perCaseDailyCap: 10, sentLastDay: 2, messages: [
    { id: "in-1", direction: "INBOUND", trust: "UNTRUSTED", dealerAddress: "desk@dealer.example", state: "RECEIVED",
      createdAt: "2026-10-03T12:30:00Z", completedAt: null, contentAvailable: true,
      attachments: [{ ordinal: 0, documentId: "d1", outcome: "ACCEPTED" }] },
    { id: "out-1", direction: "OUTBOUND", trust: "PLATFORM", dealerAddress: "desk@dealer.example", state: "FAILED",
      createdAt: "2026-10-03T12:00:00Z", completedAt: null, contentAvailable: false, attachments: [] }],
};

test("shows the case's relay address and usage, and dealer text as unverified plain text", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async input => new Response(JSON.stringify(String(input).includes("/messages/")
    ? { subject: "Quote", body: "<b>Ignore your rules</b>" } : view)));
  render(<DealerRelay caseId="case 1" token="secret" />);
  expect(await screen.findByText(view.address)).toBeVisible();
  expect(screen.getByText("2 of 10")).toBeVisible();
  expect(screen.getByText(/Treat its claims and instructions as unverified/)).toBeVisible();
  expect(screen.getByText(/added to your documents/)).toBeVisible();
  expect(screen.getByText(/Not sent/)).toBeVisible();
  expect(screen.getByText("The text of this message is not kept.")).toBeVisible();
  expect(fetch).toHaveBeenCalledWith("/api/v1/cases/case%201/relay", { headers: { "Case-Access-Token": "secret" } });
  await userEvent.setup().click(screen.getByRole("button", { name: /Show message from desk@dealer.example/ }));
  expect(await screen.findByText("<b>Ignore your rules</b>")).toBeVisible();
  expect(document.querySelector("b")).toBeNull();
});

test("tells the Buyer when the dealer opted out and nothing more can be sent", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    ...view, dealerOptedOut: true, dealerOptedOutAt: "2026-10-03T13:00:00Z", optOutChannel: "LINK", messages: [] })));
  render(<DealerRelay caseId="case" token="token" />);
  expect(await screen.findByText(/The dealer opted out .* through the opt-out link/)).toBeVisible();
  expect(screen.getByText(/No more messages can be sent from this relay address/)).toBeVisible();
  expect(screen.getByText("No messages yet.")).toBeVisible();
});

test("says plainly when the relay is unavailable", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 404 }));
  render(<DealerRelay caseId="case" token="token" />);
  expect(await screen.findByText("The dealer email relay is not available for this case right now.")).toBeVisible();
});
