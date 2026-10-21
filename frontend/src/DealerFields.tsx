import { useEffect, useState } from "react";
import { useMessages } from "./i18n/LanguageProvider";

export function DealerFields({ buyerPostalCode, buyerState, initialLocation }: {
  buyerPostalCode: string; buyerState?: string; initialLocation?: string;
}) {
  const { m: { account: { dealerFields: t, stateNames } } } = useMessages();
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
    <legend>{t.legend}</legend>
    <label>{t.name}<input name="dealerName" maxLength={200} /></label>
    <label>{t.city}<input name="dealerCity" required maxLength={100} defaultValue={parts?.[1] ?? initialLocation} /></label>
    <label>{t.state}<select name="dealerState" required value={state} onChange={event => {
      setChosen(true); setState(event.target.value);
    }}>
      <option value="">{t.selectState}</option>
      {Object.entries(stateNames).map(([code, name]) => <option key={code} value={code.substring(3)}>{name}</option>)}
    </select></label>
  </fieldset>;
}
