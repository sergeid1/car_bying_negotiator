import { FormEvent, useEffect, useState } from "react";

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

const DISCLOSURE_LABELS: Record<Disclosure, string> = {
  PAYING_CASH: "I am paying cash",
  HAS_OUTSIDE_FINANCING: "I have financing arranged elsewhere",
  FIRST_NAME: "My first name",
  PURCHASE_TIMELINE: "When I plan to buy",
};
const STATUS_LABELS: Record<Status, string> = {
  ACTIVE: "Active", SUPERSEDED: "Replaced by a newer approval", REVOKED: "Revoked",
  PLAN_CHANGED: "No longer applies: price boundaries changed", EXPIRED: "Expired",
};
const LIFETIMES = [3, 7, 14, 30];

export function NegotiationMandate({ caseId, token }: { caseId: string; token: string }) {
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
    if (!response.ok) throw new Error(body?.detail ?? "Mandate request failed");
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
    new Intl.NumberFormat("en-US", { style: "currency", currency: currency ?? "USD" }).format(minor / 100);
  const when = (iso: string) => new Date(iso).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
  const current = mandates.find(mandate => mandate.status === "ACTIVE");
  const planChanged = mandates[0]?.status === "PLAN_CHANGED";

  return <section className="panel mandate-panel" aria-labelledby="mandate-heading">
    <h2 id="mandate-heading">Approve a Negotiation Mandate</h2>
    <p>A mandate sets the limits for any later contact with a dealer about this car. Approving it sends nothing to any dealer.</p>
    <div className="mandate-terms">
      <section aria-labelledby="mandate-may">
        <h3 id="mandate-may">What the platform may do</h3>
        <ul>
          <li>Draft messages asking a dealer for a written, itemized out-the-door quote.</li>
          <li>Propose prices at or below your target, and never above your walk-away ceiling.</li>
          <li>Share only the details you tick below.</li>
          <li>Work under this mandate until it expires or you revoke it, whichever comes first.</li>
        </ul>
      </section>
      <section aria-labelledby="mandate-may-not">
        <h3 id="mandate-may-not">What the platform may not do</h3>
        <ul>
          <li>Send any message without your approval. Every message waits for you.</li>
          <li>Offer more than your walk-away ceiling.</li>
          <li>Share your email address, phone number, surname, or anything you did not tick.</li>
          <li>Agree to buy, sign anything, or pay a deposit. Only you can do those things.</li>
        </ul>
      </section>
    </div>
    {error && <p role="alert">{error}</p>}
    {proposal && !proposal.available && <p role="status">{proposal.unavailableReason}</p>}
    {planChanged && <p className="notice">Your price boundaries changed after mandate version {mandates[0].version} was approved, so it no longer applies. Approve a new mandate to continue.</p>}
    {current && <section aria-labelledby="mandate-current">
      <h3 id="mandate-current">Current mandate, version {current.version}</h3>
      <dl className="facts">
        <div><dt>Target</dt><dd>{money(current.targetMinor, current.currency)}</dd></div>
        <div><dt>Walk-away ceiling</dt><dd>{money(current.ceilingMinor, current.currency)}</dd></div>
        <div><dt>May be shared</dt><dd>{current.disclosures.length ? current.disclosures.map(item => DISCLOSURE_LABELS[item]).join("; ") : "Nothing"}</dd></div>
        <div><dt>Mode</dt><dd>Supervised: you approve every message</dd></div>
        <div><dt>Expires</dt><dd>{when(current.expiresAt)}</dd></div>
        <div><dt>Approved</dt><dd>{when(current.approvedAt)}</dd></div>
      </dl>
      <button type="button" onClick={() => revoke(current.version)} disabled={busy}>Revoke mandate now</button>
    </section>}
    {proposal?.available && proposal.targetMinor != null && proposal.ceilingMinor != null &&
      <form onSubmit={approve} aria-labelledby="mandate-form-heading">
        <h3 id="mandate-form-heading">{current ? "Approve a new version" : "Review and approve"}</h3>
        <p>Proposed from Deal Plan version {proposal.planVersion}. You may lower either amount, but the ceiling cannot go above {money(proposal.ceilingMinor, proposal.currency)}.
          {current ? " Changing anything replaces the current mandate only after you approve the new version." : ""}</p>
        <label>Target selling price ($)<input name="target" type="number" required min="0.01" step="0.01"
          max={proposal.targetMinor / 100} defaultValue={proposal.targetMinor / 100} /></label>
        <label>Walk-away ceiling ($)<input name="ceiling" type="number" required min="0.01" step="0.01"
          max={proposal.ceilingMinor / 100} defaultValue={proposal.ceilingMinor / 100} /></label>
        <fieldset>
          <legend>What may be shared with a dealer</legend>
          {proposal.disclosures.map(item =>
            <label key={item}><input type="checkbox" name="disclosure" value={item} />{DISCLOSURE_LABELS[item]}</label>)}
        </fieldset>
        <p>The mandate limits what you can approve. otdog never sends anything: you approve each message and send it from your own email.</p>
        <label>Mandate lasts<select name="lifetime" defaultValue="14">
          {LIFETIMES.map(days => <option key={days} value={days}>{days} days</option>)}
        </select></label>
        <label><input type="checkbox" name="approved" required />I approve this Negotiation Mandate. I can revoke it at any time.</label>
        <button disabled={busy}>Approve mandate</button>
      </form>}
    {mandates.length > 0 && <section aria-labelledby="mandate-history">
      <h3 id="mandate-history">Approved versions</h3>
      <ul className="mandate-history">
        {mandates.map(mandate => <li key={mandate.version}>
          Version {mandate.version}: {STATUS_LABELS[mandate.status]}. Target {money(mandate.targetMinor, mandate.currency)},
          ceiling {money(mandate.ceilingMinor, mandate.currency)}, approved {when(mandate.approvedAt)}
          {mandate.revokedAt ? `, revoked ${when(mandate.revokedAt)}` : ""}.
        </li>)}
      </ul>
    </section>}
    <button type="button" onClick={load} disabled={busy}>Check again</button>
  </section>;
}
