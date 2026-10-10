import type { DeskTacticFinding } from "./DeskTactics";

/** Synthetic development evidence only. */
export const syntheticTactic: DeskTacticFinding = {
  code: "PAYMENT_PACKING", label: "Possible payment packing", summary: "The quoted payment is $40.00 higher per month than the worksheet's own numbers produce.",
  monthlyEffectMinor: 4000, totalEffectMinor: 240000, effectBasis: "Monthly difference multiplied by 60 months; actual final payment and timing may differ.",
  noDollarEffectReason: null, deskQuestion: "Please show how the payment follows from the amount financed, APR and term.",
  questionReview: "PENDING_OPERATOR_REVIEW", policyVersion: "desk-tactics.v1", verified: false,
  evidenceRegions: [{ field: "MONTHLY_PAYMENT", region: { pageNumber: 2 }, verified: false }],
};
