import { useState } from "react";

export type FinancingResult = {
  status: string; timing: string; rounding: string; issues: string[];
  quotedPaymentMinor: number | null; paymentMinor: number | null;
  quotedMinusCalculatedMinor: number | null; toleranceMinor: number; withinTolerance: boolean | null;
  possibleCauses: string[];
  financedAddOns: { label: string; cashPriceMinor: number; incrementalPaymentMinor: number;
    incrementalScheduledPaymentsMinor: number; incrementalInterestMinor: number }[];
};
const money = (value: number | null) => value == null ? "Unavailable" : new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(value / 100);
const fields = [
  ["purchasePriceMinor", "Purchase price after discounts and rebates"], ["outTheDoorTotalMinor", "Out-the-door total"],
  ["netAmountDueMinor", "Net amount due after trade, cash down and credits"], ["principalMinor", "Amount financed"],
  ["financedFeesMinor", "Financed lender fees"], ["upfrontFeesMinor", "Upfront lender fees"],
  ["cashPaidAtClosingMinor", "Cash paid at or before closing, including upfront fees"],
  ["deferredChargesMinor", "Known deferred charges"], ["quotedPaymentMinor", "Quoted monthly payment (optional)"],
] as const;

export function DealerFinancingForm({ caseId, token, outside = false, onSaved }: {
  caseId: string; token: string; outside?: boolean; onSaved?: (value: unknown) => void;
}) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [addons, setAddons] = useState<{ label: string; price: string }[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState(() => crypto.randomUUID());
  function update(name: string, value: string) { setValues({ ...values, [name]: value }); setConfirmed(false); setKey(crypto.randomUUID()); }
  function cents(value: string | undefined) { return value == null || value === "" ? null : Math.round(Number(value) * 100); }
  async function save(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setMessage("");
    try {
      const path = `/api/v1/cases/${encodeURIComponent(caseId)}`;
      const headers = { "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key };
      const response = await fetch(`${path}/${outside ? "finance-offers" : "dealer-offers"}/latest`, { headers });
      if (!response.ok) throw new Error(outside ? "Confirm current dealer financing first." : "Establish and verify the current Dealer Offer first.");
      const offer = await response.json();
      const loan = { currency: "USD", ...Object.fromEntries(fields.map(([name]) => [name, cents(values[name])])),
        aprPercent: values.apr ? Number(values.apr) : null, termMonths: values.term ? Number(values.term) : null,
        schedule: values.schedule || null, paymentTiming: values.timing || null,
        financedAddOns: addons.map(addon => ({ label: addon.label, cashPriceMinor: cents(addon.price) })) };
      const saved = await fetch(`${path}/${outside ? "outside-finance-offers" : "finance-offers"}`, { method: "POST", headers,
        body: JSON.stringify({ [outside ? "dealerFinanceOfferId" : "dealerOfferId"]: offer.id, confirmed, loan }) });
      if (!saved.ok) {
        const error = await saved.json().catch(() => ({}));
        throw new Error(error.detail || error.message || "Finance Offer could not be saved. Check case access, purchase method and verified offer.");
      }
      const result = await saved.json();
      onSaved?.(result);
      setMessage(`Finance Offer version ${result.version} saved. Financing: ${result.result.status}. ${outside ? "Comparison saved below." : "Generate a new report to review it."}`);
      setKey(crypto.randomUUID());
    } catch (error) { setMessage(String(error)); } finally { setBusy(false); }
  }
  async function copyPurchase() {
    setBusy(true); setMessage("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/finance-offers/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("Confirm dealer financing first.");
      const { inputs } = await response.json();
      const purchase: Record<string, string> = {};
      for (const name of ["purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor", "deferredChargesMinor"])
        purchase[name] = inputs[name] == null ? "" : String(inputs[name] / 100);
      setValues({ ...values, ...purchase });
      setAddons((inputs.financedAddOns || []).map((item: { label: string; cashPriceMinor: number | null }) => ({ label: item.label, price: item.cashPriceMinor == null ? "" : String(item.cashPriceMinor / 100) })));
      setConfirmed(false); setKey(crypto.randomUUID());
      setMessage("Dealer purchase assumptions copied. Confirm that these prices, rebates and add-ons also apply with outside financing. Enter outside principal, fees and total cash paid separately.");
    } catch (error) { setMessage(String(error)); } finally { setBusy(false); }
  }
  return <details className="panel"><summary>{outside ? "Compare one outside financing offer" : "Verify dealer financing (finance purchases)"}</summary>
    <form onSubmit={save} aria-label={outside ? "Compare outside financing" : "Verify dealer financing"}><fieldset disabled={busy}><legend>Buyer-confirmed loan terms</legend>
      {outside && <><p>Buyer-selected outside financing is not a dealer discount. Purchase price, OTD, net due, cash contribution excluding lender fees, deferred charges and add-ons must match before a cost delta is shown. Resolve financing-dependent rebates with the dealer before confirming matching assumptions.</p>
        <button type="button" onClick={copyPurchase}>Copy dealer purchase assumptions</button></>}
      <p>Enter USD amounts from the current offer. Enter 0 for confirmed zero fees or charges; leave unknown values blank. Add-ons must already be included in the principal. Cash paid includes upfront fees. Known deferred charges exclude scheduled loan payments.</p>
      {fields.map(([name, label]) => <label key={name}>{label} (USD)<input type="number" min="0" step="0.01" value={values[name] || ""} onChange={e => update(name, e.target.value)} /></label>)}
      <label>APR (%)<input type="number" min="0" max="100" step="0.00000001" value={values.apr || ""} onChange={e => update("apr", e.target.value)} /></label>
      <label>Term (months)<input type="number" min="1" max="1200" step="1" value={values.term || ""} onChange={e => update("term", e.target.value)} /></label>
      <label>Schedule<select value={values.schedule || ""} onChange={e => update("schedule", e.target.value)}><option value="">Unknown</option><option value="STANDARD_FIXED_RATE">Standard fixed rate</option><option value="BALLOON">Balloon</option><option value="IRREGULAR">Irregular or variable rate</option></select></label>
      <label>Payment timing<select value={values.timing || ""} onChange={e => update("timing", e.target.value)}><option value="">Unknown</option><option value="MONTHLY_IN_ARREARS_FIRST_PAYMENT_ONE_MONTH">Monthly; first payment one month after funding</option><option value="OTHER">Other timing</option></select></label>
      <h4>Financed add-ons</h4>
      {addons.map((addon, index) => <div key={index}><label>Add-on {index + 1} name<input required value={addon.label} onChange={e => { setAddons(addons.map((item, i) => i === index ? { ...item, label: e.target.value } : item)); setConfirmed(false); setKey(crypto.randomUUID()); }} /></label>
        <label>Add-on {index + 1} cash price (USD)<input required type="number" min="0" step="0.01" value={addon.price} onChange={e => { setAddons(addons.map((item, i) => i === index ? { ...item, price: e.target.value } : item)); setConfirmed(false); setKey(crypto.randomUUID()); }} /></label>
        <button type="button" onClick={() => { setAddons(addons.filter((_, i) => i !== index)); setConfirmed(false); setKey(crypto.randomUUID()); }}>Remove add-on {index + 1}</button></div>)}
      <button type="button" onClick={() => { setAddons([...addons, { label: "", price: "" }]); setConfirmed(false); setKey(crypto.randomUUID()); }}>Add financed add-on</button>
      <label><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />{outside ? "I confirm this Buyer-selected outside offer and its purchase assumptions, fees, timing and complete add-on list; blank fields are unknown." : "I verified these values, fees, timing and the complete financed add-on list against the current offer; blank fields are unknown."}</label>
      <button disabled={!confirmed} type="submit">{outside ? "Save and compare outside financing" : "Save verified Finance Offer"}</button>
    </fieldset></form>{message && <p role="status">{message}</p>}
  </details>;
}

export function DealerFinancingResult({ result, availability, terms }: { result: FinancingResult | null; availability: string; terms?: { aprPercent: number | null; termMonths: number | null } | null }) {
  const available = availability === "AVAILABLE";
  return <section className="panel" id="core-financing" tabIndex={-1}><h3>Dealer financing</h3>
    <p>Financing: {availability}. <a href="#evidence-financing">Verified inputs and calculation</a></p>
    {result && <>{result.issues.map(issue => <p key={issue}>{issue}</p>)}
      {available && <>{terms && <p>Verified APR: {terms.aprPercent ?? "Unavailable"}%. Term: {terms.termMonths ?? "Unavailable"} months.</p>}<dl className="money-list">
        <div><dt>Quoted monthly payment</dt><dd>{money(result.quotedPaymentMinor)}</dd></div>
        <div><dt>Calculated monthly payment</dt><dd>{money(result.paymentMinor)}</dd></div>
        <div><dt>Quoted minus calculated</dt><dd>{money(result.quotedMinusCalculatedMinor)}</dd></div>
        <div><dt>Payment tolerance</dt><dd>{money(result.toleranceMinor)}</dd></div>
      </dl><p>{result.withinTolerance == null ? "Quoted payment unavailable for comparison." : result.withinTolerance ? "Quoted payment is within tolerance." : "Review required: quoted payment exceeds the difference tolerance."}</p>
      {result.possibleCauses.map(cause => <p key={cause}>{cause}</p>)}
      <h4>Full-term financed add-on effects</h4><p>Each comparison removes one add-on from principal, holding APR, term and fees constant. Tax changes and cancellation refunds are excluded.</p>
      {result.financedAddOns.length === 0 && <p>No financed add-ons confirmed.</p>}
      {result.financedAddOns.map((addon, index) => <section key={index}><h4>{addon.label}</h4><dl className="money-list">
        <div><dt>Cash price</dt><dd>{money(addon.cashPriceMinor)}</dd></div>
        <div><dt>Incremental monthly payment</dt><dd>{money(addon.incrementalPaymentMinor)}</dd></div>
        <div><dt>Incremental scheduled payments over the full term</dt><dd>{money(addon.incrementalScheduledPaymentsMinor)}</dd></div>
        <div><dt>Incremental interest</dt><dd>{money(addon.incrementalInterestMinor)}</dd></div>
      </dl></section>)}</>}
      <p>Supported timing: monthly, first payment one month after funding. Rounding: {result.rounding}.</p>
    </>}
  </section>;
}
