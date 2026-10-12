import { useEffect, useState } from "react";
export const STATE_NAMES: Record<string, string> = {
  "US-AL": "Alabama", "US-AK": "Alaska", "US-AZ": "Arizona", "US-AR": "Arkansas",
  "US-CA": "California", "US-CO": "Colorado", "US-CT": "Connecticut", "US-DE": "Delaware",
  "US-DC": "District of Columbia", "US-FL": "Florida", "US-GA": "Georgia", "US-HI": "Hawaii",
  "US-ID": "Idaho", "US-IL": "Illinois", "US-IN": "Indiana", "US-IA": "Iowa", "US-KS": "Kansas",
  "US-KY": "Kentucky", "US-LA": "Louisiana", "US-ME": "Maine", "US-MD": "Maryland",
  "US-MA": "Massachusetts", "US-MI": "Michigan", "US-MN": "Minnesota", "US-MS": "Mississippi",
  "US-MO": "Missouri", "US-MT": "Montana", "US-NE": "Nebraska", "US-NV": "Nevada",
  "US-NH": "New Hampshire", "US-NJ": "New Jersey", "US-NM": "New Mexico", "US-NY": "New York",
  "US-NC": "North Carolina", "US-ND": "North Dakota", "US-OH": "Ohio", "US-OK": "Oklahoma",
  "US-OR": "Oregon", "US-PA": "Pennsylvania", "US-RI": "Rhode Island", "US-SC": "South Carolina",
  "US-SD": "South Dakota", "US-TN": "Tennessee", "US-TX": "Texas", "US-UT": "Utah",
  "US-VT": "Vermont", "US-VA": "Virginia", "US-WA": "Washington", "US-WV": "West Virginia",
  "US-WI": "Wisconsin", "US-WY": "Wyoming", "US-AS": "American Samoa", "US-GU": "Guam",
  "US-MP": "Northern Mariana Islands", "US-PR": "Puerto Rico", "US-VI": "U.S. Virgin Islands",
};

export function DealerFields({ buyerPostalCode, buyerState, initialLocation }: {
  buyerPostalCode: string; buyerState?: string; initialLocation?: string;
}) {
  const parts = initialLocation?.match(/^(.*), ([A-Z]{2})$/);
  const [state, setState] = useState(parts?.[2] ?? "");
  const [chosen, setChosen] = useState(Boolean(parts));
  useEffect(() => {
    if (chosen) return;
    if (buyerState) { setState(buyerState.replace("US-", "")); return; }
    if (!/^\d{5}$/.test(buyerPostalCode)) { setState(""); return; }
    const controller = new AbortController();
    fetch(`/api/v1/intake-options?buyerPostalCode=${encodeURIComponent(buyerPostalCode)}`, { signal: controller.signal })
      .then(response => response.ok ? response.json() : undefined)
      .then(value => { if (!controller.signal.aborted) setState(value?.buyerState?.replace("US-", "") ?? ""); })
      .catch(() => undefined);
    return () => controller.abort();
  }, [buyerPostalCode, buyerState, chosen]);
  return <fieldset className="dealer-location-fields">
    <legend>Dealer</legend>
    <label>Dealer name (optional)<input name="dealerName" maxLength={200} /></label>
    <label>Dealer city<input name="dealerCity" required maxLength={100} defaultValue={parts?.[1] ?? initialLocation} /></label>
    <label>Dealer state<select name="dealerState" required value={state} onChange={event => {
      setChosen(true); setState(event.target.value);
    }}>
      <option value="">Select a state</option>
      {Object.entries(STATE_NAMES).map(([code, name]) => <option key={code} value={code.substring(3)}>{name}</option>)}
    </select></label>
  </fieldset>;
}
