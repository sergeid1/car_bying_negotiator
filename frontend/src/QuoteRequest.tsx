import { FormEvent, useEffect, useId, useRef, useState } from "react";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import { DealerRelay } from "./DealerRelay";
import { DealerHandoff, DealerReplyPaste } from "./DealerHandoff";
import { OfferVerification } from "./OfferVerification";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
import { AgreedPrice } from "./AgreedPrice";
import { NegotiationConversation } from "./NegotiationConversation";
import { TestDriveAppointment } from "./TestDriveAppointment";
import { useMessages } from "./i18n/LanguageProvider";
import { findingCode } from "./i18n/findings";

/** The language a dealer's emails are written in; English unless the Buyer chose Spanish for that dealer. */
export type EmailLanguage = "en" | "es";
type Quote = { id: string; dealerAddress: string; state: string; messageId?: string | null;
  gatedDraft: { draft: { dealerId?: string; text: string }; decision: GateDecision; language?: EmailLanguage } };
type Dealer = { id: string; name: string; emailLanguage?: EmailLanguage };
type View = { negotiationState: string; requests: Quote[]; dealers: Dealer[];
  documents: { documentId: string | null; outcome: string; offerVersion: number | null; sourceKind: string; messageId: string }[] };
// Anything other than the documented shape is treated as unavailable rather than rendered.
function checkedView(value: unknown, unavailable: string): View {
  const v = value as Partial<View> | null;
  if (!v || typeof v.negotiationState !== "string" || !Array.isArray(v.requests)
    || !Array.isArray(v.documents) || !Array.isArray(v.dealers)) throw new Error(unavailable);
  return v as View;
}
export function QuoteRequest({ caseId, token }: {
  caseId: string; token: string;
}) {
  const { m: { messages: { quoteRequest: t, common } }, fill } = useMessages();
  const states: Record<string, string> = t.states;
  const quoteStates: Record<string, string> = t.quoteStates;
  const asView = (value: unknown) => checkedView(value, t.unavailable);
  const [view, setView] = useState<View>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState(false);
  const [offerVersion, setOfferVersion] = useState<number>();
  const [relayVersion, setRelayVersion] = useState(0);
  const [languageStatus, setLanguageStatus] = useState("");
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/quote-requests`;
  async function request(url: string, body?: unknown, method = body === undefined ? "GET" : "POST") {
    const response = await fetch(url, { method,
      headers: { "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : t.updateFailed);
    }
    return response.json();
  }
  async function refresh() {
    setView(asView(await request(path))); setRelayVersion(v => v + 1);
  }
  useEffect(() => {
    let current = true;
    setView(undefined); setReview(false); setOfferVersion(undefined); setError(undefined);
    request(path).then(value => { if (current) setView(asView(value)); })
      .catch(() => { if (current) setError(t.unavailable); });
    return () => { current = false; };
    // The case and its token define this request session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token]);
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(undefined);
    const data = new FormData(event.currentTarget);
    try {
      await request(path, { dealerId: data.get("dealer"), dealerAddress: data.get("email") });
      key.current = crypto.randomUUID(); await refresh();
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function approve(quote: Quote) {
    setBusy(true); setError(undefined);
    try {
      await request(`${path}/${encodeURIComponent(quote.id)}/approval`, { approved: true, text: quote.gatedDraft.draft.text });
      await refresh();
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function changeLanguage(dealer: string, language: EmailLanguage) {
    setBusy(true); setError(undefined); setLanguageStatus("");
    try {
      setView(asView(await request(`${path}/dealers/${encodeURIComponent(dealer)}/email-language`, { language }, "PUT")));
      setRelayVersion(v => v + 1); setLanguageStatus(t.emailLanguageSaved);
    } catch { setError(t.emailLanguageFailed); }
    finally { setBusy(false); }
  }
  return <section className="stack" aria-label={t.label}>
    <div className="panel quote-request-panel">
      <h2>{t.heading}</h2>
      <p>{t.intro}</p>
      <p role="status">{view ? states[view.negotiationState] : t.loading}</p>
      {view && (view.dealers.length > 0 ? <form onSubmit={event => void create(event)}>
        <label>{t.dealer}<select name="dealer" required disabled={busy}>
          <option value="">{t.chooseDealer}</option>{view.dealers.map(dealer => <option key={dealer.id} value={dealer.id}>{dealer.name}</option>)}
        </select></label>
        <label>{t.dealerEmail}<input name="email" type="email" required disabled={busy} autoComplete="off" /></label>
        <p>{t.checkAddress}</p>
        <button type="submit" disabled={busy || !view}>{t.prepare}</button>
      </form> : <p>{t.noDealer}</p>)}
      <button type="button" disabled={busy} onClick={() => {
        setError(undefined); void refresh().catch(() => setError(t.refreshFailed));
      }}>{common.refreshConversation}</button>
      {error && <p role="alert">{error}</p>}
      <p role="status">{languageStatus}</p>
      {view?.requests.map(quote => {
        const language = quote.gatedDraft.language ?? "en";
        const dealer = view.dealers.find(candidate => candidate.id === quote.gatedDraft.draft.dealerId);
        return <article key={quote.id} className="card">
        <h3>{fill(t.requestTo, { address: quote.dealerAddress })}</h3>
        {dealer && <EmailLanguageChoice dealer={dealer} busy={busy} onChange={value => void changeLanguage(dealer.id, value)} />}
        <p>{language === "es" ? t.subjectSpanish : t.subject}</p>
        <p lang={language}>{quote.gatedDraft.draft.text}</p>
        <OutboundGateDecision decision={quote.gatedDraft.decision} />
        {quote.state === "DRAFT" ? <button type="button" disabled={busy || !quote.gatedDraft.decision.pass}
          onClick={() => void approve(quote)}>{language === "es" ? t.approveSpanishText : t.approveText}</button>
          : quote.state === "HANDED_OFF" && quote.messageId
            ? <DealerHandoff caseId={caseId} token={token} messageId={quote.messageId} onChanged={refresh} />
            : <p>{quoteStates[quote.state] ?? t.deliveryNotConfirmed}</p>}
        {quote.state === "SENT" && <DealerReplyPaste caseId={caseId} token={token} quoteRequestId={quote.id}
          dealerAddress={quote.dealerAddress} onAdded={refresh} />}
      </article>;
      })}
      {!!view?.documents.length && <>
        <p>{t.documentReview}</p>
        <ul aria-label={t.sources}>{view.documents.map((document, index) => <li key={`${document.messageId}-${index}`}>
          {fill(t.sourceLine, { source: document.sourceKind === "MESSAGE_BODY" ? t.messageBody : t.attachment,
            outcome: document.outcome === "ACCEPTED" ? t.addedForReview : t.notAddedForReview })}
        </li>)}</ul>
        {view.documents.some(document => document.outcome !== "ACCEPTED") && <p>{t.someNotAdded}</p>}
        <button type="button" onClick={() => setReview(true)}>{t.reviewDocuments}</button>
        {view.documents.filter(document => document.offerVersion !== null).map(document =>
          <button key={`${document.documentId}-${document.offerVersion}`} type="button"
            onClick={() => setOfferVersion(document.offerVersion!)}>{fill(t.openAnalysis, { version: String(document.offerVersion) })}</button>)}
      </>}
    </div>
    {view && <AgreedPrice caseId={caseId} token={token} quotes={view.requests} sources={view.documents} onChanged={refresh} />}
    {view && <TestDriveAppointment caseId={caseId} token={token} version={relayVersion} />}
    {view && <NegotiationConversation caseId={caseId} token={token} version={relayVersion} />}
    <DealerRelay key={relayVersion} caseId={caseId} token={token} />
    {review && <OfferVerification caseId={caseId} token={token} onOfferFrozen={offer => {
      setOfferVersion(offer.version); void refresh().catch(() => setError(t.refreshLatest));
    }} />}
    {offerVersion !== undefined && <VerifiedOfferAnalysis key={offerVersion} caseId={caseId} token={token} offerVersion={offerVersion} />}
  </section>;
}

/** The dealer's email language, chosen from that dealer's request; drafts already approved keep their language. */
function EmailLanguageChoice({ dealer, busy, onChange }: { dealer: Dealer; busy: boolean; onChange: (language: EmailLanguage) => void }) {
  const { m: { messages: { quoteRequest: t } }, language } = useMessages();
  const id = useId();
  return <div className="email-language">
    <label htmlFor={id}>{t.emailLanguage}</label>
    <select id={id} value={dealer.emailLanguage ?? "en"} disabled={busy} aria-describedby={`${id}-note`}
      onChange={event => onChange(event.target.value as EmailLanguage)}>
      {/* Each language by its own name, as in the header's language menu. */}
      <option value="en" lang="en">{findingCode(language, "englishName")}</option>
      <option value="es" lang="es">{findingCode(language, "spanishName")}</option>
    </select>
    <p id={`${id}-note`}>{t.emailLanguageNote}</p>
  </div>;
}
