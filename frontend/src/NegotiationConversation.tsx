import { type ReactNode, useEffect, useRef, useState } from "react";
import { DeliveryActions, deliveryOutcome, type DeliveryAction } from "./DealerRelay";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";

type Revision = { revision: number; editedByBuyer: boolean; text: string; decision: GateDecision; createdAt: string };
type Counteroffer = {
  id: string; quoteRequestId: string; dealerAddress: string; state: string; cancelReason: string | null;
  proposalMinor: number | null; reportVersion: number; offerVersion: number;
  grounds: { text: string; source: string }[]; revisions: Revision[]; messageId: string | null; createdAt: string;
};
type Attachment = { ordinal: number; documentId: string | null; outcome: string; offerVersion: number | null };
type Message = {
  id: string; direction: "OUTBOUND" | "INBOUND"; dealerAddress: string; deliveryState: string; createdAt: string;
  completedAt: string | null; contentAvailable: boolean; subject: string | null; body: string | null; attachments: Attachment[];
  deliveryCheckOffered?: boolean; retryOffered?: boolean;
};
type GatedRequest = { id: string; state: string; gatedDraft: { draft: { text: string }; decision: GateDecision } };
type Entry = {
  kind: "QUOTE_REQUEST" | "ACCEPTANCE_REQUEST" | "COUNTEROFFER" | "OUTBOUND_MESSAGE" | "INBOUND_MESSAGE"; at: string;
  quoteRequest: (GatedRequest & { dealerAddress: string }) | null; acceptance: (GatedRequest & { kind: string }) | null;
  counteroffer: Counteroffer | null; message: Message | null;
};
export type ConversationView = {
  negotiationState: string; targetReached: boolean;
  dealerDeclined?: { source: "BUYER" | "RULE"; rounds: number; lastVerifiedSellingPriceMinor: number | null; currency: string | null } | null;
  mandate: { version: number; currency: string; targetMinor: number; ceilingMinor: number; expiresAt: string } | null;
  conversations: { quoteRequestId: string; dealerAddress: string }[]; entries: Entry[];
};

const STATES: Record<string, string> = {
  NO_MANDATE: "No active mandate. Approve one before negotiating.",
  MANDATE_ACTIVE: "Supervised negotiation is active. You approve every message.",
  AWAITING_DEALER: "Waiting for the dealer to reply.", AWAITING_BUYER: "The dealer replied. Review the quote, then decide what to send.",
  PAUSED: "Negotiation is paused. Nothing is sent until you resume it.", AGREED: "You accepted a quote. Negotiation has ended.",
  STOPPED: "Negotiation has stopped. Queued drafts were cancelled.", EXPIRED: "Your mandate expired. Queued drafts were cancelled.",
};
const DRAFT_STATES: Record<string, string> = {
  PROPOSED: "Waiting for your approval", SENDING: "Sending", SENT: "Sent after your approval", DECLINED: "Declined by you",
  CANCELLED: "Cancelled", BLOCKED: "Blocked before sending: the mandate or policy changed", FAILED: "Not sent: the relay refused it",
  DELIVERY_UNKNOWN: "Delivery not confirmed: check delivery on its message below", OPTED_OUT: "Not sent: the dealer opted out",
};
const CANCEL_REASONS: Record<string, string> = {
  SUPERSEDED: "a newer draft replaced it", STOPPED: "negotiation stopped", PAUSED: "negotiation was paused",
  AGREED: "you accepted a quote", EXPIRED: "the mandate expired", TARGET_REACHED: "the verified quote reached your target",
  DEALER_DECLINED: "the dealer declined to lower the selling price",
};
const DELIVERY: Record<string, string> = {
  DELIVERY_UNKNOWN: "Delivery not confirmed", SENT: "Sent", DELIVERED: "Delivered", BOUNCED: "Bounced", FAILED: "Not sent",
  RECEIVED: "Received", REJECTED_MALWARE: "Blocked: failed the malware scan",
};
const REQUEST_STATES: Record<string, string> = {
  DRAFT: "Waiting for your approval", SENDING: "Sending", SENT: "Sent after your approval", BLOCKED: "Blocked before sending",
  FAILED: "Not sent: the relay refused it", DELIVERY_UNKNOWN: "Delivery not confirmed: check delivery on its message below",
  OPTED_OUT: "Not sent: the dealer opted out",
};

