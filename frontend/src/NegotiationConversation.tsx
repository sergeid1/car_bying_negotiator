import { useEffect, useRef, useState } from "react";
import { DealerHandoff } from "./DealerHandoff";
import { OutboundGateDecision, type GateDecision } from "./OutboundGateDecision";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

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
  sendPath?: string;
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

function checkedView(value: unknown, unavailable: string): ConversationView {
  const v = value as Partial<ConversationView> | null;
  if (!v || typeof v.negotiationState !== "string" || !Array.isArray(v.entries) || !Array.isArray(v.conversations))
    throw new Error(unavailable);
  return v as ConversationView;
}
/** A label from a catalogue table, or the code itself when the table has none. */
const label = (table: Record<string, string>, code: string) => table[code] ?? code;
const usd = (minor: number) => format.money(minor);
const when = (iso: string) => format.dateTime(iso, { dateStyle: "medium", timeStyle: "short" });

export function NegotiationConversation({ caseId, token, version = 0 }: { caseId: string; token: string; version?: number }) {
  const { m: { messages: { negotiationConversation: t, common } }, fill } = useMessages();
  const asView = (value: unknown) => checkedView(value, t.unavailable);
  const [view, setView] = useState<ConversationView>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<Record<string, string>>({});
  const key = useRef(crypto.randomUUID());
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/negotiation-conversation`;

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
  useEffect(() => {
    let current = true;
    setError(undefined);
    request(path).then(value => { if (current) setView(asView(value)); })
      .catch(() => { if (current) setError(t.unavailable); });
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
  const active = view && ["MANDATE_ACTIVE", "AWAITING_DEALER", "AWAITING_BUYER"].includes(view.negotiationState);
  const drafts = `${path}/drafts`;

  return <section className="panel negotiation-conversation" aria-labelledby="negotiation-conversation-heading">
    <h2 id="negotiation-conversation-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    <p role="status">{view ? label(t.states, view.negotiationState) : error ? "" : t.loading}</p>
    {view?.mandate && <dl className="facts">
      <div><dt>{t.mandateTarget}</dt><dd>{usd(view.mandate.targetMinor)}</dd></div>
      <div><dt>{t.walkAwayCeiling}</dt><dd>{usd(view.mandate.ceilingMinor)}</dd></div>
      <div><dt>{t.mandateExpires}</dt><dd>{when(view.mandate.expiresAt)}</dd></div>
    </dl>}
    {view?.targetReached && <p className="notice">{t.targetReached}</p>}
    {view?.dealerDeclined && <p className="notice" role="status">
      {view.dealerDeclined.source === "BUYER" ? t.declinedByBuyer
        : fill(t.declinedByRule, { rounds: view.dealerDeclined.rounds })}
      {" "}{t.counteroffersStopped}
      {" "}{view.dealerDeclined.lastVerifiedSellingPriceMinor !== null && view.dealerDeclined.currency
        ? fill(t.lastVerifiedPrice, { amount: format.money(view.dealerDeclined.lastVerifiedSellingPriceMinor, { currency: view.dealerDeclined.currency }) })
        : t.noVerifiedPrice}
      {" "}{t.stillAccept}
    </p>}
    {view && <div className="negotiation-controls">
      {active && view.conversations.length > 0 && !view.targetReached && !view.dealerDeclined && <form onSubmit={event => {
        event.preventDefault();
        void act(drafts, { quoteRequestId: new FormData(event.currentTarget).get("conversation") });
      }}>
        <label>{t.dealerConversation}<select name="conversation" required disabled={busy}>
          {view.conversations.map(conversation => <option key={conversation.quoteRequestId} value={conversation.quoteRequestId}>
            {conversation.dealerAddress}</option>)}
        </select></label>
        <button type="submit" disabled={busy}>{t.draftCounteroffer}</button>
      </form>}
      {active && view.dealerDeclined?.source !== "BUYER" && <button type="button" disabled={busy}
        onClick={() => void act(`${path}/dealer-declined`)}>{t.dealerWontMove}</button>}
      {view.dealerDeclined?.source === "BUYER" && <button type="button" disabled={busy}
        onClick={() => void act(`${path}/dealer-declined`, undefined, "DELETE")}>
        {t.undoDealerWontMove}</button>}
      {view.negotiationState === "PAUSED" && <button type="button" disabled={busy} onClick={() => void act(`${path}/resume`)}>
        {t.resume}</button>}
      {(active || view.negotiationState === "PAUSED") && <button type="button" disabled={busy} onClick={() => void act(`${path}/stop`)}>
        {t.stop}</button>}
    </div>}
    {view && (view.entries.length === 0 ? <p>{t.none}</p>
      : <ol className="conversation-timeline" aria-label={t.timeline}>
        {view.entries.map(entry => <li key={`${entry.kind}-${(entry.counteroffer ?? entry.message ?? entry.quoteRequest ?? entry.acceptance)?.id}`}>
          <p className="conversation-when">{when(entry.at)}</p>
          {entry.quoteRequest && <GatedEntry title={fill(t.quoteRequestTo, { address: entry.quoteRequest.dealerAddress })} request={entry.quoteRequest} />}
          {entry.acceptance && <GatedEntry title={entry.acceptance.kind === "ACCEPTANCE" ? t.conditionalAcceptance : t.writtenConfirmation}
            request={entry.acceptance} />}
          {entry.message && <MessageEntry message={entry.message} />}
          {entry.counteroffer && (() => {
            const draft = entry.counteroffer;
            const current = draft.revisions[draft.revisions.length - 1];
            const edit = editing[draft.id];
            return <article className="card" aria-label={fill(t.counterofferTo, { address: draft.dealerAddress })}>
              <h3>{draft.proposalMinor !== null ? fill(t.counterofferDraftAt, { amount: usd(draft.proposalMinor) }) : t.counterofferDraft}</h3>
              <p>{draft.state === "CANCELLED" && draft.cancelReason
                ? fill(t.stateWithReason, { state: label(t.draftStates, draft.state), reason: label(t.cancelReasons, draft.cancelReason) })
                : fill(common.sentence, { text: label(t.draftStates, draft.state) })}</p>
              <p>{fill(t.basedOn, { offer: String(draft.offerVersion), report: String(draft.reportVersion) })}
                {" "}{draft.proposalMinor !== null ? t.figureIsTarget : t.noFigure}</p>
              {draft.revisions.map(revision => <section key={revision.revision} className="conversation-revision"
                aria-label={fill(t.revision, { revision: String(revision.revision) })}>
                <h4>{fill(revision.editedByBuyer ? t.revisionEdited : t.revisionDrafted, { revision: String(revision.revision) })}</h4>
                <pre>{revision.text}</pre>
                <OutboundGateDecision decision={revision.decision} />
              </section>)}
              {draft.state === "PROPOSED" && !view.dealerDeclined && <>
                {edit === undefined
                  ? <button type="button" disabled={busy} onClick={() => setEditing(previous => ({ ...previous, [draft.id]: current.text }))}>
                    {t.editDraft}</button>
                  : <form onSubmit={event => {
                    event.preventDefault();
                    void act(`${drafts}/${encodeURIComponent(draft.id)}/revisions`, { text: edit })
                      .then(done => done && setEditing(previous => { const next = { ...previous }; delete next[draft.id]; return next; }));
                  }}>
                    <label>{t.revisedText}<textarea value={edit} rows={6} disabled={busy}
                      onChange={event => setEditing(previous => ({ ...previous, [draft.id]: event.target.value }))} /></label>
                    <p>{t.checkedAgain}</p>
                    <button type="submit" disabled={busy}>{t.checkRevised}</button>
                  </form>}
                <button type="button" disabled={busy || !current.decision.pass}
                  onClick={() => void act(`${drafts}/${encodeURIComponent(draft.id)}/approval`, { approved: true, text: current.text })}>
                  {fill(t.approveRevision, { revision: String(current.revision) })}</button>
                <button type="button" disabled={busy} onClick={() => void act(`${drafts}/${encodeURIComponent(draft.id)}/decline`)}>
                  {t.declineDraft}</button>
              </>}
              {draft.state === "HANDED_OFF" && draft.messageId && <DealerHandoff caseId={caseId} token={token}
                messageId={draft.messageId} onChanged={async () => setView(asView(await request(path)))} />}
            </article>;
          })()}
        </li>)}
      </ol>)}
    <button type="button" disabled={busy} onClick={() => {
      setError(undefined); void request(path).then(value => setView(asView(value))).catch(failure => setError((failure as Error).message));
    }}>{common.refreshConversation}</button>
    {error && <p role="alert">{error}</p>}
  </section>;
}

function GatedEntry({ title, request }: { title: string; request: GatedRequest }) {
  const { m: { messages: { negotiationConversation: t, common } }, fill } = useMessages();
  return <article className="card">
    <h3>{title}</h3>
    <p>{fill(common.sentence, { text: label(t.requestStates, request.state) })}</p>
    <pre>{request.gatedDraft.draft.text}</pre>
    <OutboundGateDecision decision={request.gatedDraft.decision} />
  </article>;
}

function MessageEntry({ message }: { message: Message }) {
  const { m: { messages: { negotiationConversation: t, common } }, fill } = useMessages();
  const sendPaths: Record<string, string> = t.sendPaths;
  const outbound = message.direction === "OUTBOUND";
  return <article className="card">
    <h3>{fill(outbound ? ["HANDED_OFF", "DISCARDED", "DELIVERY_UNKNOWN"].includes(message.deliveryState) ? t.messageTo
      : t.messageSentTo : t.messageReceivedFrom, { address: message.dealerAddress || common.unknownSender })}</h3>
    <p>{label(t.delivery, message.deliveryState)}
      {outbound && message.sendPath && sendPaths[message.sendPath] ? ` · ${sendPaths[message.sendPath]}` : ""}
      {message.completedAt ? ` · ${when(message.completedAt)}` : ""}</p>
    {!outbound && <p className="relay-untrusted">{common.writtenByDealer}</p>}
    {message.contentAvailable
      ? <div className="relay-content">
        {message.subject && <p><strong>{common.subjectLabel}</strong> {message.subject}</p>}
        <pre>{message.body}</pre>
      </div>
      : <p>{common.textNotKept}</p>}
    {message.attachments.length > 0 && <ul aria-label={t.attachmentsAndSources}>
      {message.attachments.map(attachment => <li key={attachment.ordinal}>
        {fill(common.sourceOutcome, {
          source: attachment.ordinal < 0 ? t.messageBody : fill(common.attachment, { number: attachment.ordinal + 1 }),
          outcome: attachment.outcome === "ACCEPTED"
            ? attachment.offerVersion !== null ? fill(t.verifiedAsOffer, { offer: String(attachment.offerVersion) }) : t.addedForReview
            : t.notAdded })}
      </li>)}
    </ul>}
  </article>;
}
