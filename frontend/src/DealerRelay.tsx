import { useEffect, useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Attachment = { ordinal: number; documentId: string | null; outcome: string };
export type RelayMessage = {
  id: string; direction: "OUTBOUND" | "INBOUND"; trust: "PLATFORM" | "UNTRUSTED"; dealerAddress: string; state: string;
  createdAt: string; completedAt: string | null; contentAvailable: boolean; attachments: Attachment[]; sendPath?: string;
};
/** The case's private reply-intake address and its dealer messages. Nothing here sends anything. */
export type RelayView = { address: string; forwardingAvailable: boolean; messages: RelayMessage[] };
type Content = { subject: string | null; body: string | null };

export function DealerRelay({ caseId, token }: { caseId: string; token: string }) {
  const { m: { messages: { dealerRelay: t, common } }, fill } = useMessages();
  const stateLabels: Record<string, string> = t.states;
  const attachmentLabels: Record<string, string> = t.attachmentOutcomes;
  const [relay, setRelay] = useState<RelayView>();
  const [failed, setFailed] = useState(false);
  const [open, setOpen] = useState<Record<string, Content | "loading" | "failed">>({});
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/relay`;

  useEffect(() => {
    let current = true;
    fetch(path, { headers: { "Case-Access-Token": token } })
      .then(async response => {
        if (!response.ok) throw new Error("relay-unavailable");
        const body = await response.json() as RelayView;
        if (typeof body?.address !== "string" || !Array.isArray(body.messages)) throw new Error("relay-unavailable");
        if (current) setRelay(body);
      })
      .catch(() => { if (current) setFailed(true); });
    return () => { current = false; };
  }, [path, token]);

  async function show(id: string) {
    setOpen(previous => ({ ...previous, [id]: "loading" }));
    try {
      const response = await fetch(`${path}/messages/${encodeURIComponent(id)}`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("message-unavailable");
      const content = await response.json() as Content;
      setOpen(previous => ({ ...previous, [id]: content }));
    } catch { setOpen(previous => ({ ...previous, [id]: "failed" })); }
  }

  const when = (iso: string) => format.dateTime(iso, { dateStyle: "medium", timeStyle: "short" });

  return <section className="panel relay-panel" aria-labelledby="relay-heading">
    <h2 id="relay-heading">{t.heading}</h2>
    <p>{t.intro}</p>
    {failed && !relay && <p>{t.unavailable}</p>}
    {relay && <>
      <dl className="facts">
        <div><dt>{t.forwardHere}</dt><dd className="relay-address">{relay.address}</dd></div>
      </dl>
      <p>{t.privateAddress}</p>
      {!relay.forwardingAvailable && <p className="notice">{t.forwardingOff}</p>}
      {relay.messages.length === 0
        ? <p>{t.none}</p>
        : <ul className="relay-messages" aria-label={t.list}>
          {relay.messages.map(message => {
            const content = open[message.id];
            const address = message.dealerAddress || common.unknownSender;
            return <li key={message.id}>
              <p><strong>{fill(message.direction === "OUTBOUND" ? t.to : t.from, { address })}</strong>
                {" "}· {stateLabels[message.state] ?? message.state} · {when(message.createdAt)}
                {message.sendPath === "RELAY" && <> · {t.formerRelay}</>}</p>
              {message.trust === "UNTRUSTED" && <p className="relay-untrusted">{common.writtenByDealer}</p>}
              {message.attachments.length > 0 && <ul aria-label={t.attachments}>
                {message.attachments.map(attachment => <li key={attachment.ordinal}>
                  {fill(common.sourceOutcome, { source: fill(common.attachment, { number: attachment.ordinal + 1 }),
                    outcome: attachmentLabels[attachment.outcome] ?? t.attachmentNotAdded })}
                </li>)}
              </ul>}
              {message.contentAvailable
                ? content && typeof content === "object"
                  ? <div className="relay-content">
                    {content.subject && <p><strong>{common.subjectLabel}</strong> {content.subject}</p>}
                    <pre>{content.body ?? t.retentionDeleted}</pre>
                  </div>
                  : <button type="button" onClick={() => void show(message.id)} disabled={content === "loading"}
                    aria-label={fill(message.direction === "OUTBOUND" ? t.showMessageTo : t.showMessageFrom,
                      { address, when: when(message.createdAt) })}>
                    {t.showMessage}</button>
                : <p>{common.textNotKept}</p>}
              {content === "failed" && <p>{t.loadFailed}</p>}
            </li>;
          })}
        </ul>}
    </>}
  </section>;
}
