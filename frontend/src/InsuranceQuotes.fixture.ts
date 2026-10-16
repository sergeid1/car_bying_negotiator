import type { CoverageLine, InsuranceApplication, InsuranceQuote, InsuranceView } from "./InsuranceQuotes";

/** Synthetic insurance views for client, axe and browser tests (ticket 126). No real insurer or person. */
export const insuranceGuide: InsuranceView["coverageGuide"] = [
  { type: "BODILY_INJURY_LIABILITY", label: "Bodily injury liability", explanation: "Pays for injuries you cause to other people in a crash, up to a limit for each person and a limit for each accident.", perPersonLimit: true, perAccidentLimit: true, deductible: false },
  { type: "PROPERTY_DAMAGE_LIABILITY", label: "Property damage liability", explanation: "Pays for damage you cause to other people's cars and property, up to the limit. Florida requires at least $10,000.", perPersonLimit: false, perAccidentLimit: true, deductible: false },
  { type: "PERSONAL_INJURY_PROTECTION", label: "Personal injury protection (PIP)", explanation: "Pays part of your own medical bills and lost income after a crash, whoever caused it. Florida requires $10,000. The deductible is what you pay before it pays.", perPersonLimit: true, perAccidentLimit: false, deductible: true },
  { type: "UNINSURED_MOTORIST", label: "Uninsured motorist", explanation: "Pays for your injuries when the driver who caused the crash has no insurance or too little.", perPersonLimit: true, perAccidentLimit: true, deductible: false },
  { type: "COLLISION", label: "Collision", explanation: "Pays to repair or replace your car after a crash, minus your deductible. A lender or lease usually requires it.", perPersonLimit: false, perAccidentLimit: false, deductible: true },
  { type: "COMPREHENSIVE", label: "Comprehensive", explanation: "Pays for theft, flood, hail, fire, hitting an animal and falling objects, minus your deductible. A lender or lease usually requires it.", perPersonLimit: false, perAccidentLimit: false, deductible: true },
];

const chosen: CoverageLine[] = [
  { type: "BODILY_INJURY_LIABILITY", perPersonLimitMinor: 10000000, perAccidentLimitMinor: 30000000, deductibleMinor: null },
  { type: "PROPERTY_DAMAGE_LIABILITY", perPersonLimitMinor: null, perAccidentLimitMinor: 5000000, deductibleMinor: null },
  { type: "PERSONAL_INJURY_PROTECTION", perPersonLimitMinor: 1000000, perAccidentLimitMinor: null, deductibleMinor: 0 },
  { type: "COLLISION", perPersonLimitMinor: null, perAccidentLimitMinor: null, deductibleMinor: 50000 },
];

export const insuranceApplication: InsuranceApplication = {
  drivers: [{ ageYears: 41, licenceState: "FL", yearsLicensed: 22, atFaultAccidents: 0, violations: 0 }],
  garaging: { zip: "33647", state: "FL" },
  vehicle: { vin: "1HGCM82633A004352", modelYear: 2023, make: "Honda", model: "Accord", trim: "EX", usage: "COMMUTE", annualMileage: 12000 },
  coverage: chosen,
  currentPolicy: { insurer: "Gulfside Mutual Assurance of Northeast Florida", premiumMinor: 91000, term: "SIX_MONTHS", coverage: "SAME_AS_CHOSEN", lines: null },
};

const quote = (id: string, insurer: string, premiumMinor: number, term: "SIX_MONTHS" | "TWELVE_MONTHS",
  extra: Partial<InsuranceQuote> = {}): InsuranceQuote => {
  const yearly = term === "SIX_MONTHS" ? premiumMinor * 2 : premiumMinor;
  return { id, insurer, source: "PARTNER", premiumMinor, term, yearlyPremiumMinor: yearly, quotedAt: "2026-10-16T12:00:00Z",
    expiresAt: "2026-11-15T12:00:00Z", continueUrl: `https://synthetic-insurance-partner.invalid/continue/${id}`, differences: [],
    yearlyDifference: { quoteYearlyMinor: yearly, currentYearlyMinor: 182000, quoteMinusCurrentMinor: yearly - 182000, currentCoverage: "SAME_AS_CHOSEN" },
    ...extra };
};

export function insuranceView(state: "EMPTY" | "APPLIED" | "QUOTED", overrides: Partial<InsuranceView> = {}): InsuranceView {
  const view: InsuranceView = {
    holder: "CASE",
    caseVehicle: { vin: "1HGCM82633A004352", modelYear: 2023, make: "Honda", model: "Accord", trim: "EX" },
    revision: state === "EMPTY" ? null : 2, changedAt: state === "EMPTY" ? null : "2026-10-16T12:00:00Z",
    application: state === "EMPTY" ? null : insuranceApplication,
    consent: { version: "otdog-insurance-consent.draft-1", partner: "the synthetic test partner (development only)", synthetic: true, text: [
      "When you choose Get quotes, otdog sends this application to the synthetic test partner (development only), a licensed insurance comparison partner, and to the insurers that quote through it. It goes to no one else.",
      "The application holds your drivers' ages, licence states, years licensed, accidents and tickets, the ZIP code where the car is kept, the car, how it is used, the coverage you chose and, if you entered them, your current insurer, coverage and premium. It holds no names, dates of birth, licence numbers or Social Security numbers.",
      "otdog does not sell your application or share it with anyone else. The partner and the insurers may use it only to quote you, and may not contact you about it unless you continue with one of them.",
      "Getting quotes does not buy a policy. To take one, you continue on that insurer's own site, which may ask for more details, such as names and dates of birth, before giving a final price. otdog never buys, accepts or binds a policy.",
    ] },
    coverageGuide: insuranceGuide,
    results: state !== "QUOTED" ? null : {
      request: { id: "request-1", requestedAt: "2026-10-16T12:00:00Z", retrievedAt: "2026-10-16T12:00:00Z", partner: "SYNTHETIC_INSURANCE_PARTNER",
        synthetic: true, availability: "AVAILABLE", notice: "Synthetic insurance quotes for development only.", outdated: false },
      sameCoverage: [
        quote("own", "Panhandle Farmers Insurance Exchange of the Gulf Coast", 178000, "TWELVE_MONTHS", { source: "BUYER_ENTERED", continueUrl: null, expiresAt: null }),
        quote("alpha", "Synthetic Insurer Alpha", 398000, "TWELVE_MONTHS"),
        quote("beta", "Synthetic Insurer Beta", 206900, "SIX_MONTHS"),
      ],
      differentCoverage: [quote("gamma", "Synthetic Insurer Gamma", 350200, "TWELVE_MONTHS", { yearlyDifference: null, differences: [
        { type: "COLLISION", aspect: "DEDUCTIBLE", chosenMinor: 50000, pricedMinor: 100000 },
        { type: "BODILY_INJURY_LIABILITY", aspect: "NOT_IN_QUOTE", chosenMinor: null, pricedMinor: null }] })],
      expired: [quote("old", "Old Renewal Notice", 150000, "TWELVE_MONTHS", { source: "BUYER_ENTERED", continueUrl: null,
        expiresAt: "2026-10-15T04:00:00Z", yearlyDifference: null })],
      withoutQuote: [{ insurer: "Synthetic Insurer Delta", outcome: "DECLINED" }, { insurer: "Synthetic Insurer Epsilon", outcome: "TIMED_OUT" }],
      currentPremiumEntered: true,
    },
  };
  return { ...view, ...overrides };
}
