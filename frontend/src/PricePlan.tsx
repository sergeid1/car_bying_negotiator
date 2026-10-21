import { FormEvent, useState } from "react";
import * as format from "./i18n/format";

type Boundary = { openingMinor: number; targetMinor: number; policyMaximumMinor: number; walkAwayMinor: number };
type Snapshot = { version: number; payload: { plan: {
  currency: string; sellingPrice: Boundary; outTheDoor: Boundary | null;
  warnings: string[]; method: string;
  savings: { amountMinor: number; baseline: string; uncertainty: string };
} } };

export function PricePlan({ caseId, token }: { caseId: string; token: string }) {
  const [plan, setPlan] = useState<Snapshot>();
  const [baseline, setBaseline] = useState<Snapshot>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [approved, setApproved] = useState(false);
  const [approvalKey, setApprovalKey] = useState(() => crypto.randomUUID());
  async function post(path: string, body: unknown, key: string): Promise<Snapshot> {
    const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/${path}`, {
      method: "POST", headers: { "Content-Type": "application/json", "Case-Access-Token": token, "Idempotency-Key": key },
      body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error((await response.json()).detail ?? "Price plan request failed");
    return response.json();
  }
  async function propose(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(undefined);
    const values = new FormData(event.currentTarget);
    const limit = (name: string) => values.get(name) ? Math.round(Number(values.get(name)) * 100) : null;
    try {
      setPlan(await post("price-plans", { maxSellingPriceMinor: limit("selling"), maxOutTheDoorPriceMinor: limit("otd") }, crypto.randomUUID()));
      setBaseline(undefined); setApproved(false); setApprovalKey(crypto.randomUUID());
    } catch (caught) { setError(String(caught)); } finally { setBusy(false); }
  }
  async function approve() {
    setBusy(true); setError(undefined);
    try { setBaseline(await post("approved-baselines", { planVersion: plan!.version, approved }, approvalKey)); }
    catch (caught) { setError(String(caught)); } finally { setBusy(false); }
  }
  const money = (minor: number) => format.money(minor, { currency: plan?.payload.plan.currency ?? "USD" });
  return <section className="panel" aria-labelledby="plan-heading">
    <h2 id="plan-heading">Set your price limits</h2>
    <p>Suggested limits come from the dealer offer you checked against the paperwork and the market range for this car. Check the offer first if you have not yet.</p>
    <form onSubmit={propose}>
      <label>Maximum selling price ($)<input name="selling" type="number" min="0.01" step="0.01" /></label>
      <label>Maximum out-the-door price ($)<input name="otd" type="number" min="0.01" step="0.01" /></label>
      <p>Leave blank to use your existing case limit, if any.</p>
      <button disabled={busy}>Review suggested boundaries</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {plan && <>
      <p>{plan.payload.plan.method} All suggested boundaries are estimated.</p>
      {([['Selling price', plan.payload.plan.sellingPrice], ['Out-the-door price', plan.payload.plan.outTheDoor]] as const).map(([label, boundary]) =>
        <section key={label} aria-label={label}><h3>{label}</h3>{boundary ? <dl className="facts">
          <div><dt>Opening</dt><dd>{money(boundary.openingMinor)}</dd></div>
          <div><dt>Target</dt><dd>{money(boundary.targetMinor)}</dd></div>
          <div><dt>Policy maximum</dt><dd>{money(boundary.policyMaximumMinor)}</dd></div>
          <div><dt>Walk-away ceiling</dt><dd>{money(boundary.walkAwayMinor)}</dd></div>
        </dl> : <p>Unavailable: jurisdiction inputs do not support conversion.</p>}</section>)}
      {plan.payload.plan.warnings.map(warning => <p className="notice" key={warning}>Warning: {warning}</p>)}
      <p>Estimated savings: {money(plan.payload.plan.savings.amountMinor)} against {plan.payload.plan.savings.baseline}. {plan.payload.plan.savings.uncertainty}</p>
      <label><input type="checkbox" checked={approved} onChange={event => setApproved(event.target.checked)} />I approve these Deal Terms and price limits as my comparison baseline.</label>
      <button onClick={approve} disabled={busy || !approved || Boolean(baseline)}>Approve immutable baseline</button>
      {baseline && <p role="status">Approved baseline version {baseline.version} saved.</p>}
    </>}
  </section>;
}
