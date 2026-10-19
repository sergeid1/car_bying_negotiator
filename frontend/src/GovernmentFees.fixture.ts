import type { GovernmentFeeCheck } from "./GovernmentFees";

/** A synthetic North Carolina check of a padded "Tag, title and registration" line. */
export const paddedFeeCheck: GovernmentFeeCheck = {
  policyVersion: "government-fee-check.v1", scheduleVersion: "government-fees.2026-10.v1", state: "NC",
  stateName: "North Carolina", status: "CHECKED", checkedOn: "2026-10-18", reviewOn: "2027-01-01", stateFeesMinor: 89500,
  highestMinor: 62725,
  findings: [{
    code: "ABOVE_SCHEDULE", severity: "MATERIAL", label: "Tag, title and registration", amountMinor: 89500, lowestMinor: 11300,
    highestMinor: 60175, verified: false, dependsOn: ["whether the one-day title service is used"], notCounted: [],
    summary: "“Tag, title and registration” on this paperwork is $895. North Carolina's listed title, registration and plate fees add up to at most $601.75. Which apply depends on whether the one-day title service is used. A dealer can also pass through a fee this schedule does not list.",
    deskQuestion: "Please itemize the state fees on this line.",
  }, {
    code: "TAX_COVERED", severity: "INFORMATIONAL", label: "Highway use tax", amountMinor: 75000, lowestMinor: null,
    highestMinor: null, verified: false, dependsOn: [], notCounted: [],
    summary: "“Highway use tax” is a tax the tax check covers, so it is not counted again with the state fees.", deskQuestion: null,
  }],
  summary: "“Tag, title and registration” on this paperwork is $895. North Carolina's listed title, registration and plate fees add up to at most $601.75. Which apply depends on whether the one-day title service is used. A dealer can also pass through a fee this schedule does not list.",
  scope: "A North Carolina resident buying a private passenger vehicle from a North Carolina dealer.",
  note: "G.S. 20-85 prints $25.00 for a plate transfer and a lien; NCDOT's 2024 fee adjustment lists $25.50.",
  fees: [
    { name: "Certificate of title", purpose: "Puts the car's ownership in your name in the state's records.", minMinor: 6675, maxMinor: 6675, dependsOn: "nothing", citation: "G.S. 20-85(a)(1)" },
    { name: "County vehicle property tax", purpose: "Your county's and city's property tax on the car, collected with registration.", minMinor: 0, maxMinor: null, dependsOn: "the car's value", citation: "G.S. 105-330.5" },
  ],
};

export const uncheckedFees: GovernmentFeeCheck = {
  ...paddedFeeCheck, state: null, stateName: null, status: "NOT_CHECKED", checkedOn: null, reviewOn: null,
  highestMinor: null, findings: [], scope: null, note: null, fees: [],
  summary: "otdog has no fee schedule for your state, so the $895 in state fees on this paperwork was not checked.",
};
