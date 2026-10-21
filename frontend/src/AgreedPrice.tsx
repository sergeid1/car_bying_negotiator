import { useEffect, useRef, useState } from "react";
import { DealerHandoff } from "./DealerHandoff";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import * as format from "./i18n/format";

type SentQuote = { id: string; dealerAddress: string; state: string };
type Source = { offerVersion: number | null };
type Draft = { id: string; kind: string; state: string; offerVersion: number | null; planVersion: number | null; messageId?: string | null;
  gatedDraft: { draft: { text: string }; decision: GateDecision } };
type View = { requests: Draft[]; agreedPrice: null | { offerVersion: number; baselineVersion: number;
  acceptedAt: string; actor: string; journey: { journeyLinked: boolean; otherOpenNegotiations: { caseId: string; label: string }[] } } };

export function AgreedPrice({ caseId, token, quotes, sources, onChanged }: {
  caseId: string; token: string; quotes: SentQuote[]; sources: Source[]; onChanged: () => Promise<void>;
}) {
  const [view, setView] = useState<View>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/quote-acceptance`;
  async function request(url: string, body?: unknown) {
    const response = await fetch(url, { method: body === undefined ? "GET" : "POST", headers: {
      "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current,
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) throw new Error((await response.json().catch(() => ({}))).detail ?? "The quote could not be updated. Try refreshing.");
    return response.json();
  }
  async function refresh() {
    const value = await request(path);
    if (!Array.isArray(value.requests) || !("agreedPrice" in value)) throw new Error("Acceptance status is unavailable. Try refreshing.");
    setView(value);
  }
  useEffect(() => {
    let current = true;
    setView(undefined); setError(undefined);
    request(path).then(value => {
      if (current && Array.isArray(value.requests) && "agreedPrice" in value) setView(value);
    }).catch(() => { if (current) setError("Acceptance status is unavailable. Try refreshing."); });
    return () => { current = false; };
    // Credentials define the acceptance session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token]);
  async function update(url: string, body: unknown) {
    setBusy(true); setError(undefined);
    try { await request(url, body); key.current = crypto.randomUUID(); await refresh(); await onChanged(); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function download() {
    setBusy(true); setError(undefined);
    try {
      const response = await fetch(`${path}/summary`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("The summary could not be downloaded. Try again.");
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = "agreed-price.html";
      link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function chooseOther(otherCaseId: string, action: "PAUSE" | "STOP") {
    setBusy(true); setError(undefined);
    try {
      const journey = await request(`${path}/other-negotiations/${encodeURIComponent(otherCaseId)}`, { action });
      setView(previous => previous?.agreedPrice ? { ...previous, agreedPrice: { ...previous.agreedPrice, journey } } : previous);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  const versions = [...new Set(sources.flatMap(source => source.offerVersion === null ? [] : [source.offerVersion]))].sort((a, b) => b - a);
  return <section className="panel agreed-price-panel" aria-labelledby="agreed-price-heading">
    <h2 id="agreed-price-heading">Accept a written quote</h2>
    <p>Acceptance is subject to a test drive and final paperwork matching the quote. Review and verify the itemized source and price boundaries first.</p>
    {view?.agreedPrice ? <>
      <p role="status">Agreed Price saved from verified offer {view.agreedPrice.offerVersion}. Approved Baseline version {view.agreedPrice.baselineVersion}.</p>
      <p>Accepted by {view.agreedPrice.actor} at {format.dateTime(view.agreedPrice.acceptedAt)}.</p>
      {view.agreedPrice.journey.otherOpenNegotiations.length > 0 && <>
        <p>Choose whether to pause or stop other open negotiations in your journey.</p>
        <ul>{view.agreedPrice.journey.otherOpenNegotiations.map(other => <li key={other.caseId}>
          <p>{other.label}</p>
          <button type="button" disabled={busy} onClick={() => void chooseOther(other.caseId, "PAUSE")}>Pause {other.label}</button>
          <button type="button" disabled={busy} onClick={() => void chooseOther(other.caseId, "STOP")}>Stop {other.label}</button>
        </li>)}</ul>
      </>}
      <button type="button" disabled={busy} onClick={() => void download()}>Download one-page Agreed Price summary</button>
      <p>Open the downloaded HTML file to view or print the summary to bring to the dealership.</p>
    </> : view && <form onSubmit={event => {
      event.preventDefault(); const values = new FormData(event.currentTarget);
      void update(path, { quoteRequestId: values.get("conversation"), offerVersion: values.get("offer") ? Number(values.get("offer")) : null });
    }}>
      <label>Dealer conversation<select name="conversation" required disabled={busy}>
        <option value="">Choose a sent request</option>{quotes.filter(quote => quote.state === "SENT").map(quote =>
          <option key={quote.id} value={quote.id}>{quote.dealerAddress}</option>)}
      </select></label>
      <label>Quote to accept<select name="offer" disabled={busy}>
        <option value="">Verbal or partial figure — ask for written confirmation</option>
        {versions.map(version => <option key={version} value={version}>Verified offer {version}</option>)}
      </select></label>
      <button disabled={busy}>Review acceptance or confirmation request</button>
    </form>}
    {view?.requests.map(draft => <article className="card" key={draft.id}>
      <h3>{draft.kind === "ACCEPTANCE" ? `Accept verified offer ${draft.offerVersion}` : "Request written confirmation"}</h3>
      <p>{draft.gatedDraft.draft.text}</p>
      {draft.kind === "ACCEPTANCE" && <p>Once you send it and tell us, the Agreed Price is saved with an Approved Baseline from reviewed price plan {draft.planVersion}.</p>}
      <OutboundGateDecision decision={draft.gatedDraft.decision} />
      {draft.state === "DRAFT" ? <button type="button" disabled={busy || !draft.gatedDraft.decision.pass || Boolean(view.agreedPrice)}
        onClick={() => void update(`${path}/${encodeURIComponent(draft.id)}/approval`, { approved: true, text: draft.gatedDraft.draft.text })}>
        {draft.kind === "ACCEPTANCE" ? "Approve this acceptance text and baseline" : "Approve written-confirmation text"}
      </button> : draft.state === "HANDED_OFF" && draft.messageId
        ? <DealerHandoff caseId={caseId} token={token} messageId={draft.messageId}
          onChanged={async () => { await refresh(); await onChanged(); }} />
        : <p role="status">{draft.state === "SENT"
        ? draft.kind === "ACCEPTANCE" ? "Conditional acceptance message sent." : "Written confirmation requested. Review and verify the dealer's reply before accepting."
        : draft.state === "BLOCKED" ? "The mandate or quote changed. Prepare a new request."
        : draft.state === "DISCARDED" ? "Discarded. It was not sent."
        : "Delivery was not confirmed. Check the conversation before preparing another request."}</p>}
    </article>)}
    <button type="button" disabled={busy} onClick={() => { setError(undefined); void refresh().catch(failure => setError((failure as Error).message)); }}>Refresh acceptance status</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
