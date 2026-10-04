import { FormEvent, useEffect, useRef, useState } from "react";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import { DealerRelay } from "./DealerRelay";
import { OfferVerification } from "./OfferVerification";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";

type Quote = { id: string; dealerAddress: string; state: string;
  gatedDraft: { draft: { text: string }; decision: GateDecision } };
type View = { negotiationState: string; requests: Quote[]; dealers: { id: string; name: string }[];
  documents: { documentId: string | null; outcome: string; offerVersion: number | null; sourceKind: string; messageId: string }[] };
const states: Record<string, string> = {
  NO_MANDATE: "Approve a negotiation mandate to contact the dealer.",
  MANDATE_ACTIVE: "Your mandate is active. Review a quote request before sending it.",
  AWAITING_DEALER: "Your request was sent. Waiting for the dealer to reply.",
  AWAITING_BUYER: "The dealer replied. Review and verify the quote before analysis.",
  AGREED: "You accepted the quote.", STOPPED: "Negotiation has stopped.", EXPIRED: "Your mandate expired. No more requests can be sent.",
};
export function QuoteRequest({ caseId, token }: {
  caseId: string; token: string;
}) {
  const [view, setView] = useState<View>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [review, setReview] = useState(false);
  const [offerVersion, setOfferVersion] = useState<number>();
  const [relayVersion, setRelayVersion] = useState(0);
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/quote-requests`;
  async function request(url: string, body?: unknown) {
    const response = await fetch(url, { method: body === undefined ? "GET" : "POST",
      headers: { "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : "The quote request could not be updated. Try again.");
    }
    return response.json();
  }
  async function refresh() {
    setView(await request(path) as View); setRelayVersion(v => v + 1);
  }
  useEffect(() => {
    let current = true;
    setView(undefined); setReview(false); setOfferVersion(undefined); setError(undefined);
    request(path).then(value => { if (current) setView(value as View); })
      .catch(() => { if (current) setError("Quote requests are unavailable. Try refreshing."); });
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
  return <section className="stack" aria-label="Request a dealer quote">
    <div className="panel">
      <h2>Request an itemized quote</h2>
      <p>Review the request for this Vehicle. Sending requires your current mandate and your approval.</p>
      <p role="status">{view ? states[view.negotiationState] : "Loading quote requests…"}</p>
      {view && (view.dealers.length > 0 ? <form onSubmit={event => void create(event)}>
        <label>Dealer<select name="dealer" required disabled={busy}>
          <option value="">Choose a dealer</option>{view.dealers.map(dealer => <option key={dealer.id} value={dealer.id}>{dealer.name}</option>)}
        </select></label>
        <label>Dealer email<input name="email" type="email" required disabled={busy} /></label>
        <button type="submit" disabled={busy || !view}>Prepare request</button>
      </form> : <p>No dealers have a listing that matches this Vehicle's confirmed VIN. Add a matching dealer listing before preparing a quote request.</p>)}
      <button type="button" disabled={busy} onClick={() => {
        setError(undefined); void refresh().catch(() => setError("Quote requests could not be refreshed."));
      }}>Refresh conversation</button>
      {error && <p role="alert">{error}</p>}
      {view?.requests.map(quote => <article key={quote.id} className="card">
        <h3>Quote request to {quote.dealerAddress}</h3>
        <p>Subject: Request for an itemized vehicle quote</p>
        <p>{quote.gatedDraft.draft.text}</p>
        <p>Every sent message includes the relay's notice that Deal X-Ray acts on behalf of a Buyer,
          uses AI assistance, and lets the dealer opt out.</p>
        <OutboundGateDecision decision={quote.gatedDraft.decision} />
        {quote.state === "DRAFT" ? <button type="button" disabled={busy || !quote.gatedDraft.decision.pass}
          onClick={() => void approve(quote)}>Approve this text and send</button>
          : <p>{quote.state === "SENT" ? "Request sent."
            : quote.state === "BLOCKED" ? "The mandate or policy changed before sending. This request was stopped."
            : "Delivery was not confirmed. Check the conversation before preparing another request."}</p>}
      </article>)}
      {!!view?.documents.length && <>
        <p>Dealer quotes enter document review. Confirm or correct the extracted amounts against the source before analysis.</p>
        <ul aria-label="Dealer quote sources">{view.documents.map((document, index) => <li key={`${document.messageId}-${index}`}>
          {document.sourceKind === "MESSAGE_BODY" ? "Dealer message body" : "Dealer attachment"}: {document.outcome === "ACCEPTED"
            ? "added as a document for your review" : "could not be added for review"}.
        </li>)}</ul>
        {view.documents.some(document => document.outcome !== "ACCEPTED") && <p>Some reply content could not be added. Check the conversation and ask for a supported document.</p>}
        <button type="button" onClick={() => setReview(true)}>Review quote documents</button>
        {view.documents.filter(document => document.offerVersion !== null).map(document =>
          <button key={`${document.documentId}-${document.offerVersion}`} type="button"
            onClick={() => setOfferVersion(document.offerVersion!)}>Open analysis of verified quote {document.offerVersion}</button>)}
      </>}
    </div>
    <DealerRelay key={relayVersion} caseId={caseId} token={token} />
    {review && <OfferVerification caseId={caseId} token={token} onOfferFrozen={offer => {
      setOfferVersion(offer.version); void refresh().catch(() => setError("Refresh to see the latest quote status."));
    }} />}
    {offerVersion !== undefined && <VerifiedOfferAnalysis key={offerVersion} caseId={caseId} token={token} offerVersion={offerVersion} />}
  </section>;
}
