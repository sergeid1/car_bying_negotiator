import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { TradeCheckPanel, type TradeCheckResult } from "./TradeCheck";
import { DeskCheck, type DeskCheckView } from "./DeskCheck";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";
import { syntheticTradeTactics } from "./DeskTactics.fixture";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

/** Labelled synthetic: scenarios 28 and 30 on one worksheet, against a synthetic offer and range. */
function tradeCheckFixture(state: "CHECKED" | "OTHER_TRADE" | "UNRECORDED" = "CHECKED"): TradeCheckResult {
  const base: TradeCheckResult = {
    status: "CHECKED", allowanceMinor: 1150000, paperworkPayoffMinor: 1920000,
    buyerPayoff: { state: "CURRENT", amountMinor: 1800000, goodThrough: "2026-10-10" }, payoffDifferenceMinor: 120000,
    equityMinor: -770000, equityBasis: "PAPERWORK_PAYOFF", negativeEquityFinancedMinor: 770000,
    negativeEquityCost: { status: "AVAILABLE", reason: null, aprPercent: 6.9, termMonths: 72, paymentIncreaseMinor: 13093,
      scheduledPaymentsIncreaseMinor: 942696, interestMinor: 172696 },
    bestOffer: { id: "o1", amountMinor: 1400000, source: "Synthetic Car Exchange", offerDate: "2026-10-09", expiresOn: "2026-10-17",
      label: "the written offer from Synthetic Car Exchange dated 2026-10-09" },
    shortfallToOfferMinor: 250000,
    taxCredit: { status: "AVAILABLE", reason: null, jurisdiction: "US-NC", policyVersion: "US-NC.cash.v1", creditMinor: 34500 },
    offerGapAfterTaxMinor: 215500,
    valuation: { availability: "AVAILABLE", notice: "Synthetic trade-in range for development only.", source: "SYNTHETIC_TRADE_VALUATION",
      synthetic: true, retrievedAt: "2026-10-10T15:00:00Z", conditionAssumption: "GOOD", lowMinor: 1400000, typicalMinor: 1600000,
      highMinor: 1800000, position: "BELOW" },
    vinMatch: { status: "SAME", paperworkVin: "5J6RW2H52ML000002", recordedVin: "5J6RW2H52ML000002" },
    findings: [
      { code: "BELOW_WRITTEN_OFFER", severity: "WARNING", summary: "The trade-in allowance of $11,500 is $2,500 below the written offer from Synthetic Car Exchange dated 2026-10-09 for $14,000.",
        amountMinor: 250000, deskQuestion: "I have a written offer of $14,000 for my trade. Will you match it?", estimate: false },
      { code: "BELOW_VALUATION_RANGE", severity: "WARNING", summary: "The trade-in allowance of $11,500 is $2,500 below the low end of the trade-in range, $14,000 to $18,000 (an estimate from SYNTHETIC_TRADE_VALUATION).",
        amountMinor: 250000, deskQuestion: "The trade-in range I have starts at $14,000. How did you arrive at $11,500?", estimate: true },
      { code: "PAYOFF_DIFFERS", severity: "WARNING", summary: "This paperwork uses a payoff of $19,200, $1,200 more than your payoff of $18,000 valid through 2026-10-10.",
        amountMinor: 120000, deskQuestion: "My lender's payoff is $18,000 valid through 2026-10-10. Why does this use $19,200?", estimate: false },
      { code: "TRADE_VIN_SAME", severity: "INFORMATIONAL", summary: "The trade-in VIN on this paperwork is the same as your recorded trade-in.",
        amountMinor: null, deskQuestion: null, estimate: false },
    ],
  };
  if (state === "OTHER_TRADE") return { ...base, equityMinor: null, equityBasis: "UNAVAILABLE", negativeEquityFinancedMinor: null, negativeEquityCost: null,
    shortfallToOfferMinor: null, offerGapAfterTaxMinor: null, valuation: null, payoffDifferenceMinor: null,
    vinMatch: { status: "DIFFERENT", paperworkVin: "2T3P1RFV5MW000001", recordedVin: "5J6RW2H52ML000002" },
    findings: [{ code: "TRADE_VIN_DIFFERENT", severity: "BLOCKING", summary: "The trade-in VIN on this paperwork is 2T3P1RFV5MW000001, but your recorded trade-in is VIN 5J6RW2H52ML000002.",
      amountMinor: null, deskQuestion: "The trade-in VIN here is not my car's. Will you correct it to 5J6RW2H52ML000002?", estimate: false }] };
  if (state === "UNRECORDED") return { ...base, status: "TRADE_NOT_RECORDED", buyerPayoff: null, bestOffer: null, valuation: null,
    negativeEquityFinancedMinor: null, negativeEquityCost: null, shortfallToOfferMinor: null, offerGapAfterTaxMinor: null, payoffDifferenceMinor: null,
    vinMatch: { status: "NO_TRADE_RECORDED", paperworkVin: null, recordedVin: null },
    findings: [{ code: "TRADE_NOT_RECORDED", severity: "WARNING", summary: "This paperwork shows a trade-in allowance of $11,500, but no trade-in is recorded on this case.",
      amountMinor: null, deskQuestion: "What payoff amount and trade-in VIN did you use for this allowance?", estimate: false }] };
  return base;
}

