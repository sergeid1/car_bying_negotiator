import { useEffect, useState } from "react";

type Attachment = { ordinal: number; documentId: string | null; outcome: string };
export type RelayMessage = {
  id: string; direction: "OUTBOUND" | "INBOUND"; trust: "PLATFORM" | "UNTRUSTED"; dealerAddress: string; state: string;
  createdAt: string; completedAt: string | null; contentAvailable: boolean; attachments: Attachment[];
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
const ATTACHMENT_LABELS: Record<string, string> = {
  ACCEPTED: "added to your documents", MALWARE_DETECTED: "blocked: failed the malware scan",
};

export function DealerRelay({ caseId, token }: { caseId: string; token: string }) {
  const [relay, setRelay] = useState<RelayView>();
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState<Record<string, Content | "loading" | "failed">>({});
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/relay`;

  useEffect(() => {
    let current = true;
    fetch(path, { headers: { "Case-Access-Token": token } })
      .then(async response => {
        if (!response.ok) throw new Error("Relay request failed");
        const body = await response.json() as RelayView;
        if (current && typeof body?.address === "string") setRelay(body); else if (current) setFailed(true);
      })
      .catch(() => { if (current) setFailed(true); });
    return () => { current = false; };
  }, [path, token]);

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
            </li>;
          })}
        </ul>}
    </>}
  </section>;
}
