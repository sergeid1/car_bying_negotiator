import type { DealerAddOnCheck, DealerAddOnLine } from "./DealerAddOns";

const base = { chargeIds: [] as string[], name: null, family: null, kind: null, what: null, kindWording: null, note: null, optional: false,
  confirmedByBuyer: false, verified: true };

const etch: DealerAddOnLine = { ...base, label: "VIN ETCH", amountMinor: 29900, status: "RECOGNISED",
  chargeIds: ["vin-etch"], name: "VIN etching", family: "Theft deterrent", kind: "OPTIONAL_ADD_ON",
  what: "The vehicle identification number etched into the windows to discourage theft.",
  kindWording: "Usually optional and negotiable.", optional: true,
  question: "Are the windows already etched? If the etching is optional, will you take it off the price?" };
const warranty: DealerAddOnLine = { ...base, label: "Ext. Warranty", amountMinor: 249500, status: "RECOGNISED",
  chargeIds: ["service-contract"], name: "service contract or extended warranty", family: "Products sold in the finance office",
  kind: "FINANCE_PRODUCT", what: "A contract that pays for some repairs after the factory warranty, sold for a set price and term.",
  kindWording: "Optional product; you can decline it or buy it elsewhere.", optional: true,
  note: "Financed with the car, it also adds interest for the whole loan term, on top of its price.",
  question: "Is the service contract optional? What is its price on its own, and can I decline it?" };
const marketAdjustment: DealerAddOnLine = { ...base, label: "Mkt Adj", amountMinor: 300000, status: "RECOGNISED",
  chargeIds: ["market-adjustment"], name: "market adjustment", family: "Price add-ons", kind: "PRICE_INCREASE",
  what: "An amount the dealer adds on top of the car's price.",
  kindWording: "A price increase on the car, not a service. It is negotiable.",
  question: "The market adjustment raises the car's price. Will you remove it or lower it?" };
const possible: DealerAddOnLine = { ...base, label: "Exterior Shine", amountMinor: 49900, status: "POSSIBLE",
  chargeIds: ["paint-protection"], name: "paint protection", family: "Pre-installed protection", kind: "OPTIONAL_ADD_ON",
  note: "Possibly paint protection.", question: "Is the “Exterior Shine” paint protection? What does it cover, and is it optional?" };
const unrecognised: DealerAddOnLine = { ...base, label: "Gold Package with a long dealer-made name that wraps", amountMinor: 99500,
  status: "UNRECOGNISED", note: "Unrecognised charge — ask what it is.",
  question: "What is the “Gold Package with a long dealer-made name that wraps”, and is it optional?" };

export const namedAddOns: DealerAddOnCheck = {
  policyVersion: "dealer-add-on-check.v1", catalogueVersion: "dealer-add-ons.2026-10.v1", financed: true,
  lines: [etch, warranty, marketAdjustment, possible, unrecognised], optionalTotalMinor: 279400, possibleTotalMinor: 49900,
  unrecognisedTotalMinor: 99500, unreadAmounts: 0,
  summary: "This paperwork lists 3 named add-ons and dealer charges, 1 possible add-on and 1 unrecognised charge. Usually optional items total $2,794. Possible add-ons total $499. Unrecognised charges total $995.",
  listingNote: "The listing you saved showed $30,000 for the car. This paperwork adds $3,798 in add-ons and extra dealer charges beside the vehicle price. Ask whether the advertised price included them.",
};

export const readAddOns: DealerAddOnCheck = { ...namedAddOns, lines: [{ ...etch, verified: false }, { ...unrecognised, amountMinor: null }],
  optionalTotalMinor: 29900, possibleTotalMinor: 0, unrecognisedTotalMinor: 0, unreadAmounts: 1, listingNote: null,
  summary: "This paperwork lists 1 named add-on or dealer charge and 1 unrecognised charge. Usually optional items total $299. 1 line's amount could not be read, so it is in no total." };
