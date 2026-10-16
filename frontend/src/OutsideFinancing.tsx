import { useState } from "react";
import { DealerFinancingForm, type FinancingResult } from "./DealerFinancing";

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
const money = (value: number | null | undefined) => value == null ? "Unavailable" : new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(value / 100);

export function OutsideFinancing({ caseId, token }: { caseId: string; token: string }) {
  const [saved, setSaved] = useState<Comparison>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function load() {
    setBusy(true); setError(""); setSaved(undefined);
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/outside-finance-offers/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("Saved outside comparison unavailable. Check case access or save an outside offer first.");
      setSaved(await response.json());
    } catch (error) { setError(String(error)); } finally { setBusy(false); }
  }
  return <section className="stack" aria-label="Outside financing">
    <DealerFinancingForm caseId={caseId} token={token} outside onSaved={value => { setSaved(value as Comparison); setError(""); }} />
    <div className="panel">
      <button className="btn-secondary" onClick={load} disabled={busy}>Open saved outside comparison</button>
      {error && <p role="alert">{error}</p>}
    </div>
    {saved && <OutsideFinancingResult comparison={saved} />}
  </section>;
}

export function OutsideFinancingResult({ comparison }: { comparison: Comparison }) {
  const { result } = comparison;
  const comparable = result.status === "COMPARABLE";
  return <section className="panel" aria-label="Saved financing comparison"><h3>Outside financing comparison — version {comparison.version}</h3>
    <p>Buyer-selected financing, not a dealer discount. Saved estimate as of {comparison.createdAt}, using dealer financing {comparison.dealerFinancing.id}. This historical snapshot does not reflect later offer or vehicle changes. Save a new comparison against current verified financing to update it.</p>
    {comparison.preApproval && <p>Outside financing: your chosen pre-approval from {comparison.preApproval.lender}.</p>}
    <p>Comparison: {result.status}</p>
    {[...result.issues, ...result.purchaseDifferences].map((issue, index) => <p className="notice" key={index}>{issue}</p>)}
    <table><caption>Full-term economics under each saved scenario’s purchase assumptions</caption>
      <thead><tr><th scope="col">Amount or term</th><th scope="col">Dealer financing</th><th scope="col">Outside financing</th></tr></thead>
      <tbody>
        <tr><th scope="row">APR (%)</th><td>{comparison.dealerFinancing.inputs.aprPercent ?? "Unavailable"}</td><td>{comparison.inputs.aprPercent ?? "Unavailable"}</td></tr>
        <tr><th scope="row">Term (months)</th><td>{comparison.dealerFinancing.inputs.termMonths ?? "Unavailable"}</td><td>{comparison.inputs.termMonths ?? "Unavailable"}</td></tr>
        {([["purchasePriceMinor", "Purchase price after discounts and rebates"], ["outTheDoorTotalMinor", "Out-the-door total"], ["netAmountDueMinor", "Net amount due"], ["cashPaidAtClosingMinor", "Cash paid including upfront fees"], ["deferredChargesMinor", "Deferred charges"], ["principalMinor", "Principal"], ["financedFeesMinor", "Financed lender fees (included in principal)"], ["upfrontFeesMinor", "Upfront lender fees (included in cash paid)"]] as const).map(([field, label]) =>
          <tr key={field}><th scope="row">{label}</th><td>{money(comparison.dealerFinancing.inputs[field])}</td><td>{money(comparison.inputs[field])}</td></tr>)}
        {([["paymentMinor", "Monthly payment"], ["finalPaymentMinor", "Final payment"], ["totalInterestMinor", "Total interest"], ["totalScheduledPaymentsMinor", "Scheduled payments"], ["totalTransactionCostMinor", "Total Transaction Cost"]] as const).map(([field, label]) =>
          <tr key={field}><th scope="row">{label}</th>{[result.dealer, result.outside].map((scenario, index) => <td key={index}>{scenario.status === "AVAILABLE" ? money(scenario[field]) : "Unavailable"}</td>)}</tr>)}
      </tbody>
    </table>
    {comparable ? <><p>Outside minus dealer Total Transaction Cost: {money(result.outsideMinusDealerCostMinor)}. Negative means the outside scenario costs less over its full term.</p>
      <p>APR-only change in scheduled payments: {money(result.aprOnlyScheduledPaymentsDeltaMinor)}. Uses the outside APR with the dealer principal, term, fees, timing and add-ons held constant. This is a counterfactual estimate, not another loan offer.</p></>
      : <p>No cost delta is available. Resolve missing terms or confirm aligned purchase assumptions before comparing costs.</p>}
    {[result.dealer, result.outside].map((scenario, index) => scenario.status === "AVAILABLE" && <section key={index}><h4>{index === 0 ? "Dealer" : "Outside"} financed add-on effects</h4>
      <p>Each estimate removes one add-on while holding APR, term and other inputs fixed; excludes tax changes and refunds.</p>
      {scenario.financedAddOns.length === 0 && <p>No financed add-ons confirmed.</p>}
      {scenario.financedAddOns.map((addon, i) => <p key={i}>{addon.label}: cash price {money(addon.cashPriceMinor)}; incremental scheduled payments {money(addon.incrementalScheduledPaymentsMinor)}; incremental interest {money(addon.incrementalInterestMinor)}.</p>)}
    </section>)}
    <details><summary>Saved inputs and reproducible calculation</summary><pre>{JSON.stringify(comparison, null, 2)}</pre></details>
  </section>;
}