test("the trade check leads with the shortfall, labels the range as an estimate and shows the figures behind it", () => {
  render(<TradeCheckPanel result={tradeCheckFixture()} />);
  const findings = within(screen.getByRole("list", { name: "Trade-in findings" })).getAllByRole("listitem");
  expect(findings[0]).toHaveTextContent("$2,500 below the written offer from Synthetic Car Exchange");
  expect(findings[0]).toHaveTextContent("Will you match it?");
  expect(within(findings[1]).getByText("Estimate")).toBeInTheDocument();
  expect(screen.getByText("Trade-in range, an estimate from SYNTHETIC_TRADE_VALUATION")).toBeInTheDocument();
  expect(screen.getByText(/\$14,000\.00 to \$18,000\.00, assuming good condition/)).toBeInTheDocument();
  expect(screen.getByText("$19,200.00")).toBeInTheDocument();
  expect(screen.getByText("$18,000.00 valid through 2026-10-10")).toBeInTheDocument();
  expect(screen.getByText("−$7,700.00")).toBeInTheDocument();
  expect(screen.getByText(/\$130\.93 a month, \$9,426\.96 in scheduled payments for 72 months, \$1,726\.96 of it interest/)).toBeInTheDocument();
  expect(screen.getByText("About $345.00 under US-NC rules (US-NC.cash.v1)")).toBeInTheDocument();
  expect(screen.getByText(/never a discount on the car/)).toBeInTheDocument();
});

test("a stale or unknown payoff is said, and an unavailable range is never filled in", () => {
  const stale = tradeCheckFixture();
  render(<TradeCheckPanel result={{ ...stale, buyerPayoff: { state: "STALE", amountMinor: 1800000, goodThrough: "2026-10-01" },
    valuation: { ...stale.valuation!, availability: "UNAVAILABLE", notice: "Trade valuation provider unavailable. No value was substituted.",
      lowMinor: null, typicalMinor: null, highMinor: null, position: null } }} />);
  expect(screen.getByText(/past its valid-through date/)).toBeInTheDocument();
  expect(screen.getByText("Unavailable. Trade valuation provider unavailable. No value was substituted.")).toBeInTheDocument();
  cleanup();
  render(<TradeCheckPanel result={{ ...stale, buyerPayoff: { state: "UNKNOWN", amountMinor: null, goodThrough: null }, equityMinor: null }} />);
  expect(screen.getByText("Unknown")).toBeInTheDocument();
  expect(screen.getByText("Unavailable until the payoff is known")).toBeInTheDocument();
});

