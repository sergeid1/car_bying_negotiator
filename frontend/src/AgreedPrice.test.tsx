import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { AgreedPrice } from "./AgreedPrice";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });
const text = "The Buyer accepts the itemized quote, subject to a test drive and the final paperwork matching that quote.";
const draft = { id: "a1", kind: "ACCEPTANCE", state: "DRAFT", offerVersion: 3, planVersion: 2,
  gatedDraft: { draft: { text }, decision: { pass: true, gateVersion: "outbound-gate.v2", reasons: [] } } };
const props = { caseId: "c1", token: "secret", quotes: [{ id: "q1", dealerAddress: "sales@dealer.example", state: "SENT" }],
  sources: [{ offerVersion: 3 }], onChanged: vi.fn(async () => {}) };

test("asks for written confirmation when the Buyer selects a verbal or partial figure", async () => {
  const fetcher = vi.fn(async () => new Response(JSON.stringify({ requests: [], agreedPrice: null })));
  vi.stubGlobal("fetch", fetcher);
  render(<AgreedPrice {...props} />);
  fireEvent.change(await screen.findByRole("combobox", { name: "Dealer conversation" }), { target: { value: "q1" } });
  fireEvent.click(screen.getByRole("button", { name: "Review acceptance or confirmation request" }));
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-acceptance", expect.objectContaining({
    method: "POST", body: JSON.stringify({ quoteRequestId: "q1", offerVersion: null }),
    headers: expect.objectContaining({ "Case-Access-Token": "secret", "Idempotency-Key": expect.any(String) }),
  })));
});

test("sends the exact displayed acceptance text only after explicit baseline approval", async () => {
  const fetcher = vi.fn(async () => new Response(JSON.stringify({ requests: [draft], agreedPrice: null })));
  vi.stubGlobal("fetch", fetcher);
  render(<AgreedPrice {...props} />);
  const button = await screen.findByRole("button", { name: "Approve this acceptance text and baseline" });
  expect(fetcher.mock.calls).toHaveLength(1);
  expect(screen.getByText(/the Agreed Price is saved with an Approved Baseline/)).toBeInTheDocument();
  fireEvent.click(button);
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-acceptance/a1/approval", expect.objectContaining({
    method: "POST", body: JSON.stringify({ approved: true, text }),
  })));
});

test("a stopped gate cannot be approved and a sent confirmation asks for review", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ requests: [
    { ...draft, gatedDraft: { ...draft.gatedDraft, decision: { pass: false, gateVersion: "v2", reasons: [{ code: "NO_ACTIVE_MANDATE", text: "Approve a current mandate." }] } } },
    { ...draft, id: "a2", kind: "WRITTEN_CONFIRMATION", state: "SENT" },
  ], agreedPrice: null }))));
  render(<AgreedPrice {...props} />);
  expect(await screen.findByRole("button", { name: "Approve this acceptance text and baseline" })).toBeDisabled();
  expect(screen.getByText(/Written confirmation requested/)).toBeInTheDocument();
});

test("an Agreed Price exposes a credentialed summary download and cannot be accepted again", async () => {
  const create = vi.fn(() => "blob:summary"), revoke = vi.fn();
  vi.stubGlobal("URL", { createObjectURL: create, revokeObjectURL: revoke });
  const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  const fetcher = vi.fn(async (url: string) => new Response(url.endsWith("/summary") ? "<!doctype html>summary" : JSON.stringify({ requests: [draft],
    agreedPrice: { offerVersion: 3, baselineVersion: 2, actor: "BUYER", acceptedAt: "2026-10-04T12:00:00Z", journey: { journeyLinked: false, otherOpenNegotiations: [] } } })));
  vi.stubGlobal("fetch", fetcher);
  render(<AgreedPrice {...props} />);
  fireEvent.click(await screen.findByRole("button", { name: "Download one-page Agreed Price summary" }));
  await waitFor(() => expect(click).toHaveBeenCalled());
  expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-acceptance/summary", { headers: { "Case-Access-Token": "secret" } });
  expect(screen.queryByRole("combobox", { name: "Dealer conversation" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Approve this acceptance text and baseline" })).toBeDisabled();
});

test("offers explicit pause and stop choices only for other negotiations returned by the journey seam", async () => {
  const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(url.includes("/other-negotiations/")
    ? { journeyLinked: true, otherOpenNegotiations: [] }
    : { requests: [], agreedPrice: { offerVersion: 3, baselineVersion: 2, actor: "BUYER", acceptedAt: "2026-10-04T12:00:00Z",
      journey: { journeyLinked: true, otherOpenNegotiations: [{ caseId: "other", label: "Another dealer" }] } } })));
  vi.stubGlobal("fetch", fetcher);
  render(<AgreedPrice {...props} />);
  const pause = await screen.findByRole("button", { name: "Pause Another dealer" });
  expect(screen.getByRole("button", { name: "Stop Another dealer" })).toBeEnabled();
  fireEvent.click(pause);
  await waitFor(() => expect(fetcher).toHaveBeenCalledWith("/api/v1/cases/c1/quote-acceptance/other-negotiations/other", expect.objectContaining({
    method: "POST", body: JSON.stringify({ action: "PAUSE" }), headers: expect.objectContaining({ "Case-Access-Token": "secret" }),
  })));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Pause Another dealer" })).not.toBeInTheDocument());
});
