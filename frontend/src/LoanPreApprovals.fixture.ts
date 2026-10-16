import type { LoanApplication, LoanOffer, LoanView } from "./LoanPreApprovals";

/** Synthetic loan views for client, axe and browser tests (ticket 129). No real lender or person. */
export const loanApplication: LoanApplication = {
  amountMinor: 3000000, termMonths: 60,
  vehicle: { vin: "1HGCM82633A004352", modelYear: 2023, make: "Honda", model: "Accord", mileage: 12000 },
  annualIncomeMinor: 8800000, housing: "RENT", monthlyHousingCostMinor: 185000,
};

const offer = (id: string, lender: string, aprPercent: number, paymentMinor: number, totalInterestMinor: number,
  extra: Partial<LoanOffer> = {}): LoanOffer => {
  const upfrontFeesMinor = extra.upfrontFeesMinor ?? 0;
  return { id, lender, source: "PARTNER", amountMinor: 3000000, aprPercent, termMonths: 60, upfrontFeesMinor, paymentMinor,
    totalInterestMinor, totalCostMinor: 3000000 + totalInterestMinor + upfrontFeesMinor, lenderPaymentMinor: null,
    lenderMinusCalculatedMinor: null, lenderPaymentWithinTolerance: null,
    conditions: { maximumVehicleAgeYears: 10, maximumMileage: 120000, minimumDownPaymentMinor: 0 },
    offeredAt: "2026-10-16T12:00:00Z", expiresAt: "2026-11-15T12:00:00Z",
    continueUrl: `https://synthetic-lending-partner.invalid/continue/${id}`, differences: [], chosen: false, ...extra };
};

export function loanView(state: "EMPTY" | "APPLIED" | "OFFERED" | "CHOSEN", overrides: Partial<LoanView> = {}): LoanView {
  const chosen = state === "CHOSEN";
  const view: LoanView = {
    holder: "CASE",
    caseVehicle: { vin: "1HGCM82633A004352", modelYear: 2023, make: "Honda", model: "Accord", mileage: 12000 },
    revision: state === "EMPTY" ? null : 2, changedAt: state === "EMPTY" ? null : "2026-10-16T12:00:00Z",
    application: state === "EMPTY" ? null : loanApplication,
    authorization: { version: "otdog-credit-authorization.draft-1", partner: "the synthetic test partner (development only)", synthetic: true, text: [
      "By choosing Get pre-approvals, you give written permission, under the Fair Credit Reporting Act, for the synthetic test partner (development only), a licensed lending partner, and the lenders that work through it to get your credit report with a soft inquiry, only to tell you whether you are pre-approved and on what terms.",
      "A soft inquiry does not affect your credit score. No lender may make a hard inquiry through otdog: a response that is not limited to soft inquiries is refused, and none of its offers is shown.",
      "otdog sends this application to the synthetic test partner (development only) and the lenders that work through it, and to no one else. It holds the amount, term, car, yearly income and housing. If the partner needs your name, address, date of birth or Social Security number, you enter them in the partner's own form; otdog never stores them.",
      "otdog does not sell your application or share it with anyone else. The partner and the lenders may use it only to give you pre-approvals, and may not contact you about it unless you continue with one of them.",
      "A pre-approval is not a loan. To take one, you continue on that lender's own site, which makes its own checks and asks before any hard inquiry. otdog never accepts an offer or signs a loan.",
    ] },
    results: state === "EMPTY" || state === "APPLIED" ? null : {
      request: { id: "request-1", requestedAt: "2026-10-16T12:00:00Z", retrievedAt: "2026-10-16T12:00:00Z", partner: "SYNTHETIC_PRE_APPROVAL",
        synthetic: true, availability: "AVAILABLE", notice: "Synthetic pre-approvals for development only.", outdated: false },
      sameTerms: [
        offer("own", "Space Coast Community Credit Union of Brevard County", 4.9, 56478, 388680,
          { source: "BUYER_ENTERED", continueUrl: null, expiresAt: null, conditions: null }),
        offer("coastal", "Coastal Auto Finance", 5.49, 57300, 438000, { chosen }),
        offer("bayfront", "Bayfront Credit", 5.49, 57300, 438000, { upfrontFeesMinor: 39500 }),
        offer("harbor", "Harbor Lending", 6.99, 59389, 563340, { lenderPaymentMinor: 61000, lenderMinusCalculatedMinor: 1611,
          lenderPaymentWithinTolerance: false }),
      ],
      differentTerms: [offer("gator", "Gator Federal", 3.9, 46800, 369600, { source: "BUYER_ENTERED", termMonths: 72,
        continueUrl: null, differences: ["TERM"] })],
      expired: [offer("old", "Old Letter Bank", 2.9, 53800, 228000, { source: "BUYER_ENTERED", continueUrl: null,
        expiresAt: "2026-10-15T04:00:00Z" })],
      withoutOffer: [{ lender: "Keys Bank", outcome: "DECLINED" }, { lender: "Pelican Lending", outcome: "TIMED_OUT" }],
    },
    chosen: chosen ? { offerId: "coastal", lender: "Coastal Auto Finance", source: "PARTNER", amountMinor: 3000000, aprPercent: 5.49,
      termMonths: 60, upfrontFeesMinor: 0, expiresAt: "2026-11-15T12:00:00Z", expired: false, chosenAt: "2026-10-16T12:05:00Z" } : null,
  };
  return { ...view, ...overrides };
}