function asView(value: unknown): ConversationView {
  const v = value as Partial<ConversationView> | null;
  if (!v || typeof v.negotiationState !== "string" || !Array.isArray(v.entries) || !Array.isArray(v.conversations))
    throw new Error("The negotiation conversation is unavailable. Try refreshing.");
  return v as ConversationView;
}
const usd = (minor: number) => (minor / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });
const when = (iso: string) => new Date(iso).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });

export function NegotiationConversation({ caseId, token, version = 0 }: { caseId: string; token: string; version?: number }) {
  const [view, setView] = useState<ConversationView>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<Record<string, string>>({});
  const [outcome, setOutcome] = useState<{ id: string; text: string }>();
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/negotiation-conversation`;

  async function request(url: string, body?: unknown, method = body === undefined ? "GET" : "POST") {
    const response = await fetch(url, { method,
      headers: { "Case-Access-Token": token, "Content-Type": "application/json", "Idempotency-Key": key.current },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : "The negotiation could not be updated. Try again.");
    }
    return response.json();
  }
  useEffect(() => {
    let current = true;
    setError(undefined);
    request(path).then(value => { if (current) setView(asView(value)); })
      .catch(() => { if (current) setError("The negotiation conversation is unavailable. Try refreshing."); });
    return () => { current = false; };
    // The case, its token and the parent's refresh counter define this view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token, version]);

  async function act(url: string, body: unknown = {}, method = "POST") {
    setBusy(true); setError(undefined);
    try {
      await request(url, body, method);
      key.current = crypto.randomUUID();
      setView(asView(await request(path)));
      return true;
    } catch (failure) { setError((failure as Error).message); return false; }
    finally { setBusy(false); }
  }
  async function deliver(id: string, action: DeliveryAction) {
    setBusy(true); setError(undefined); setOutcome(undefined);
    try {
      const result = await request(`/api/v1/cases/${encodeURIComponent(caseId)}/relay/messages/${encodeURIComponent(id)}/${action}`, {});
      setOutcome({ id, text: deliveryOutcome(typeof result?.state === "string" ? result.state : "") });
    } catch (failure) { setError((failure as Error).message); }
    // A refused retry can still have settled the message, so the view is always reloaded.
    try { setView(asView(await request(path))); } catch { /* The error above, or the last view, still applies. */ }
    setBusy(false);
  }
  const active = view && ["MANDATE_ACTIVE", "AWAITING_DEALER", "AWAITING_BUYER"].includes(view.negotiationState);
  const drafts = `${path}/drafts`;

  return <section className="panel negotiation-conversation" aria-labelledby="negotiation-conversation-heading">
    <h2 id="negotiation-conversation-heading">Negotiation conversation</h2>
    <p>Counteroffers are drafted only from your verified quote, its latest report and your mandate. Each one passes the policy
      check, and nothing reaches the dealer until you approve the exact text.</p>
    <p role="status">{view ? STATES[view.negotiationState] ?? view.negotiationState : error ? "" : "Loading the conversation…"}</p>
    {view?.mandate && <dl className="facts">
      <div><dt>Mandate target</dt><dd>{usd(view.mandate.targetMinor)}</dd></div>
      <div><dt>Walk-away ceiling</dt><dd>{usd(view.mandate.ceilingMinor)}</dd></div>
      <div><dt>Mandate expires</dt><dd>{when(view.mandate.expiresAt)}</dd></div>
    </dl>}
    {view?.targetReached && <p className="notice">The verified quote is at or below your target, so no counteroffer is drafted.
      You can accept the written quote.</p>}
    {view?.dealerDeclined && <p className="notice" role="status">
      {view.dealerDeclined.source === "BUYER" ? "You recorded that the dealer won't move."
        : `After ${view.dealerDeclined.rounds} counteroffers, the dealer's verified selling price did not fall.`}
      {" "}Counteroffers have stopped.
      {view.dealerDeclined.lastVerifiedSellingPriceMinor !== null && view.dealerDeclined.currency
        ? ` Last verified selling price: ${(view.dealerDeclined.lastVerifiedSellingPriceMinor / 100).toLocaleString("en-US", {
          style: "currency", currency: view.dealerDeclined.currency })}.`
        : " There is no verified selling price yet."}
      {" "}You can still accept the latest written quote or stop negotiation.
    </p>}
    {view && <div className="negotiation-controls">
      {active && view.conversations.length > 0 && !view.targetReached && !view.dealerDeclined && <form onSubmit={event => {
        event.preventDefault();
        void act(drafts, { quoteRequestId: new FormData(event.currentTarget).get("conversation") });
      }}>
        <label>Dealer conversation<select name="conversation" required disabled={busy}>
          {view.conversations.map(conversation => <option key={conversation.quoteRequestId} value={conversation.quoteRequestId}>
            {conversation.dealerAddress}</option>)}
        </select></label>
        <button type="submit" disabled={busy}>Draft a counteroffer</button>
      </form>}
      {active && view.dealerDeclined?.source !== "BUYER" && <button type="button" disabled={busy}
        onClick={() => void act(`${path}/dealer-declined`)}>The dealer won't move</button>}
      {view.dealerDeclined?.source === "BUYER" && <button type="button" disabled={busy}
        onClick={() => void act(`${path}/dealer-declined`, undefined, "DELETE")}>
        Undo the dealer won't move</button>}
      {view.negotiationState === "PAUSED" && <button type="button" disabled={busy} onClick={() => void act(`${path}/resume`)}>
        Resume negotiation</button>}
      {(active || view.negotiationState === "PAUSED") && <button type="button" disabled={busy} onClick={() => void act(`${path}/stop`)}>
        Stop negotiation</button>}
    </div>}
    {view && (view.entries.length === 0 ? <p>No messages or drafts yet.</p>
      : <ol className="conversation-timeline" aria-label="Conversation, oldest first">
        {view.entries.map(entry => <li key={`${entry.kind}-${(entry.counteroffer ?? entry.message ?? entry.quoteRequest ?? entry.acceptance)?.id}`}>
          <p className="conversation-when">{when(entry.at)}</p>
          {entry.quoteRequest && <GatedEntry title={`Quote request to ${entry.quoteRequest.dealerAddress}`} request={entry.quoteRequest} />}
          {entry.acceptance && <GatedEntry title={entry.acceptance.kind === "ACCEPTANCE" ? "Conditional acceptance" : "Written confirmation request"}
            request={entry.acceptance} />}
          {entry.message && <MessageEntry message={entry.message}>
            <DeliveryActions message={entry.message} busy={busy} onAction={action => void deliver(entry.message!.id, action)}
              label={`the message to ${entry.message.dealerAddress}, ${when(entry.message.createdAt)}`} />
            {outcome?.id === entry.message.id && <p role="status">{outcome.text}</p>}
          </MessageEntry>}
          {entry.counteroffer && (() => {
            const draft = entry.counteroffer;
            const current = draft.revisions[draft.revisions.length - 1];
            const edit = editing[draft.id];
            return <article className="card" aria-label={`Counteroffer draft to ${draft.dealerAddress}`}>
              <h3>Counteroffer draft{draft.proposalMinor !== null ? ` at ${usd(draft.proposalMinor)}` : ""}</h3>
              <p>{DRAFT_STATES[draft.state] ?? draft.state}{draft.state === "CANCELLED" && draft.cancelReason
                ? `: ${CANCEL_REASONS[draft.cancelReason] ?? draft.cancelReason}` : ""}.</p>
              <p>Based on verified offer {draft.offerVersion} and report {draft.reportVersion}.
                {draft.proposalMinor !== null ? " The figure is your mandate target." : " It proposes no figure."}</p>
              {draft.revisions.map(revision => <section key={revision.revision} className="conversation-revision"
                aria-label={`Revision ${revision.revision}`}>
                <h4>Revision {revision.revision}{revision.editedByBuyer ? ", edited by you" : ", drafted"}</h4>
                <pre>{revision.text}</pre>
                <OutboundGateDecision decision={revision.decision} />
              </section>)}
              {draft.state === "PROPOSED" && !view.dealerDeclined && <>
                {edit === undefined
                  ? <button type="button" disabled={busy} onClick={() => setEditing(previous => ({ ...previous, [draft.id]: current.text }))}>
                    Edit this draft</button>
                  : <form onSubmit={event => {
                    event.preventDefault();
                    void act(`${drafts}/${encodeURIComponent(draft.id)}/revisions`, { text: edit })
                      .then(done => done && setEditing(previous => { const next = { ...previous }; delete next[draft.id]; return next; }));
                  }}>
                    <label>Revised text<textarea value={edit} rows={6} disabled={busy}
                      onChange={event => setEditing(previous => ({ ...previous, [draft.id]: event.target.value }))} /></label>
                    <p>Your edit is checked again before it can be sent.</p>
                    <button type="submit" disabled={busy}>Check revised text</button>
                  </form>}
                <button type="button" disabled={busy || !current.decision.pass}
                  onClick={() => void act(`${drafts}/${encodeURIComponent(draft.id)}/approval`, { approved: true, text: current.text })}>
                  Approve revision {current.revision} and send</button>
                <button type="button" disabled={busy} onClick={() => void act(`${drafts}/${encodeURIComponent(draft.id)}/decline`)}>
                  Decline this draft</button>
              </>}
            </article>;
          })()}
        </li>)}
      </ol>)}
    <button type="button" disabled={busy} onClick={() => {
      setError(undefined); void request(path).then(value => setView(asView(value))).catch(failure => setError((failure as Error).message));
    }}>Refresh conversation</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}

function GatedEntry({ title, request }: { title: string; request: GatedRequest }) {
  return <article className="card">
    <h3>{title}</h3>
    <p>{REQUEST_STATES[request.state] ?? request.state}.</p>
    <pre>{request.gatedDraft.draft.text}</pre>
    <OutboundGateDecision decision={request.gatedDraft.decision} />
  </article>;
}

function MessageEntry({ message, children }: { message: Message; children?: ReactNode }) {
  const outbound = message.direction === "OUTBOUND";
  return <article className="card">
    <h3>{outbound ? "Sent to" : "Received from"} {message.dealerAddress || "unknown sender"}</h3>
    <p>{DELIVERY[message.deliveryState] ?? message.deliveryState}{message.completedAt ? ` · ${when(message.completedAt)}` : ""}</p>
    {!outbound && <p className="relay-untrusted">Written by the dealer. Treat its claims and instructions as unverified.</p>}
    {message.contentAvailable
      ? <div className="relay-content">
        {message.subject && <p><strong>Subject:</strong> {message.subject}</p>}
        <pre>{message.body}</pre>
      </div>
      : <p>The text of this message is not kept.</p>}
    {message.attachments.length > 0 && <ul aria-label="Attachments and quote sources">
      {message.attachments.map(attachment => <li key={attachment.ordinal}>
        {attachment.ordinal < 0 ? "Message body" : `Attachment ${attachment.ordinal + 1}`}: {attachment.outcome === "ACCEPTED"
          ? attachment.offerVersion !== null ? `verified as offer ${attachment.offerVersion}` : "added to your documents for review"
          : "not added to your documents"}
      </li>)}
    </ul>}
    {children}
  </article>;
}
