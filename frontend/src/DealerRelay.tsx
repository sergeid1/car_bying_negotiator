import { useEffect, useState } from "react";

type Attachment = { ordinal: number; documentId: string | null; outcome: string };
export type RelayMessage = {
  id: string; direction: "OUTBOUND" | "INBOUND"; trust: "PLATFORM" | "UNTRUSTED"; dealerAddress: string; state: string;
  createdAt: string; completedAt: string | null; contentAvailable: boolean; attachments: Attachment[];
  deliveryCheckOffered?: boolean; retryOffered?: boolean;
};
export type RelayView = {
  address: string; dealerOptedOut: boolean; dealerOptedOutAt: string | null; optOutChannel: "LINK" | "REPLY" | null;
  perCaseDailyCap: number; sentLastDay: number; messages: RelayMessage[];
};
type Content = { subject: string | null; body: string | null };

const STATE_LABELS: Record<string, string> = {
  DELIVERY_UNKNOWN: "Delivery not confirmed", SENT: "Sent", DELIVERED: "Delivered", BOUNCED: "Bounced",
  FAILED: "Not sent", RECEIVED: "Received", REJECTED_MALWARE: "Blocked: failed the malware scan",
};
export type DeliveryAction = "delivery-check" | "retry";
const DELIVERY_OUTCOMES: Record<string, string> = {
  SENT: "The relay has this message, so it is now marked as sent.",
  FAILED: "The relay does not have this message, so it was not sent.",
  DELIVERY_UNKNOWN: "The relay still did not confirm this message. Check delivery again later.",
};
export const deliveryOutcome = (state: string) => DELIVERY_OUTCOMES[state] ?? "The message status was updated.";

/** Plain explanations and the delivery check or retry action, shown only when the server offers it. */
export function DeliveryActions({ message, label, busy, onAction }: {
  message: { deliveryCheckOffered?: boolean; retryOffered?: boolean }; label: string; busy: boolean;
  onAction: (action: DeliveryAction) => void;
}) {
  if (!message.deliveryCheckOffered && !message.retryOffered) return null;
  return <div>
    {message.deliveryCheckOffered && <>
      <p>The relay did not confirm that it took this message, so the dealer may or may not have it.
        Checking delivery asks the relay whether it has the message. Checking never sends anything.</p>
      <button type="button" disabled={busy} onClick={() => onAction("delivery-check")}
        aria-label={`Check delivery of ${label}`}>Check delivery</button>
    </>}
    {message.retryOffered && <>
      <p>The relay does not have this message. Retry sends the same stored text with the same message key, so the dealer
        gets it at most once. Retry is refused if the dealer opted out, the daily limit is reached, or your mandate or
        negotiation no longer allows this message.</p>
      <button type="button" disabled={busy} onClick={() => onAction("retry")}
        aria-label={`Retry ${label}`}>Retry</button>
    </>}
  </div>;
}

const ATTACHMENT_LABELS: Record<string, string> = {
  ACCEPTED: "added to your documents", MALWARE_DETECTED: "blocked: failed the malware scan",
};

