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

test("offers a delivery check for an unconfirmed send and a retry for a not-sent one, then reloads", async () => {
  const offered = { ...view, messages: [
    { ...view.messages[1], id: "out-2", state: "DELIVERY_UNKNOWN", contentAvailable: true, deliveryCheckOffered: true, retryOffered: false },
    { ...view.messages[1], contentAvailable: true, deliveryCheckOffered: false, retryOffered: true }] };
  const after = { ...view, messages: [{ ...offered.messages[0], state: "SENT", deliveryCheckOffered: false }, offered.messages[1]] };
  let checked = false;
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    if (url.endsWith("/delivery-check")) { checked = true; return new Response(JSON.stringify({ messageId: "out-2", state: "SENT" })); }
    if (url.endsWith("/retry")) return new Response(JSON.stringify({ detail: "The original send is no longer permitted.", reason: "NOT_PERMITTED" }), { status: 409 });
    return new Response(JSON.stringify(checked ? after : offered));
  });
  render(<DealerRelay caseId="case" token="secret" />);
  expect(await screen.findByText(/Checking delivery asks the relay whether it has the message/)).toBeVisible();
  expect(screen.getAllByRole("button", { name: /^Check delivery/ })).toHaveLength(1);
  expect(screen.getAllByRole("button", { name: /^Retry/ })).toHaveLength(1);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: /^Check delivery of the message to desk@dealer.example/ }));
  expect(await screen.findByText("The relay has this message, so it is now marked as sent.")).toBeVisible();
  expect(fetch).toHaveBeenCalledWith("/api/v1/cases/case/relay/messages/out-2/delivery-check",
    { method: "POST", headers: { "Case-Access-Token": "secret" } });
  expect(screen.queryByRole("button", { name: /^Check delivery/ })).toBeNull();
  await user.click(screen.getByRole("button", { name: /^Retry the message to desk@dealer.example/ }));
  expect(await screen.findByText("The original send is no longer permitted.")).toBeVisible();
});
