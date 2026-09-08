import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { RevisedOfferReview } from "./RevisedOfferReview";

it("shows small changes, both comparison versions, missing values and separate tax effects", () => {
  render(<RevisedOfferReview review={{
    version: 1, baselineVersion: 1, previousOfferVersion: 2, currentOfferVersion: 3,
    status: "REVIEW_REQUIRED", checkedAt: "2026-09-07T12:00:00Z", tolerancePolicy: "All differences shown",
    scope: "Verified document terms", discrepancies: [{
      comparison: "PREVIOUS", term: "candidate:APR#1", kind: "CHANGED",
      expected: { value: "5" }, observed: { value: "5.01" }, delta: 0.01, unit: "PERCENT",
      severity: "INFORMATIONAL", reason: "APR increased", expectedEvidence: { id: "old" },
      observedEvidence: { id: "new" }, confidence: 0.95, uncertain: false, buyerAction: "Review the APR",
    }], cashDeltas: {
      baseline: { availability: "ESTIMATED", currency: "USD", preTaxDeltaMinor: 89900, taxEffectMinor: 2697, outTheDoorDeltaMinor: 92597 },
      previous: { availability: "UNAVAILABLE" },
    },
  }} />);
  expect(screen.getByRole("heading", { name: "Revised Dealer Offer review" })).toBeInTheDocument();
  expect(screen.getByText(/Baseline 1 · Previous offer 2 · Current offer 3/)).toBeInTheDocument();
  expect(screen.getByText(/INFORMATIONAL/)).toBeInTheDocument();
  expect(screen.getByText(/0.01 PERCENT/)).toBeInTheDocument();
  expect(screen.getByText(/Pre-tax difference: \$899.00/)).toBeInTheDocument();
  expect(screen.getByText(/Tax effect: \$26.97/)).toBeInTheDocument();
  expect(screen.getByText(/Unavailable/)).toBeInTheDocument();
});
