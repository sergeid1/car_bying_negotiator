import { useEffect, useState } from "react";
import * as format from "./i18n/format";

type Attachment = { ordinal: number; documentId: string | null; outcome: string };
export type RelayMessage = {
  id: string; direction: "OUTBOUND" | "INBOUND"; trust: "PLATFORM" | "UNTRUSTED"; dealerAddress: string; state: string;
  createdAt: string; completedAt: string | null; contentAvailable: boolean; attachments: Attachment[]; sendPath?: string;
};
/** The case's private reply-intake address and its dealer messages. Nothing here sends anything. */
export type RelayView = { address: string; forwardingAvailable: boolean; messages: RelayMessage[] };
type Content = { subject: string | null; body: string | null };

const STATE_LABELS: Record<string, string> = {
  DELIVERY_UNKNOWN: "Delivery not confirmed", SENT: "Sent", DELIVERED: "Delivered", BOUNCED: "Bounced",
  FAILED: "Not sent", RECEIVED: "Received", REJECTED_MALWARE: "Blocked: failed the malware scan",
  HANDED_OFF: "Not sent yet", DISCARDED: "Discarded",
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
        if (!response.ok) throw new Error("Dealer messages request failed");
        const body = await response.json() as RelayView;
        if (typeof body?.address !== "string" || !Array.isArray(body.messages)) throw new Error("Dealer messages request failed");
        if (current) setRelay(body);
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

  const when = (iso: string) => format.dateTime(iso, { dateStyle: "medium", timeStyle: "short" });

  return <section className="panel relay-panel" aria-labelledby="relay-heading">
    <h2 id="relay-heading">Dealer messages</h2>
    <p>Every message for this case's dealers is listed here. You send approved messages from your own email. Dealer replies
      you add are treated as the dealer's claims: nothing in them is acted on until you review it.</p>
    {failed && !relay && <p>Dealer messages are not available for this case right now.</p>}
    {relay && <>
      <dl className="facts">
        <div><dt>Forward a dealer's email here</dt><dd className="relay-address">{relay.address}</dd></div>
      </dl>
      <p>This private address belongs to this case only. Keep it to yourself: give it to no dealer and send nothing else to it.
        It stops accepting mail when the case is closed or deleted.</p>
      {!relay.forwardingAvailable && <p className="notice">Forwarding is not switched on yet, so mail sent to this address does
        not arrive. Paste the dealer's email into the conversation instead.</p>}
      {relay.messages.length === 0
        ? <p>No messages yet.</p>
        : <ul className="relay-messages" aria-label="Dealer messages">
          {relay.messages.map(message => {
            const content = open[message.id];
            return <li key={message.id}>
              <p><strong>{message.direction === "OUTBOUND" ? "To" : "From"} {message.dealerAddress || "unknown sender"}</strong>
                {" "}· {STATE_LABELS[message.state] ?? message.state} · {when(message.createdAt)}
                {message.sendPath === "RELAY" && " · Sent through the former platform relay"}</p>
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
