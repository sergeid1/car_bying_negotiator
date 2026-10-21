import { FormEvent, useEffect, useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Disclosure = "PAYING_CASH" | "HAS_OUTSIDE_FINANCING" | "FIRST_NAME" | "PURCHASE_TIMELINE";
type Status = "ACTIVE" | "SUPERSEDED" | "REVOKED" | "PLAN_CHANGED" | "EXPIRED";
export type MandateProposal = {
  available: boolean; unavailableReason: string | null; planVersion: number | null; currency: string | null;
  targetMinor: number | null; ceilingMinor: number | null; disclosures: Disclosure[];
};
export type Mandate = {
  version: number; status: Status; currency: string; targetMinor: number; ceilingMinor: number;
  planCeilingMinor: number; disclosures: Disclosure[]; expiresAt: string;
  approvedAt: string; revokedAt: string | null;
};

const LIFETIMES = [3, 7, 14, 30];

export function NegotiationMandate({ caseId, token }: { caseId: string; token: string }) {
  const { m: { messages: { negotiationMandate: t } }, fill } = useMessages();
  const [proposal, setProposal] = useState<MandateProposal>();
  const [mandates, setMandates] = useState<Mandate[]>([]);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [approvalKey, setApprovalKey] = useState(() => crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/mandates`;

  async function request<T>(url: string, init?: RequestInit): Promise<T | undefined> {
    const response = await fetch(url, { ...init, headers: { "Case-Access-Token": token, ...init?.headers } });
    const text = await response.text();
    const body = text ? JSON.parse(text) : undefined;
    if (!response.ok) throw new Error(body?.detail ?? t.requestFailed);
    return body as T | undefined;
  }

  async function load() {
    setBusy(true); setError(undefined);
    try {
      setProposal(await request<MandateProposal>(`${path}/proposal`));
      const list = await request<Mandate[]>(path);
      setMandates(Array.isArray(list) ? list : []);
    } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); }
    finally { setBusy(false); }
  }
  useEffect(() => { void load(); }, [caseId, token]);

  async function approve(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(undefined);
    const form = event.currentTarget;
    const values = new FormData(form);
    const minor = (name: string) => Math.round(Number(values.get(name)) * 100);
    const days = Number(values.get("lifetime"));
    try {
      await request<Mandate>(path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": approvalKey },
        body: JSON.stringify({
          planVersion: proposal?.planVersion, targetMinor: minor("target"), ceilingMinor: minor("ceiling"),
          disclosures: values.getAll("disclosure"),
          expiresAt: new Date(Date.now() + days * 86_400_000).toISOString(), approved: values.get("approved") === "on",
        }),
      });
      setApprovalKey(crypto.randomUUID());
      form.reset();
      await load();
    } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); setBusy(false); }
  }

  async function revoke(version: number) {
    setBusy(true); setError(undefined);
    try {
      await request<Mandate>(`${path}/${version}/revocation`, { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } });
      await load();
    } catch (caught) { setError(caught instanceof Error ? caught.message : String(caught)); setBusy(false); }
  }

  const money = (minor: number, currency?: string | null) =>
    format.money(minor, { currency: currency ?? "USD" });
  const when = (iso: string) => format.dateTime(iso, { dateStyle: "medium", timeStyle: "short" });
  const current = mandates.find(mandate => mandate.status === "ACTIVE");
  const planChanged = mandates[0]?.status === "PLAN_CHANGED";

  return <section className="panel mandate-panel" aria-labelledby="mandate-heading">
    <h2 id="mandate-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    <div className="mandate-terms">
      <section aria-labelledby="mandate-may">
        <h3 id="mandate-may">{t.mayHeading}</h3>
        <ul>{t.may.map(item => <li key={item}>{item}</li>)}</ul>
      </section>
      <section aria-labelledby="mandate-may-not">
        <h3 id="mandate-may-not">{t.mayNotHeading}</h3>
        <ul>{t.mayNot.map(item => <li key={item}>{item}</li>)}</ul>
      </section>
    </div>
    {error && <p role="alert">{error}</p>}
    {proposal && !proposal.available && <p role="status">{proposal.unavailableReason}</p>}
    {planChanged && <p className="notice">{fill(t.planChanged, { version: String(mandates[0].version) })}</p>}
    {current && <section aria-labelledby="mandate-current">
      <h3 id="mandate-current">{fill(t.current, { version: String(current.version) })}</h3>
      <dl className="facts">
        <div><dt>{t.target}</dt><dd>{money(current.targetMinor, current.currency)}</dd></div>
        <div><dt>{t.walkAwayCeiling}</dt><dd>{money(current.ceilingMinor, current.currency)}</dd></div>
        <div><dt>{t.mayBeShared}</dt><dd>{current.disclosures.length ? current.disclosures.map(item => t.disclosures[item]).join("; ") : t.nothing}</dd></div>
        <div><dt>{t.mode}</dt><dd>{t.supervised}</dd></div>
        <div><dt>{t.expires}</dt><dd>{when(current.expiresAt)}</dd></div>
        <div><dt>{t.approved}</dt><dd>{when(current.approvedAt)}</dd></div>
      </dl>
      <button type="button" onClick={() => revoke(current.version)} disabled={busy}>{t.revoke}</button>
    </section>}
    {proposal?.available && proposal.targetMinor != null && proposal.ceilingMinor != null &&
      <form onSubmit={approve} aria-labelledby="mandate-form-heading">
        <h3 id="mandate-form-heading">{current ? t.approveNewVersion : t.reviewAndApprove}</h3>
        <p>{fill(t.proposedFrom, { plan: String(proposal.planVersion ?? ""), ceiling: money(proposal.ceilingMinor, proposal.currency) })}
          {current ? <>{" "}{t.replacesCurrent}</> : ""}</p>
        <label>{t.targetInput}<input name="target" type="number" required min="0.01" step="0.01"
          max={proposal.targetMinor / 100} defaultValue={proposal.targetMinor / 100} /></label>
        <label>{t.ceilingInput}<input name="ceiling" type="number" required min="0.01" step="0.01"
          max={proposal.ceilingMinor / 100} defaultValue={proposal.ceilingMinor / 100} /></label>
        <fieldset>
          <legend>{t.shareLegend}</legend>
          {proposal.disclosures.map(item =>
            <label key={item}><input type="checkbox" name="disclosure" value={item} />{t.disclosures[item]}</label>)}
        </fieldset>
        <p>{t.youSend}</p>
        <label>{t.lasts}<select name="lifetime" defaultValue="14">
          {LIFETIMES.map(days => <option key={days} value={days}>{fill(t.days, { count: days })}</option>)}
        </select></label>
        <label><input type="checkbox" name="approved" required />{t.approval}</label>
        <button disabled={busy}>{t.approve}</button>
      </form>}
    {mandates.length > 0 && <section aria-labelledby="mandate-history">
      <h3 id="mandate-history">{t.history}</h3>
      <ul className="mandate-history">
        {mandates.map(mandate => <li key={mandate.version}>
          {fill(mandate.revokedAt ? t.historyEntryRevoked : t.historyEntry, {
            version: String(mandate.version), status: t.statuses[mandate.status],
            target: money(mandate.targetMinor, mandate.currency), ceiling: money(mandate.ceilingMinor, mandate.currency),
            approved: when(mandate.approvedAt), revoked: mandate.revokedAt ? when(mandate.revokedAt) : "" })}
        </li>)}
      </ul>
    </section>}
    <button type="button" onClick={load} disabled={busy}>{t.checkAgain}</button>
  </section>;
}
