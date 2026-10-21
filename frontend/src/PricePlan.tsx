import { FormEvent, useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Boundary = { openingMinor: number; targetMinor: number; policyMaximumMinor: number; walkAwayMinor: number };
type Snapshot = { version: number; payload: { plan: {
  currency: string; sellingPrice: Boundary; outTheDoor: Boundary | null;
  warnings: string[]; method: string;
  savings: { amountMinor: number; baseline: string; uncertainty: string };
} } };

export function PricePlan({ caseId, token }: { caseId: string; token: string }) {
  const { m: { money: { pricePlan: t } }, fill } = useMessages();
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
    if (!response.ok) throw new Error((await response.json()).detail ?? t.requestFailed);
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
    <h2 id="plan-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    <form onSubmit={propose}>
      <label>{t.maxSelling}<input name="selling" type="number" min="0.01" step="0.01" /></label>
      <label>{t.maxOutTheDoor}<input name="otd" type="number" min="0.01" step="0.01" /></label>
      <p>{t.blankUsesCase}</p>
      <button disabled={busy}>{t.review}</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {plan && <>
      <p>{fill(t.methodEstimated, { method: plan.payload.plan.method })}</p>
      {([["selling", t.sellingPrice, plan.payload.plan.sellingPrice], ["otd", t.outTheDoorPrice, plan.payload.plan.outTheDoor]] as const).map(([key, label, boundary]) =>
        <section key={key} aria-label={label}><h3>{label}</h3>{boundary ? <dl className="facts">
          <div><dt>{t.opening}</dt><dd>{money(boundary.openingMinor)}</dd></div>
          <div><dt>{t.target}</dt><dd>{money(boundary.targetMinor)}</dd></div>
          <div><dt>{t.policyMaximum}</dt><dd>{money(boundary.policyMaximumMinor)}</dd></div>
          <div><dt>{t.walkAway}</dt><dd>{money(boundary.walkAwayMinor)}</dd></div>
        </dl> : <p>{t.noConversion}</p>}</section>)}
      {plan.payload.plan.warnings.map(warning => <p className="notice" key={warning}>{fill(t.warning, { warning })}</p>)}
      <p>{fill(t.estimate, { amount: money(plan.payload.plan.savings.amountMinor), baseline: plan.payload.plan.savings.baseline,
        uncertainty: plan.payload.plan.savings.uncertainty })}</p>
      <label><input type="checkbox" checked={approved} onChange={event => setApproved(event.target.checked)} />{t.approveLabel}</label>
      <button onClick={approve} disabled={busy || !approved || Boolean(baseline)}>{t.approve}</button>
      {baseline && <p role="status">{fill(t.approved, { version: String(baseline.version) })}</p>}
    </>}
  </section>;
}
