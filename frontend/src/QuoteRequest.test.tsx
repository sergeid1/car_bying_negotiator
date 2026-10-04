import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import axe from "axe-core";
import { afterEach, expect, test, vi } from "vitest";
import { QuoteRequest } from "./QuoteRequest";
import type { GateDecision } from "./OutboundGateDecision";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const quote = { id: "q1", dealerAddress: "sales@dealer.example", state: "DRAFT",
  gatedDraft: { draft: { text: "Could you provide an itemized out-the-door quote for VIN 1HGCM82633A004352?" },
    decision: { pass: true, gateVersion: "outbound-gate.v2", reasons: [] } as GateDecision } };
function mock(view = { negotiationState: "MANDATE_ACTIVE", requests: [quote], documents: [], dealers: [{ id: "d1", name: "Dealer" }] }) {
  const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith("/relay")
    ? { address: "relay@example.test", messages: [], perCaseDailyCap: 10, sentLastDay: 0 } : view)));
  vi.stubGlobal("fetch", fetcher); return fetcher;
}
test("reviews the gated text accessibly and sends only on explicit approval", async () => {
  const fetcher = mock();
  const { container } = render(<QuoteRequest caseId="c1" token="secret" />);
  const approve = await screen.findByRole("button", { name: "Approve this text and send" });
  expect(fetcher.mock.calls.every(([url]) => !url.includes("approval"))).toBe(true);
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
  fireEvent.click(approve);
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-requests/q1/approval", expect.objectContaining({
    method: "POST", body: JSON.stringify({ approved: true, text: quote.gatedDraft.draft.text }),
    headers: expect.objectContaining({ "Case-Access-Token": "secret" }),
  })));
});
test("a blocked draft cannot be approved and expiry is explained", async () => {
  mock({ negotiationState: "EXPIRED", requests: [{ ...quote, gatedDraft: { ...quote.gatedDraft,
    decision: { pass: false, gateVersion: "outbound-gate.v2", reasons: [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] } } }], documents: [], dealers: [] });
  render(<QuoteRequest caseId="c1" token="secret" />);
  expect(await screen.findByRole("button", { name: "Approve this text and send" })).toBeDisabled();
  expect(screen.getByText(/Your mandate expired/)).toBeInTheDocument();
});

test("prepares a request using the dealer ID supplied by the quote view", async () => {
  const fetcher = mock();
  render(<QuoteRequest caseId="c1" token="secret" />);
  const dealer = await screen.findByRole("combobox", { name: "Dealer" });
  expect(screen.getByRole("option", { name: "Dealer" })).toHaveValue("d1");
  fireEvent.change(dealer, { target: { value: "d1" } });
  fireEvent.change(screen.getByRole("textbox", { name: "Dealer email" }), { target: { value: "sales@dealer.example" } });
  fireEvent.click(screen.getByRole("button", { name: "Prepare request" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-requests", expect.objectContaining({
    method: "POST", body: JSON.stringify({ dealerId: "d1", dealerAddress: "sales@dealer.example" }),
  })));
});

test("an empty eligible dealer list explains what is needed accessibly", async () => {
  mock({ negotiationState: "NO_MANDATE", requests: [], documents: [], dealers: [] });
  const { container } = render(<QuoteRequest caseId="c1" token="secret" />);
  expect(await screen.findByText(/No dealers have a listing that matches/)).toBeInTheDocument();
  expect(screen.queryByRole("combobox", { name: "Dealer" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Prepare request" })).not.toBeInTheDocument();
  expect((await axe.run(container, { rules: { "color-contrast": { enabled: false } } })).violations).toEqual([]);
});
