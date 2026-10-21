import type { Finding } from "./i18n/findings";

/** Test support: fills in the coded twins the server sends beside its English, as JourneyQuoteComparisonService does. */
const charges: Record<string, string> = {
  "Selling price": "SELLING_PRICE", "Dealer fees": "DEALER_FEES", "Add-ons": "ADD_ONS", "Taxes": "TAXES",
  "Government charges": "GOVERNMENT_CHARGES", "Out-the-door total": "OUT_THE_DOOR_TOTAL",
};
const states: Record<string, string> = {
  "Verified quote": "STATE_VERIFIED", "Agreed": "STATE_AGREED", "Awaiting quote": "STATE_AWAITING", "Quote unverified": "STATE_UNVERIFIED",
};

const markets: Record<string, string> = {
  "Selling price below the stored market range.": "MARKET_BELOW", "Selling price within the stored market range.": "MARKET_WITHIN",
  "Selling price above the stored market range.": "MARKET_ABOVE", "Market position unavailable in the stored report.": "MARKET_NOT_STORED",
  "Market position unavailable; analyze the current verified offer.": "MARKET_ANALYZE",
};

const named = (code: string, text: string, parameters?: Finding["parameters"]): Finding =>
  ({ code: `COMPARE_${code}`, text, ...(parameters ? { parameters } : {}) });

function amountName(label: string): { category: string; name: Finding } {
  const [category, paperworkLabel] = label.split(/: (.*)/s);
  const code = charges[category];
  if (!code) return { category: "MARKET", name: { code: "UNKNOWN_LABEL", text: label } };
  const name = named(code, category);
  return { category: code, name: paperworkLabel === undefined ? name : named("LINE", label, { category: name, paperworkLabel }) };
}

function reason(text: string): Finding {
  if (charges[text]) return named(charges[text], text);
  const off = /^Figures do not add up to the quoted OTD by ([\d.]+) (\w+)$/.exec(text);
  if (off) return named("OTD_OFF_BY", text, { difference: { minorUnits: Math.round(Number(off[1]) * 100), currency: off[2] } });
  return { code: "UNKNOWN_REASON", text };
}

type EnglishCar = { state: string; missing: string[]; noneListed?: string[]; marketPosition: string;
  amounts: { label: string }[] };

export function coded<C extends EnglishCar>(car: C) {
  return {
    ...car,
    amounts: car.amounts.map(amount => ({ ...amount, ...amountName(amount.label) })),
    stateName: named(states[car.state] ?? "STATE_UNKNOWN", car.state),
    missingNames: car.missing.map(reason),
    noneListedCategories: (car.noneListed ?? []).map(category => charges[category]),
    marketName: named(markets[car.marketPosition] ?? "MARKET_UNKNOWN", car.marketPosition),
  };
}

export function codedView<V extends { cars: EnglishCar[] }>(view: V) {
  return { ...view, cars: view.cars.map(coded) };
}