test("the Desk Check leads its trade group with the trade check's own sentence and shows the panel", async () => {
  const view: DeskCheckView = {
    status: "DIFFERENCES", source: "READ_FROM_PAPERWORK",
    agreedPrice: { offerVersion: 2, baselineVersion: 1, vin: "1HGCM82633A004352", sellingPriceMinor: 2500000, outTheDoorMinor: 2680000, acceptedAt: "2026-10-04T12:00:00Z" },
    paperwork: { documentId: "d2", uploadedAt: "2026-10-10T13:00:00Z", offerVersion: null, vin: "1HGCM82633A004352" },
    findings: [{ group: "TRADE", term: "trade:BELOW_WRITTEN_OFFER", label: "BELOW_WRITTEN_OFFER", kind: "TRADE_CHECK", agreed: null, desk: null,
      delta: 250000, unit: "USD_MINOR", severity: "WARNING", verified: false, evidence: { pageNumber: 2 },
      buyerAction: "I have a written offer of $14,000 for my trade. Will you match it?" }],
    otherDifferences: [], unchangedTerms: 12,
    totalEffect: { basis: "QUOTED", currency: "USD", outTheDoorDeltaMinor: 0, preTaxDeltaMinor: null, taxEffectMinor: null, addedChargesMinor: 0 },
    freshCase: null, dealReviewVersion: null, trade: tradeCheckFixture(),
  };
  vi.spyOn(globalThis, "fetch").mockImplementation(async input =>
    String(input).endsWith("/desk-check") ? new Response(JSON.stringify(view)) : new Response(null, { status: 404 }));
  render(<DeskCheck caseId="case" token="token" />);
  const differences = await screen.findByRole("list", { name: "Differences from your Agreed Price" });
  expect(within(differences).getAllByRole("listitem")[0]).toHaveTextContent("$2,500 below the written offer from Synthetic Car Exchange");
  expect(screen.getByRole("heading", { name: "Trade-in check" })).toBeInTheDocument();
});

test("a report with a trade check shows it", () => {
  const report: CoreReport = { version: 1, status: "PARTIAL", asOf: "2026-10-10", analysis: {
    reviewLabel: "Review required", dealRating: "INSUFFICIENT_DATA", ratingBasis: "Selling price", nextAction: "Ask the dealer",
    topReasons: [tradeCheckFixture().findings[0].summary], marketAvailability: "UNAVAILABLE", cashAvailability: "AVAILABLE",
    materialAmounts: [], policyVersions: {}, limitations: [], tradeCheck: tradeCheckFixture(),
  }, evidence: {}, reproduction: {} };
  render(<CoreDealReport report={report} />);
  expect(screen.getByRole("heading", { name: "Trade-in check" })).toBeInTheDocument();
  expect(screen.getByRole("list", { name: "Trade-in findings" })).toHaveTextContent("$1,200 more than your payoff");
});

test("trade tactics label the existing report lines and keep one question per line", () => {
  const trade: TradeCheckResult = { ...tradeCheckFixture(), findings: syntheticTradeTactics.map(tactic => ({
    code: tactic.tradeFindingCode!, summary: tactic.summary, amountMinor: tactic.totalEffectMinor,
    deskQuestion: "Original trade question", severity: "WARNING", estimate: false,
  })) };
  const report: CoreReport = { version: 2, status: "PARTIAL", asOf: "2026-10-10", analysis: {
    reviewLabel: "Review required", dealRating: "INSUFFICIENT_DATA", ratingBasis: "Selling price", nextAction: "Ask the dealer",
    topReasons: [], marketAvailability: "UNAVAILABLE", cashAvailability: "AVAILABLE", materialAmounts: [],
    policyVersions: {}, limitations: [], tradeCheck: trade, tacticFindings: syntheticTradeTactics,
  }, evidence: {}, reproduction: {} };
  render(<CoreDealReport report={report} />);
  for (const tactic of syntheticTradeTactics) expect(screen.getAllByText(tactic.label)).toHaveLength(1);
  expect(screen.queryByText("Original trade question")).not.toBeInTheDocument();
  expect(screen.queryByRole("region", { name: "Worksheet tactics" })).not.toBeInTheDocument();
  expect(screen.queryByText(/Monthly effect:/)).not.toBeInTheDocument();
});
