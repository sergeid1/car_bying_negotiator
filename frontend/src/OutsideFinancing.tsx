import { useState } from "react";
import { DealerFinancingForm, type FinancingResult } from "./DealerFinancing";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Economics = FinancingResult & { finalPaymentMinor: number | null; totalScheduledPaymentsMinor: number | null;
  totalInterestMinor: number | null; totalTransactionCostMinor: number | null };
type Inputs = { principalMinor: number | null; aprPercent: number | null; termMonths: number | null;
  financedFeesMinor: number | null; upfrontFeesMinor: number | null;
  purchasePriceMinor: number | null; outTheDoorTotalMinor: number | null; netAmountDueMinor: number | null;
  cashPaidAtClosingMinor: number | null; deferredChargesMinor: number | null };
export type Comparison = { version: number; createdAt: string; inputs: Inputs;
  preApproval?: { offerId: string; lender: string; offerSource: "PARTNER" | "BUYER_ENTERED" } | null;
  dealerFinancing: { id: string; inputs: Inputs }; result: {
    status: string; policyVersion: string; issues: string[]; purchaseDifferences: string[];
    dealer: Economics; outside: Economics; outsideMinusDealerCostMinor: number | null;
    aprOnlyScheduledPaymentsDeltaMinor: number | null;
  } };

const INPUT_ROWS = ["purchasePriceMinor", "outTheDoorTotalMinor", "netAmountDueMinor", "cashPaidAtClosingMinor", "deferredChargesMinor",
  "principalMinor", "financedFeesMinor", "upfrontFeesMinor"] as const;
const ECONOMICS_ROWS = ["paymentMinor", "finalPaymentMinor", "totalInterestMinor", "totalScheduledPaymentsMinor", "totalTransactionCostMinor"] as const;

export function OutsideFinancing({ caseId, token }: { caseId: string; token: string }) {
  const { m: { money: { outsideFinancing: t } } } = useMessages();
  const [saved, setSaved] = useState<Comparison>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function load() {
    setBusy(true); setError(""); setSaved(undefined);
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/outside-finance-offers/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.savedUnavailable);
      setSaved(await response.json());
    } catch (error) { setError(String(error)); } finally { setBusy(false); }
  }
  return <section className="stack" aria-label={t.label}>
    <DealerFinancingForm caseId={caseId} token={token} outside onSaved={value => { setSaved(value as Comparison); setError(""); }} />
    <div className="panel">
      <button className="btn-secondary" onClick={load} disabled={busy}>{t.openSaved}</button>
      {error && <p role="alert">{error}</p>}
    </div>
    {saved && <OutsideFinancingResult comparison={saved} />}
  </section>;
}

export function OutsideFinancingResult({ comparison }: { comparison: Comparison }) {
  const { m: { money: { outsideFinancing: t } }, fill } = useMessages();
  const money = (value: number | null | undefined) => value == null ? t.unavailable : format.money(value);
  const { result } = comparison;
  const comparable = result.status === "COMPARABLE";
  return <section className="panel" aria-label={t.resultLabel}><h3>{fill(t.heading, { version: String(comparison.version) })}</h3>
    <p>{fill(t.snapshot, { createdAt: comparison.createdAt, id: comparison.dealerFinancing.id })}</p>
    {comparison.preApproval && <p>{fill(t.chosenPreApproval, { lender: comparison.preApproval.lender })}</p>}
    <p>{fill(t.comparison, { status: result.status })}</p>
    {[...result.issues, ...result.purchaseDifferences].map((issue, index) => <p className="notice" key={index}>{issue}</p>)}
    <table><caption>{t.caption}</caption>
      <thead><tr><th scope="col">{t.amountOrTerm}</th><th scope="col">{t.dealerFinancing}</th><th scope="col">{t.outsideFinancing}</th></tr></thead>
      <tbody>
        <tr><th scope="row">{t.apr}</th><td>{comparison.dealerFinancing.inputs.aprPercent ?? t.unavailable}</td><td>{comparison.inputs.aprPercent ?? t.unavailable}</td></tr>
        <tr><th scope="row">{t.term}</th><td>{comparison.dealerFinancing.inputs.termMonths ?? t.unavailable}</td><td>{comparison.inputs.termMonths ?? t.unavailable}</td></tr>
        {INPUT_ROWS.map(field =>
          <tr key={field}><th scope="row">{t.inputs[field]}</th><td>{money(comparison.dealerFinancing.inputs[field])}</td><td>{money(comparison.inputs[field])}</td></tr>)}
        {ECONOMICS_ROWS.map(field =>
          <tr key={field}><th scope="row">{t.economics[field]}</th>{[result.dealer, result.outside].map((scenario, index) => <td key={index}>{scenario.status === "AVAILABLE" ? money(scenario[field]) : t.unavailable}</td>)}</tr>)}
      </tbody>
    </table>
    {comparable ? <><p>{fill(t.costDelta, { amount: money(result.outsideMinusDealerCostMinor) })}</p>
      <p>{fill(t.aprOnlyDelta, { amount: money(result.aprOnlyScheduledPaymentsDeltaMinor) })}</p></>
      : <p>{t.noDelta}</p>}
    {[result.dealer, result.outside].map((scenario, index) => scenario.status === "AVAILABLE" && <section key={index}><h4>{index === 0 ? t.dealerAddOnEffects : t.outsideAddOnEffects}</h4>
      <p>{t.addOnEffectsIntro}</p>
      {scenario.financedAddOns.length === 0 && <p>{t.noAddOns}</p>}
      {scenario.financedAddOns.map((addon, i) => <p key={i}>{fill(t.addOnLine, { addOn: addon.label, cash: money(addon.cashPriceMinor),
        payments: money(addon.incrementalScheduledPaymentsMinor), interest: money(addon.incrementalInterestMinor) })}</p>)}
    </section>)}
    <details><summary>{t.savedInputs}</summary><pre>{JSON.stringify(comparison, null, 2)}</pre></details>
  </section>;
}