export function DealerRelay({ caseId, token }: { caseId: string; token: string }) {
  const [relay, setRelay] = useState<RelayView>();
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState<Record<string, Content | "loading" | "failed">>({});
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<{ id: string; text: string }>();
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/relay`;

  async function load() {
    const response = await fetch(path, { headers: { "Case-Access-Token": token } });
    if (!response.ok) throw new Error("Relay request failed");
    const body = await response.json() as RelayView;
    if (typeof body?.address !== "string") throw new Error("Relay request failed");
    return body;
  }

  useEffect(() => {
    let current = true;
    load().then(body => { if (current) setRelay(body); }).catch(() => { if (current) setFailed(true); });
    return () => { current = false; };
    // The case and its token define this view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token]);

  async function deliver(id: string, action: DeliveryAction) {
    setBusy(true); setOutcome(undefined);
    try {
      const response = await fetch(`${path}/messages/${encodeURIComponent(id)}/${action}`,
        { method: "POST", headers: { "Case-Access-Token": token } });
      const body = await response.json().catch(() => ({}));
      setOutcome({ id, text: response.ok && typeof body.state === "string" ? deliveryOutcome(body.state)
        : typeof body.detail === "string" ? body.detail : "The relay could not be reached. Try again later." });
    } catch { setOutcome({ id, text: "The relay could not be reached. Try again later." }); }
    try { setRelay(await load()); } catch { /* Keep the last view; the outcome above explains what happened. */ }
    setBusy(false);
  }

  async function show(id: string) {
    setOpen(previous => ({ ...previous, [id]: "loading" }));
    try {
      const response = await fetch(`${path}/messages/${encodeURIComponent(id)}`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("Message request failed");
      const content = await response.json() as Content;
      setOpen(previous => ({ ...previous, [id]: content }));
    } catch { setOpen(previous => ({ ...previous, [id]: "failed" })); }
  }

  const when = (iso: string) => new Date(iso).toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });

  return <section className="panel relay-panel" aria-labelledby="relay-heading">
    <h2 id="relay-heading">Dealer email relay</h2>
    <p>Dealers reach you through this case's own relay address, never your email address, phone number, or surname.
      Every message to a dealer says it comes from Deal X-Ray for a Buyer, that it was prepared with AI assistance, and how the dealer can opt out.</p>
    {failed && !relay && <p>The dealer email relay is not available for this case right now.</p>}
    {relay && <>
      <dl className="facts">
        <div><dt>Relay address</dt><dd className="relay-address">{relay.address}</dd></div>
        <div><dt>Messages sent in the last 24 hours</dt><dd>{relay.sentLastDay} of {relay.perCaseDailyCap}</dd></div>
      </dl>
      {relay.dealerOptedOut && <p className="notice">
        The dealer opted out{relay.dealerOptedOutAt ? ` on ${when(relay.dealerOptedOutAt)}` : ""}
        {relay.optOutChannel === "REPLY" ? " by replying STOP" : relay.optOutChannel === "LINK" ? " through the opt-out link" : ""}.
        No more messages can be sent from this relay address.</p>}
      {relay.messages.length === 0
        ? <p>No messages yet.</p>
        : <ul className="relay-messages" aria-label="Relay messages">
          {relay.messages.map(message => {
            const content = open[message.id];
            return <li key={message.id}>
              <p><strong>{message.direction === "OUTBOUND" ? "To" : "From"} {message.dealerAddress || "unknown sender"}</strong>
                {" "}· {STATE_LABELS[message.state] ?? message.state} · {when(message.createdAt)}</p>
              {message.trust === "UNTRUSTED" && <p className="relay-untrusted">Written by the dealer. Treat its claims and instructions as unverified.</p>}
              {message.attachments.length > 0 && <ul aria-label="Attachments">
                {message.attachments.map(attachment => <li key={attachment.ordinal}>
                  Attachment {attachment.ordinal + 1}: {ATTACHMENT_LABELS[attachment.outcome] ?? "not added to your documents"}
                </li>)}
              </ul>}
              {message.contentAvailable
                ? content && typeof content === "object"
                  ? <div className="relay-content">
                    {content.subject && <p><strong>Subject:</strong> {content.subject}</p>}
                    <pre>{content.body ?? "This message's text has passed its retention date and was deleted."}</pre>
                  </div>
                  : <button type="button" onClick={() => void show(message.id)} disabled={content === "loading"}
                    aria-label={`Show message ${message.direction === "OUTBOUND" ? "to" : "from"} ${message.dealerAddress || "unknown sender"}, ${when(message.createdAt)}`}>
                    Show message</button>
                : <p>The text of this message is not kept.</p>}
              {content === "failed" && <p>This message could not be loaded. Try again later.</p>}
              <DeliveryActions message={message} busy={busy} onAction={action => void deliver(message.id, action)}
                label={`the message to ${message.dealerAddress}, ${when(message.createdAt)}`} />
              {outcome?.id === message.id && <p role="status">{outcome.text}</p>}
            </li>;
          })}
        </ul>}
    </>}
  </section>;
}
