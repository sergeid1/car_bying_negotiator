import type { DeskTacticFinding } from "./DeskTactics";

/** Synthetic development evidence only. */
export const syntheticTactic: DeskTacticFinding = {
  code: "PAYMENT_PACKING", label: "Possible payment packing", summary: "The quoted payment is $40.00 higher per month than the worksheet's own numbers produce.",
  monthlyEffectMinor: 4000, totalEffectMinor: 240000, effectBasis: "Monthly difference multiplied by 60 months; actual final payment and timing may differ.",
  noDollarEffectReason: null, deskQuestion: "Please show how the payment follows from the amount financed, APR and term.",
  questionReview: "PENDING_OPERATOR_REVIEW", policyVersion: "desk-tactics.v1", verified: false,
  evidenceRegions: [{ field: "MONTHLY_PAYMENT", region: { pageNumber: 2 }, verified: false }],
};

/** Synthetic ticket 103 results named by the v2 tactic policy. */
export const syntheticTradeTactics: DeskTacticFinding[] = [
  { ...syntheticTactic, code: "NEGATIVE_EQUITY_FOLDED_IN", label: "Negative equity folded in", policyVersion: "desk-tactics.v2",
    tradeFindingCode: "NEGATIVE_EQUITY_FINANCED", summary: "Negative equity is included in the loan.", monthlyEffectMinor: 13093,
    totalEffectMinor: 942696, effectBasis: "From trade-desk-check.v1; equity basis PAPERWORK_PAYOFF; scheduled effect includes principal and interest.",
    deskQuestion: "Please show where the amount still owed on my trade is included in the loan, and its payment and interest effect." },
  { ...syntheticTactic, code: "ALLOWANCE_PAID_FOR_BY_PRICE", label: "Allowance paid for by the price", policyVersion: "desk-tactics.v2",
    tradeFindingCode: "ALLOWANCE_OFFSET_BY_PRICE", summary: "The price increase offsets the extra trade allowance.", monthlyEffectMinor: null,
    totalEffectMinor: -100000, effectBasis: "Net trade-and-price effect from trade-desk-check.v1; negative means the Buyer is behind; no monthly effect.",
    deskQuestion: "Please show the trade allowance with the selling price and discount we agreed, so I can compare their combined effect." },
];
