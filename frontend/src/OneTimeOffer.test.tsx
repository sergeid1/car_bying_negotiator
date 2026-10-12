import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";
import { OfferTerms } from "./OneTimeOffer";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";

const OFFER = {
  status: "AVAILABLE", offerId: "offer-1", kind: "ONE_TIME_PER_CASE", price: { amountMinor: 4900, currency: "USD" },
  paymentPoint: "BEFORE_REPORT",
  free: ["CASE_START", "VEHICLE_EVIDENCE", "DOCUMENT_UPLOAD", "TERM_VERIFICATION", "CASE_RECOVERY", "DOCUMENT_EXPORT", "CASE_DELETION"],
  paid: ["DEAL_ANALYSIS_REPORT", "REPORT_EXPORT", "REPORT_EXPLANATION", "NEGOTIATION_DRAFTS", "REPLY_ASSISTANCE", "REVISED_OFFER_REVIEW"],
  refund: { windowDays: 14, terms: "FULL_REFUND_ON_REQUEST_WITHIN_WINDOW" },
  jurisdiction: { code: "US-NC", name: "North Carolina" },
  excludedPurchaseTypes: ["NEW_VEHICLE", "LEASE", "PRIVATE_PARTY", "AUCTION", "MOTORCYCLE", "RV", "COMMERCIAL_VEHICLE"],
  scopeLimits: ["NO_DEALER_CONTACT", "NO_LEGAL_OR_TAX_CONCLUSIONS", "NO_VEHICLE_HISTORY", "NO_TRADE_IN_VALUATION", "NO_FINAL_CONTRACT_REVIEW"],
  checkout: "DEVELOPMENT_NO_CHARGE",
};

const report = { version: 1, asOf: "2026-09-30", status: "COMPLETE", analysis: {
  reviewLabel: "Review required", dealRating: "FAIR", ratingBasis: "Recorded evidence", nextAction: "Review the fees",
  topReasons: [], marketAvailability: "AVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [], policyVersions: {}, limitations: [],
}, evidence: { dealerOffer: { id: "o1" } }, reproduction: {} };

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

test("an available offer states free and paid scope, price, refunds, jurisdiction, exclusions and scope limits", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json(OFFER)));
  render(<OfferTerms />);
  const panel = await screen.findByRole("region", { name: "What is free and what you pay for" });
  const terms = within(panel);
  expect(terms.getAllByText(/\$49\.00/).length).toBeGreaterThan(0);
  expect(terms.getByText("Uploading the dealer's documents")).toBeVisible();
  expect(terms.getByText("Deleting your case")).toBeVisible();
  expect(terms.getByText("The Deal Analysis Report")).toBeVisible();
  expect(terms.getByText("Comparison when the dealer changes the quote")).toBeVisible();
  expect(terms.getByText("Full refund on request within 14 days of payment.")).toBeVisible();
  expect(terms.getByText("Pilot state: North Carolina.")).toBeVisible();
  expect(terms.getByText("Leases")).toBeVisible();
  expect(terms.getByText("No legal or tax conclusions.")).toBeVisible();
  expect(terms.getByText(/development checkout/i).closest("p")).toHaveTextContent("charges nothing");
  expect(panel).not.toHaveTextContent(/subscription|success fee|AI negotiator|safe to sign/i);
});

test("no selected pilot state is stated rather than left blank", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => json({ ...OFFER, jurisdiction: null })));
  render(<OfferTerms />);
  expect(await screen.findByText("No pilot state is selected yet.")).toBeVisible();
});

test.each([
  ["unavailable", () => json({ status: "UNAVAILABLE" })],
  ["failing", () => json({}, 500)],
  ["empty", () => new Response(null, { status: 204 })],
])("an %s offer shows no price and leaves intake unchanged", async (_name, reply) => {
  const fetcher = vi.fn(async (path: string) => path.endsWith("/intake-options")
    ? json({ supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } }) : reply());
  vi.stubGlobal("fetch", fetcher);
  render(<App />);
  expect(await screen.findByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
  await waitFor(() => expect(fetcher.mock.calls.some(([path]) => path === "/api/v1/offer")).toBe(true));
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(screen.queryByRole("region", { name: "What is free and what you pay for" })).toBeNull();
  expect(document.body).not.toHaveTextContent(/\$49|one-time/i);
});

test("the intake screen shows the terms before any case exists", async () => {
  vi.stubGlobal("fetch", vi.fn(async (path: string) => path.endsWith("/api/v1/offer") ? json(OFFER)
    : json({ supported: ["US-NC"], consentPolicies: { serviceProcessing: "service-processing.v1" } })));
  render(<App />);
  expect(await screen.findByRole("region", { name: "What is free and what you pay for" })).toBeVisible();
  expect(screen.getByRole("heading", { name: "Start your Buying Case" })).toBeVisible();
});

