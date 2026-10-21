import { useEffect, useRef, useState } from "react";
import { DealerHandoff } from "./DealerHandoff";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type SentQuote = { id: string; dealerAddress: string; state: string };
type Source = { offerVersion: number | null };
type Draft = { id: string; kind: string; state: string; offerVersion: number | null; planVersion: number | null; messageId?: string | null;
  gatedDraft: { draft: { text: string }; decision: GateDecision; language?: "en" | "es" } };
type View = { requests: Draft[]; agreedPrice: null | { offerVersion: number; baselineVersion: number;
  acceptedAt: string; actor: string; journey: { journeyLinked: boolean; otherOpenNegotiations: { caseId: string; label: string }[] } } };

export function AgreedPrice({ caseId, token, quotes, sources, onChanged }: {
  caseId: string; token: string; quotes: SentQuote[]; sources: Source[]; onChanged: () => Promise<void>;
}) {
  const { m: { paperwork: { agreedPrice: t } }, fill } = useMessages();
  const [view, setView] = useState<View>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/quote-acceptance`;
  async function request(url: string, body?: unknown) {
    const response = await fetch(url, { method: body === undefined ? "GET" : "POST", headers: {
      "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current,
    }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) throw new Error((await response.json().catch(() => ({}))).detail ?? t.updateFailed);
    return response.json();
  }
  async function refresh() {
    const value = await request(path);
    if (!Array.isArray(value.requests) || !("agreedPrice" in value)) throw new Error(t.statusUnavailable);
    setView(value);
  }
  useEffect(() => {
    let current = true;
    setView(undefined); setError(undefined);
    request(path).then(value => {
      if (current && Array.isArray(value.requests) && "agreedPrice" in value) setView(value);
    }).catch(() => { if (current) setError(t.statusUnavailable); });
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
      if (!response.ok) throw new Error(t.downloadFailed);
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
    <h2 id="agreed-price-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    {view?.agreedPrice ? <>
      <p role="status">{fill(t.saved, { offer: view.agreedPrice.offerVersion, baseline: view.agreedPrice.baselineVersion })}</p>
      <p>{fill(t.acceptedBy, { actor: view.agreedPrice.actor, time: format.dateTime(view.agreedPrice.acceptedAt) })}</p>
      {view.agreedPrice.journey.otherOpenNegotiations.length > 0 && <>
        <p>{t.chooseOthers}</p>
        <ul>{view.agreedPrice.journey.otherOpenNegotiations.map(other => <li key={other.caseId}>
          <p>{other.label}</p>
          <button type="button" disabled={busy} onClick={() => void chooseOther(other.caseId, "PAUSE")}>{fill(t.pause, { label: other.label })}</button>
          <button type="button" disabled={busy} onClick={() => void chooseOther(other.caseId, "STOP")}>{fill(t.stop, { label: other.label })}</button>
        </li>)}</ul>
      </>}
      <button type="button" disabled={busy} onClick={() => void download()}>{t.download}</button>
      <p>{t.openDownloaded}</p>
    </> : view && <form onSubmit={event => {
      event.preventDefault(); const values = new FormData(event.currentTarget);
      void update(path, { quoteRequestId: values.get("conversation"), offerVersion: values.get("offer") ? Number(values.get("offer")) : null });
    }}>
      <label>{t.conversation}<select name="conversation" required disabled={busy}>
        <option value="">{t.chooseSent}</option>{quotes.filter(quote => quote.state === "SENT").map(quote =>
          <option key={quote.id} value={quote.id}>{quote.dealerAddress}</option>)}
      </select></label>
      <label>{t.quoteToAccept}<select name="offer" disabled={busy}>
        <option value="">{t.verbal}</option>
        {versions.map(version => <option key={version} value={version}>{fill(t.verifiedOffer, { version })}</option>)}
      </select></label>
      <button disabled={busy}>{t.review}</button>
    </form>}
    {view?.requests.map(draft => <article className="card" key={draft.id}>
      <h3>{draft.kind === "ACCEPTANCE" ? fill(t.acceptOffer, { version: String(draft.offerVersion) }) : t.requestConfirmation}</h3>
      <p lang={draft.gatedDraft.language ?? "en"}>{draft.gatedDraft.draft.text}</p>
      {draft.kind === "ACCEPTANCE" && <p>{fill(t.onceSent, { plan: String(draft.planVersion) })}</p>}
      <OutboundGateDecision decision={draft.gatedDraft.decision} />
      {draft.state === "DRAFT" ? <button type="button" disabled={busy || !draft.gatedDraft.decision.pass || Boolean(view.agreedPrice)}
        onClick={() => void update(`${path}/${encodeURIComponent(draft.id)}/approval`, { approved: true, text: draft.gatedDraft.draft.text })}>
        {draft.gatedDraft.language === "es"
          ? draft.kind === "ACCEPTANCE" ? t.approveSpanishAcceptance : t.approveSpanishConfirmation
          : draft.kind === "ACCEPTANCE" ? t.approveAcceptance : t.approveConfirmation}
      </button> : draft.state === "HANDED_OFF" && draft.messageId
        ? <DealerHandoff caseId={caseId} token={token} messageId={draft.messageId}
          onChanged={async () => { await refresh(); await onChanged(); }} />
        : <p role="status">{draft.state === "SENT"
        ? draft.kind === "ACCEPTANCE" ? t.sentAcceptance : t.sentConfirmation
        : draft.state === "BLOCKED" ? t.blocked
        : draft.state === "DISCARDED" ? t.discarded
        : t.unconfirmed}</p>}
    </article>)}
    <button type="button" disabled={busy} onClick={() => { setError(undefined); void refresh().catch(failure => setError((failure as Error).message)); }}>{t.refresh}</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}
