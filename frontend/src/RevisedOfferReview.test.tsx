import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import { RevisedOfferReview, type DealReview } from "./RevisedOfferReview";

const revisedReview: DealReview = {
  version: 1, baselineVersion: 1, previousOfferVersion: 2, currentOfferVersion: 3,
  status: "REVIEW_REQUIRED", checkedAt: "2026-09-07T12:00:00Z", tolerancePolicy: "All differences shown",
  scope: "Verified document terms", discrepancies: [{
    comparison: "PREVIOUS", term: "candidate:APR#1", kind: "CHANGED",
    expected: { value: "5", unit: "PERCENT" }, observed: { value: "5.01", unit: "PERCENT" }, delta: 0.01, unit: "PERCENT",
    severity: "INFORMATIONAL", reason: "APR increased",
    expectedEvidence: { id: "old", field: "APR", originalWording: "APR 5.00%", evidence: { pageNumber: 1, x: 0.1, y: 0.5, width: 0.3, height: 0.05 } },
    observedEvidence: { id: "new", field: "APR", originalWording: "APR 5.01%", evidence: { pageNumber: 2, x: 0.1, y: 0.25, width: 0.3, height: 0.05 } },
    confidence: 0.95, uncertain: false, buyerAction: "Review the APR",
  }, {
    comparison: "PREVIOUS", term: "line:doc fee#1", kind: "CHANGED",
    expected: { amountMinor: 49900, currency: "USD", category: "DEALER_FEE" }, observed: { amountMinor: 89900, currency: "USD", category: "DEALER_FEE" },
    delta: 40000, unit: "USD_MINOR", severity: "MATERIAL", reason: "The term is changed relative to PREVIOUS.",
    expectedEvidence: { originalLabel: "Doc fee", amountMinor: 49900, evidence: { pageNumber: 1, x: 0.1, y: 0.6, width: 0.5, height: 0.04 } },
    observedEvidence: { originalLabel: "Documentation fee", amountMinor: 89900, evidence: { pageNumber: 1, x: 0.1, y: 0.62, width: 0.5, height: 0.04 } },
    confidence: 0.9, uncertain: false, buyerAction: "Review this difference with the dealer before accepting.",
  }, {
    comparison: "BASELINE", term: "buyerMaxOutTheDoorPriceMinor", kind: "LIMIT_VIOLATION",
    expected: 2660000, observed: 2680000, delta: 20000, unit: "USD_MINOR", severity: "MATERIAL",
    reason: "The revised offer exceeds the approved ceiling.",
    expectedEvidence: { baselineId: "baseline-1", pointer: "#/evidence/approvedBaseline/payload/plan/buyerMaxOutTheDoorPriceMinor" },
    observedEvidence: { offerId: "offer-3", pointer: "#/evidence/currentOffer", basis: "Higher of quoted and recomputed amount" },
    confidence: null, uncertain: false, buyerAction: "Ask the dealer to reduce the offer within the approved limit.",
  }], cashDeltas: {
    baseline: { availability: "ESTIMATED", currency: "USD", preTaxDeltaMinor: 89900, taxEffectMinor: 2697, outTheDoorDeltaMinor: 92597,
      expectedOutTheDoorMinor: 2600000, observedOutTheDoorMinor: 2692597, expectedTaxesMinor: 150000, observedTaxesMinor: 152697 },
    previous: { availability: "UNAVAILABLE", reason: "Both offer calculations are required." },
  },
};

it("shows expected against observed as a difference with both source lines and the separately calculated tax effect", () => {
  const { container } = render(<RevisedOfferReview review={revisedReview} />);
  expect(screen.getByRole("heading", { name: "Revised Dealer Offer review" })).toBeInTheDocument();
  expect(screen.getByText(/Baseline 1 · Previous offer 2 · Current offer 3/)).toBeInTheDocument();
  expect(screen.getByText(/INFORMATIONAL/)).toBeInTheDocument();
  expect(screen.getByText("+0.01 percentage points")).toBeInTheDocument();

  const fee = screen.getByRole("heading", { name: "Doc fee · against the previous offer" }).closest("li")!;
  expect(within(fee).getByText("$499.00", { exact: false })).toBeInTheDocument();
  expect(within(fee).getByText("$899.00", { exact: false })).toBeInTheDocument();
  expect(within(fee).getByText("+$400.00")).toBeInTheDocument();
  expect(within(fee).getByText(/Read from page 1 of the dealer document \(Dealer Offer version 2\), where it says “Doc fee”/)).toBeInTheDocument();
  expect(within(fee).getByText(/Read from page 1 of the dealer document \(Dealer Offer version 3\), where it says “Documentation fee”/)).toBeInTheDocument();
  expect(within(fee).getAllByText(/Region: 10% from the left and 6\d% from the top/)).toHaveLength(2);
  expect(within(fee).getByText(/Unavailable: both offer calculations are needed/)).toBeInTheDocument();

  const limit = screen.getByRole("heading", { name: "Your out-the-door limit · against the Approved Baseline" }).closest("li")!;
  expect(within(limit).getByText(/your approved limit in Approved Baseline version 1. No dealer document source exists/)).toBeInTheDocument();
  expect(within(limit).getByText(/using the higher of quoted and recomputed amount/)).toBeInTheDocument();
  expect(within(limit).getByText(/Calculated separately for the whole offer, not per line: \$26.97/)).toBeInTheDocument();

  const baseline = screen.getByRole("region", { name: "Cash difference against baseline" });
  expect(within(baseline).getByText("Current offer taxes $1,526.97 − Approved Baseline taxes $1,500.00 = $26.97")).toBeInTheDocument();
  expect(within(baseline).getByText("Current offer $26,925.97 − Approved Baseline $26,000.00 = $925.97")).toBeInTheDocument();
  expect(within(baseline).getByText("$925.97 − tax effect $26.97 = $899.00")).toBeInTheDocument();
  expect(screen.getByText(/Unavailable. Both offer calculations/)).toBeInTheDocument();

  expect(container.querySelector("pre")).toBeNull();
  expect(container.textContent).not.toMatch(/[{}[\]]|"\w+"\s*:/);
});
