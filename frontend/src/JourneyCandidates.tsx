import { FormEvent, useEffect, useRef, useState } from "react";
import { DealerFields } from "./DealerFields";
import { CandidateCard, CandidateMarketCheck, CandidateResults, CandidateVehicle, CandidateVehicles } from "./CandidateVehicles";

export type JourneyCaseCredentials = { caseId: string; token: string };
export type ShortlistEntry = { candidateId: string; candidate: CandidateVehicle; marketCheck: CandidateMarketCheck; caseId: string | null };
type CaseResponse = { case: { id: string; referralDisclosure?: string | null }; accessToken: string };
type Options = { supported: string[]; consentPolicies: Record<string, string> };
type Problem = { status?: number; detail?: string; reason?: string; field?: string; candidateJurisdictions?: string[] };
type Request = <T>(path: string, init?: RequestInit & { key?: string }) => Promise<T>;

export function JourneyCandidates({ journeyId, briefVersion, postalCode, request, onSessionEnded, onCaseOpened }: {
  journeyId: string; briefVersion: number; postalCode: string; request: Request; onSessionEnded: () => void;
  onCaseOpened?: (value: JourneyCaseCredentials, disclosure?: string) => void;
}) {
  const path = `/api/v1/journeys/${encodeURIComponent(journeyId)}`;
  const [entries, setEntries] = useState<ShortlistEntry[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<ShortlistEntry>();
  const [loads, setLoads] = useState(0);
  function failed(thrown: unknown, fallback = "The shortlist could not be saved. Retry safely.") {
    const problem = thrown as Problem;
    if (problem?.status === 401) onSessionEnded();
    else setError(problem?.detail ?? fallback);
  }
  useEffect(() => {
    let current = true;
    request<ShortlistEntry[]>(`${path}/shortlist`).then(rows => {
      if (current) { setEntries(Array.isArray(rows) ? rows : []); setLoaded(true); setError(""); }
    }).catch(thrown => { if (current) failed(thrown); });
    return () => { current = false; };
  }, [journeyId, loads]);

  async function save(ids: string[]) {
    setBusy(true); setError("");
    try { setEntries(await request<ShortlistEntry[]>(`${path}/shortlist`, { method: "PUT", body: JSON.stringify({ candidateIds: ids }) })); }
    catch (thrown) { failed(thrown); }
    finally { setBusy(false); }
  }
  const ids = entries.map(entry => entry.candidateId);
  async function openLinked(entry: ShortlistEntry) {
    setBusy(true); setOpening(true); setError("");
    try {
      const created = await request<CaseResponse>(`${path}/candidates/${encodeURIComponent(entry.candidateId)}/case`, { method: "GET" });
      onCaseOpened?.({ caseId: created.case.id, token: created.accessToken }, created.case.referralDisclosure ?? undefined);
    } catch (thrown) { failed(thrown, "The linked case could not be opened. Retry safely."); }
    finally { setBusy(false); setOpening(false); }
  }
  function move(index: number, delta: number) {
    const ordered = [...ids];
    [ordered[index], ordered[index + delta]] = [ordered[index + delta], ordered[index]];
    void save(ordered);
  }
  return <>
    <CandidateVehicles briefVersion={briefVersion} onSessionEnded={onSessionEnded}
      search={() => request<CandidateResults>(`${path}/candidates`, { method: "POST" })}
      actions={candidate => candidate.candidateId && <button className="btn-secondary" disabled={!loaded || busy}
        onClick={() => save(ids.includes(candidate.candidateId!) ? ids.filter(id => id !== candidate.candidateId) : [...ids, candidate.candidateId!])}>
        {ids.includes(candidate.candidateId) ? "Remove from shortlist" : "Add to shortlist"}</button>} />
    <section className="journey-shortlist" aria-labelledby="journey-shortlist-heading">
      <h3 id="journey-shortlist-heading">Your shortlist</h3>
      <p>You choose the order. Saved candidates remain here when you return to this journey.</p>
      {error && <p role="alert">{error}</p>}
      {!loaded && (error ? <button onClick={() => setLoads(loads + 1)}>Retry loading shortlist</button> : <p role="status">Loading shortlist…</p>)}
      {loaded && entries.length === 0 && <p>No shortlisted candidates yet. Search inventory and add a candidate.</p>}
      {busy && <p role="status">{opening ? "Opening linked Buying Case…" : "Saving shortlist…"}</p>}
      <ol className="candidate-list" aria-label="Your shortlist order">
        {entries.map((entry, index) => <li key={entry.candidateId}>
          <CandidateCard candidate={{ ...entry.candidate, marketCheck: entry.marketCheck }} position={index + 1} />
          <div className="actions">
            <button className="btn-secondary" disabled={busy || index === 0} onClick={() => move(index, -1)} aria-label={`Move ${entry.candidate.title} up`}>Move up</button>
            <button className="btn-secondary" disabled={busy || index === entries.length - 1} onClick={() => move(index, 1)} aria-label={`Move ${entry.candidate.title} down`}>Move down</button>
            <button className="btn-ghost" disabled={busy} onClick={() => save(ids.filter(id => id !== entry.candidateId))} aria-label={`Remove ${entry.candidate.title} from shortlist`}>Remove</button>
            <button disabled={busy} onClick={() => entry.caseId ? openLinked(entry) : setSelected(entry)}>{entry.caseId ? "Open linked Buying Case" : "Open a Buying Case"}</button>
          </div>
          {entry.caseId && <p className="muted">A Buying Case is already linked to this candidate. Opening it keeps its saved location, purchase method and permissions.</p>}
        </li>)}
      </ol>
      {selected && <CandidateCaseForm key={selected.candidateId} entry={selected} postalCode={postalCode}
        request={request} path={path} onSessionEnded={onSessionEnded} onCancel={() => setSelected(undefined)}
        onOpened={(created) => {
          setEntries(rows => rows.map(row => row.candidateId === selected.candidateId ? { ...row, caseId: created.case.id } : row));
          setSelected(undefined);
          onCaseOpened?.({ caseId: created.case.id, token: created.accessToken }, created.case.referralDisclosure ?? undefined);
        }} />}
    </section>
  </>;
}

function CandidateCaseForm({ entry, postalCode, request, path, onCancel, onSessionEnded, onOpened }: {
  entry: ShortlistEntry; postalCode: string; request: Request; path: string; onCancel: () => void;
  onSessionEnded: () => void; onOpened: (created: CaseResponse) => void;
}) {
  const [options, setOptions] = useState<Options>();
  const [buyerPostalCode, setBuyerPostalCode] = useState(postalCode);
  const [problem, setProblem] = useState<Problem>();
  const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
    let current = true;
    fetch("/api/v1/intake-options").then(async response => {
      if (!response.ok) throw new Error();
      const value = await response.json() as Options;
      if (current) setOptions(value);
    }).catch(() => { if (current) setProblem({ detail: "Case permissions could not be loaded. Close this form and retry." }); });
    return () => { current = false; };
  }, []);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!options) return;
    const values = new FormData(event.currentTarget);
    setBusy(true);
    const decision = (name: string, granted: boolean) => ({ granted, policyVersion: options.consentPolicies[name] });
    try {
      const created = await request<CaseResponse>(`${path}/candidates/${encodeURIComponent(entry.candidateId)}/case`, {
        method: "POST", key: key.current, body: JSON.stringify({
          buyerPostalCode: String(values.get("buyerPostalCode")).trim(),
          dealerCity: String(values.get("dealerCity")).trim(), dealerState: String(values.get("dealerState")),
          dealerName: String(values.get("dealerName") ?? "").trim() || undefined,
          buyerJurisdiction: values.get("buyerJurisdiction") || undefined, purchaseMethod: values.get("purchaseMethod"),
          consents: { serviceProcessing: decision("serviceProcessing", true),
            humanReview: decision("humanReview", values.get("humanReview") === "on"),
            anonymizedResearch: decision("anonymizedResearch", values.get("anonymizedResearch") === "on") },
        }) });
      onOpened(created);
    } catch (thrown) {
      const failure = thrown as Problem;
      if (failure?.status === 401) onSessionEnded();
      else setProblem(failure?.detail ? failure : { detail: "The case could not be opened. Your shortlist is saved; retry safely." });
    } finally { setBusy(false); }
  }
  return <section className="candidate-case-form" aria-labelledby="candidate-case-heading">
    <h4 id="candidate-case-heading" ref={heading} tabIndex={-1}>Confirm your Buying Case</h4>
    <p>{entry.candidate.title}. Its Vehicle and Listing Snapshot will stay with the case as evidence.</p>
    {problem && <p role="alert">{problem.detail}</p>}
    <form onSubmit={submit}>
      <label>Your ZIP code<input name="buyerPostalCode" defaultValue={postalCode} inputMode="numeric" autoComplete="postal-code" required
        onChange={event => setBuyerPostalCode(event.target.value)} /></label>
      <DealerFields buyerPostalCode={buyerPostalCode} />
      {!!problem?.candidateJurisdictions?.length && <label>State where the vehicle will be registered
        <select name="buyerJurisdiction" required defaultValue=""><option value="">Choose your state</option>
          {problem.candidateJurisdictions.map(code => <option key={code} value={code}>{code}</option>)}</select>
      </label>}
      <label>How you are paying<select name="purchaseMethod" defaultValue="CASH"><option value="CASH">Cash</option><option value="FINANCE">Dealer financing</option></select></label>
      <fieldset className="switches"><legend>Permissions</legend>
        <p>Processing your entered details to produce your own report is required. The rest are optional and can be changed later.</p>
        <label><input name="humanReview" type="checkbox" />Let a reviewer look at redacted fields if a number needs checking</label>
        <label><input name="anonymizedResearch" type="checkbox" />Allow anonymized use of my case for research</label>
      </fieldset>
      <div className="actions"><button disabled={busy || !options}>{busy ? "Opening…" : "Confirm and open case"}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button></div>
    </form>
  </section>;
}
