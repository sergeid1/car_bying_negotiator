import { useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";
import { errorMessage } from "./i18n/errors";

export type FinancingResult = {
  status: string; timing: string; rounding: string; issues: string[];
  quotedPaymentMinor: number | null; paymentMinor: number | null;
  quotedMinusCalculatedMinor: number | null; toleranceMinor: number; withinTolerance: boolean | null;
  possibleCauses: string[];
  financedAddOns: { label: string; cashPriceMinor: number; incrementalPaymentMinor: number;
    incrementalScheduledPaymentsMinor: number; incrementalInterestMinor: number }[];
};
const fields = ["purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor", "principalMinor", "financedFeesMinor",
  "upfrontFeesMinor", "cashPaidAtClosingMinor", "deferredChargesMinor", "quotedPaymentMinor"] as const;

export function DealerFinancingForm({ caseId, token, outside = false, onSaved }: {
  caseId: string; token: string; outside?: boolean; onSaved?: (value: unknown) => void;
}) {
  const { m: { money: { dealerFinancing: t } }, fill } = useMessages();
  const [values, setValues] = useState<Record<string, string>>({});
  const [addons, setAddons] = useState<{ label: string; price: string }[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState(() => crypto.randomUUID());
  // The Buyer's chosen loan pre-approval (ticket 129): its amount, APR, term and fees fill the outside offer and stay fixed.
  const [preApproval, setPreApproval] = useState<{ id: string; lender: string }>();
  const fixed = (name: string) => Boolean(preApproval) && ["principalMinor", "upfrontFeesMinor", "apr", "term", "schedule", "timing"].includes(name);
  function update(name: string, value: string) { setValues({ ...values, [name]: value }); setConfirmed(false); setKey(crypto.randomUUID()); }
  function cents(value: string | undefined) { return value == null || value === "" ? null : Math.round(Number(value) * 100); }
  async function save(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setMessage("");
    try {
      const path = `/api/v1/cases/${encodeURIComponent(caseId)}`;
      const headers = { "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key };
      const response = await fetch(`${path}/${outside ? "finance-offers" : "dealer-offers"}/latest`, { headers });
      if (!response.ok) throw new Error(outside ? t.confirmCurrentFinancingFirst : t.verifyOfferFirst);
      const offer = await response.json();
      const loan = { currency: "USD", ...Object.fromEntries(fields.map(name => [name, cents(values[name])])),
        aprPercent: values.apr ? Number(values.apr) : null, termMonths: values.term ? Number(values.term) : null,
        schedule: values.schedule || null, paymentTiming: values.timing || null,
        financedAddOns: addons.map(addon => ({ label: addon.label, cashPriceMinor: cents(addon.price) })) };
      const saved = await fetch(`${path}/${outside ? "outside-finance-offers" : "finance-offers"}`, { method: "POST", headers,
        body: JSON.stringify({ [outside ? "dealerFinanceOfferId" : "dealerOfferId"]: offer.id, confirmed, loan,
          ...(outside && preApproval ? { preApprovalId: preApproval.id } : {}) }) });
      if (!saved.ok) {
        const error = await saved.json().catch(() => ({}));
        throw new Error(error.detail || error.message || t.saveFailed);
      }
      const result = await saved.json();
      onSaved?.(result);
      setMessage(fill(outside ? t.savedOutside : t.savedDealer, { version: String(result.version), status: result.result.status }));
      setKey(crypto.randomUUID());
    } catch (error) { setMessage(errorMessage(error, t.saveFailed)); } finally { setBusy(false); }
  }
  async function copyPurchase() {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/finance-offers/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.confirmFinancingFirst);
      const { inputs } = await response.json();
      const purchase: Record<string, string> = {};
      for (const name of ["purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor", "deferredChargesMinor"])
        purchase[name] = inputs[name] == null ? "" : String(inputs[name] / 100);
      setValues({ ...values, ...purchase });
      setAddons((inputs.financedAddOns || []).map((item: { label: string; cashPriceMinor: number | null }) => ({ label: item.label, price: item.cashPriceMinor == null ? "" : String(item.cashPriceMinor / 100) })));
      setConfirmed(false); setKey(crypto.randomUUID());
      setMessage(t.purchaseCopied);
    } catch (error) { setMessage(errorMessage(error, t.saveFailed)); } finally { setBusy(false); }
  }
  async function fillChosenPreApproval() {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/loan`, { headers: { "Case-Access-Token": token } });
      const chosen = response.ok ? (await response.json().catch(() => null))?.chosen : null;
      if (!chosen?.offerId || chosen.expired) throw new Error(t.choosePreApprovalFirst);
      setValues({ ...values, principalMinor: String(chosen.amountMinor / 100), upfrontFeesMinor: String(chosen.upfrontFeesMinor / 100),
        apr: String(chosen.aprPercent), term: String(chosen.termMonths), schedule: "STANDARD_FIXED_RATE",
        timing: "MONTHLY_IN_ARREARS_FIRST_PAYMENT_ONE_MONTH" });
      setPreApproval({ id: chosen.offerId, lender: chosen.lender });
      setConfirmed(false); setKey(crypto.randomUUID());
      setMessage(fill(t.filledFromPreApproval, { lender: chosen.lender }));
    } catch (error) { setMessage(errorMessage(error, t.saveFailed)); } finally { setBusy(false); }
  }
  return <details className="panel"><summary>{outside ? t.summaryOutside : t.summaryDealer}</summary>
    <form onSubmit={save} aria-label={outside ? t.formOutside : t.formDealer}><fieldset disabled={busy}><legend>{t.legend}</legend>
      {outside && <><p>{t.outsideIntro}</p>
        <button className="btn-secondary" type="button" onClick={copyPurchase}>{t.copyPurchase}</button>
        {preApproval ? <p>{fill(t.usingPreApproval, { lender: preApproval.lender })} <button className="btn-ghost" type="button" onClick={() => {
          setPreApproval(undefined); setConfirmed(false); setKey(crypto.randomUUID()); }}>{t.enterByHand}</button></p>
          : <button className="btn-secondary" type="button" onClick={fillChosenPreApproval}>{t.fillPreApproval}</button>}</>}
      <p>{t.enterAmounts}</p>
      {fields.map(name => <label key={name}>{t.fields[name]}<input type="number" min="0" step="0.01" value={values[name] || ""} readOnly={fixed(name)} onChange={e => update(name, e.target.value)} /></label>)}
      <label>{t.apr}<input type="number" min="0" max="100" step="0.00000001" value={values.apr || ""} readOnly={fixed("apr")} onChange={e => update("apr", e.target.value)} /></label>
      <label>{t.term}<input type="number" min="1" max="1200" step="1" value={values.term || ""} readOnly={fixed("term")} onChange={e => update("term", e.target.value)} /></label>
      <label>{t.schedule}<select value={values.schedule || ""} disabled={fixed("schedule")} onChange={e => update("schedule", e.target.value)}><option value="">{t.unknown}</option>
        {(["STANDARD_FIXED_RATE", "BALLOON", "IRREGULAR"] as const).map(code => <option key={code} value={code}>{t.schedules[code]}</option>)}</select></label>
      <label>{t.paymentTiming}<select value={values.timing || ""} disabled={fixed("timing")} onChange={e => update("timing", e.target.value)}><option value="">{t.unknown}</option>
        {(["MONTHLY_IN_ARREARS_FIRST_PAYMENT_ONE_MONTH", "OTHER"] as const).map(code => <option key={code} value={code}>{t.timings[code]}</option>)}</select></label>
      <h4>{t.financedAddOns}</h4>
      {addons.map((addon, index) => <div key={index}><label>{fill(t.addOnName, { number: index + 1 })}<input required value={addon.label} onChange={e => { setAddons(addons.map((item, i) => i === index ? { ...item, label: e.target.value } : item)); setConfirmed(false); setKey(crypto.randomUUID()); }} /></label>
        <label>{fill(t.addOnPrice, { number: index + 1 })}<input required type="number" min="0" step="0.01" value={addon.price} onChange={e => { setAddons(addons.map((item, i) => i === index ? { ...item, price: e.target.value } : item)); setConfirmed(false); setKey(crypto.randomUUID()); }} /></label>
        <button className="btn-ghost" type="button" onClick={() => { setAddons(addons.filter((_, i) => i !== index)); setConfirmed(false); setKey(crypto.randomUUID()); }}>{fill(t.removeAddOn, { number: index + 1 })}</button></div>)}
      <button className="btn-secondary" type="button" onClick={() => { setAddons([...addons, { label: "", price: "" }]); setConfirmed(false); setKey(crypto.randomUUID()); }}>{t.addAddOn}</button>
      <label><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />{outside ? t.confirmOutside : t.confirmDealer}</label>
      <button disabled={!confirmed} type="submit">{outside ? t.submitOutside : t.submitDealer}</button>
    </fieldset></form>{message && <p role="status">{message}</p>}
  </details>;
}

/** `headless` drops the panel and heading so the report's collapsible list can supply them. */
export function DealerFinancingResult({ result, availability, terms, headless }: { result: FinancingResult | null; availability: string; terms?: { aprPercent: number | null; termMonths: number | null } | null; headless?: boolean }) {
  const { m: { money: { dealerFinancing: t } }, fill } = useMessages();
  const money = (value: number | null) => value == null ? t.unavailable : format.money(value);
  const available = availability === "AVAILABLE";
  const body = <>
    <p>{fill(t.financingStatus, { status: availability })} <a href="#evidence-financing">{t.evidenceLink}</a></p>
    {result && <>{result.issues.map(issue => <p className="notice" key={issue}>{issue}</p>)}
      {available && <>{terms && <p>{fill(t.verifiedTerms, { apr: String(terms.aprPercent ?? t.unavailable), months: String(terms.termMonths ?? t.unavailable) })}</p>}<dl className="money-list">
        <div><dt>{t.quotedPayment}</dt><dd>{money(result.quotedPaymentMinor)}</dd></div>
        <div><dt>{t.calculatedPayment}</dt><dd>{money(result.paymentMinor)}</dd></div>
        <div><dt>{t.quotedMinusCalculated}</dt><dd>{money(result.quotedMinusCalculatedMinor)}</dd></div>
        <div><dt>{t.tolerance}</dt><dd>{money(result.toleranceMinor)}</dd></div>
      </dl><p>{result.withinTolerance == null ? t.toleranceUnavailable : result.withinTolerance ? t.withinTolerance : t.outsideTolerance}</p>
      {result.possibleCauses.map(cause => <p key={cause}>{cause}</p>)}
      <h4>{t.addOnEffects}</h4><p>{t.addOnEffectsIntro}</p>
      {result.financedAddOns.length === 0 && <p>{t.noAddOns}</p>}
      {result.financedAddOns.map((addon, index) => <section key={index}><h4>{addon.label}</h4><dl className="money-list">
        <div><dt>{t.cashPrice}</dt><dd>{money(addon.cashPriceMinor)}</dd></div>
        <div><dt>{t.incrementalPayment}</dt><dd>{money(addon.incrementalPaymentMinor)}</dd></div>
        <div><dt>{t.incrementalScheduled}</dt><dd>{money(addon.incrementalScheduledPaymentsMinor)}</dd></div>
        <div><dt>{t.incrementalInterest}</dt><dd>{money(addon.incrementalInterestMinor)}</dd></div>
      </dl></section>)}</>}
      <p>{fill(t.supportedTiming, { rounding: result.rounding })}</p>
    </>}
  </>;
  return headless ? body : <section className="panel" id="core-financing" tabIndex={-1}><h3>{t.heading}</h3>{body}</section>;
}