/** A case whose report reads are refused until the purchase is recorded. */
function paywalledCase(purchaseReplies: (() => Response)[]) {
  let paid = false;
  const fetcher = vi.fn(async (path: string, _options?: RequestInit) => {
    if (path.endsWith("/dealer-offers/latest")) return json({ id: "o1", version: 1, status: "VERIFIED" });
    if (path === "/api/v1/offer") return json(OFFER);
    if (path.endsWith("/purchases")) {
      const reply = purchaseReplies.shift()!();
      if (reply.status === 201 || reply.status === 200) paid = true;
      return reply;
    }
    if (path.endsWith("/reports/latest")) return paid ? json(report) : json({ code: "PURCHASE_REQUIRED", offerId: "offer-1" }, 402);
    if (path.endsWith("/negotiation")) return json({ language: { draft: "Review the fees", items: [] } });
    // Only the case read stands in; every other free panel sees an empty case.
    return path === "/api/v1/cases/case" ? json({}) : json({}, 404);
  });
  vi.stubGlobal("fetch", fetcher);
  return fetcher;
}

test("a refused report read shows the offer at the payment point, and a paid purchase opens the report", async () => {
  const fetcher = paywalledCase([() => { throw new Error("offline"); },
    () => json({ purchaseId: "p1", status: "PAID", checkoutUrl: null, checkout: "DEVELOPMENT_NO_CHARGE" }, 201)]);
  render(<VerifiedOfferAnalysis caseId="case" token="token" pollDelayMs={0} />);
  const pay = await screen.findByRole("button", { name: "Pay $49.00 and open the report" });
  expect(screen.queryByRole("alert")).toBeNull();
  expect(screen.getByText(/development checkout/i).closest("p")).toHaveTextContent("charges nothing");
  // Nothing is analyzed for an unpurchased case.
  expect(fetcher.mock.calls.some(([path]) => path.endsWith("/market-assessments") || path.endsWith("/analyses"))).toBe(false);

  fireEvent.click(pay);
  expect(await screen.findByRole("alert")).toHaveTextContent(/retry/i);
  fireEvent.click(screen.getByRole("button", { name: "Pay $49.00 and open the report" }));
  expect(await screen.findByRole("heading", { name: "Deal Analysis Report" })).toBeVisible();

  const purchases = fetcher.mock.calls.filter(([path]) => path === "/api/v1/cases/case/purchases");
  expect(purchases).toHaveLength(2);
  const headers = purchases.map(([, options]) => (options!.headers as Record<string, string>)["Idempotency-Key"]);
  expect(headers[0]).toBeTruthy();
  expect(headers[1]).toBe(headers[0]);
  expect(JSON.parse(String(purchases[0][1]!.body))).toEqual({ offerId: "offer-1" });
});

test("a changed offer is reloaded and confirmed again under a new request key", async () => {
  const fetcher = paywalledCase([() => json({ code: "OFFER_CHANGED" }, 409),
    () => json({ purchaseId: "p1", status: "PAID", checkoutUrl: null, checkout: "DEVELOPMENT_NO_CHARGE" }, 201)]);
  render(<VerifiedOfferAnalysis caseId="case" token="token" pollDelayMs={0} />);
  fireEvent.click(await screen.findByRole("button", { name: "Pay $49.00 and open the report" }));
  expect(await screen.findByText(/terms changed/i)).toBeVisible();
  expect(fetcher.mock.calls.filter(([path]) => path === "/api/v1/offer")).toHaveLength(2);
  fireEvent.click(screen.getByRole("button", { name: "Pay $49.00 and open the report" }));
  expect(await screen.findByRole("heading", { name: "Deal Analysis Report" })).toBeVisible();
});

test("a pending purchase with a checkout address goes there", async () => {
  const assign = vi.fn();
  vi.stubGlobal("location", { ...window.location, assign });
  paywalledCase([() => json({ purchaseId: "p1", status: "PENDING", checkoutUrl: "https://checkout.example/session", checkout: "PROVIDER" }, 201)]);
  render(<VerifiedOfferAnalysis caseId="case" token="token" pollDelayMs={0} />);
  fireEvent.click(await screen.findByRole("button", { name: "Pay $49.00 and open the report" }));
  await waitFor(() => expect(assign).toHaveBeenCalledWith("https://checkout.example/session"));
});

test("case recovery, export and deletion stay reachable while the report is unpaid", async () => {
  paywalledCase([]);
  window.history.replaceState(null, "", "/#case=case&access=token");
  try {
    render(<App pollDelayMs={0} />);
    await screen.findByRole("button", { name: "Pay $49.00 and open the report" });
    expect(screen.getByRole("region", { name: "Private return link" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Your case" }));
    expect(screen.getByRole("button", { name: "Export retained case data" })).toBeEnabled();
    expect(screen.getByRole("region", { name: "Optional email recovery" })).toBeVisible();
    fireEvent.click(screen.getByRole("checkbox", { name: /permanently delete/i }));
    expect(screen.getByRole("button", { name: "Delete Buying Case" })).toBeEnabled();
  } finally {
    window.history.replaceState(null, "", "/");
  }
});
