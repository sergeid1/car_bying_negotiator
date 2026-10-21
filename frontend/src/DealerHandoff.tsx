import { FormEvent, useContext, useEffect, useRef, useState } from "react";
import { AccountSignedInContext } from "./BuyerAccount";
import { asMailboxView, ConnectedMailbox } from "./MailboxConnection";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Handoff = { messageId: string; sendPath: string; state: string; dealerAddress: string; subject: string; body: string;
  handedOffAt: string; sentAt: string | null };

/** Email clients and browsers start truncating or refusing longer mailto: links around this length. */
export const MAILTO_LIMIT = 2000;

/** A mailto: link with the address, subject and body percent-encoded; line breaks become CRLF (RFC 6068). */
export function mailtoLink(address: string, subject: string, body: string) {
  const encode = (value: string) => encodeURIComponent(value).replace(/'/g, "%27");
  return `mailto:${encode(address).replace(/%40/g, "@")}?subject=${encode(subject)}&body=${encode(body.replace(/\r?\n/g, "\r\n"))}`;
}

function asHandoff(value: unknown, unavailable: string): Handoff {
  const v = value as Partial<Handoff> | null;
  if (!v || typeof v.state !== "string" || typeof v.dealerAddress !== "string" || typeof v.subject !== "string"
    || typeof v.body !== "string") throw new Error(unavailable);
  return v as Handoff;
}

/**
 * The Buyer's own send of an approved message: open it in their email app or copy it, send it from their
 * mailbox, then say so. A signed-in Buyer with a connected Gmail or Outlook account can instead send it from that
 * address with one button. The text is exactly what they approved; nothing is added to it.
 */
export function DealerHandoff({ caseId, token, messageId, onChanged }: {
  caseId: string; token: string; messageId: string; onChanged: () => Promise<void> | void;
}) {
  const { m: { messages: { dealerHandoff: t } }, fill, fillNodes } = useMessages();
  const signedIn = useContext(AccountSignedInContext);
  const [handoff, setHandoff] = useState<Handoff>();
  const [mailbox, setMailbox] = useState<ConnectedMailbox | null>(null);
  const [error, setError] = useState<string>();
  const [copied, setCopied] = useState("");
  const [busy, setBusy] = useState(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/handoffs/${encodeURIComponent(messageId)}`;
  async function request(url: string, method = "GET", account = false) {
    const response = await fetch(url, { method, headers: { "Case-Access-Token": token, "Content-Type": "application/json",
      ...(account ? { "Account-Request": "1" } : {}) }, ...(method === "POST" ? { body: "{}" } : {}) });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(typeof detail.detail === "string" ? detail.detail : t.updateFailed);
    }
    return asHandoff(await response.json(), t.unavailable);
  }
  useEffect(() => {
    let current = true;
    setHandoff(undefined); setError(undefined); setCopied("");
    request(path).then(value => { if (current) setHandoff(value); })
      .catch(() => { if (current) setError(t.unavailable); });
    return () => { current = false; };
    // The message and the case credential define this view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token]);
  useEffect(() => {
    let current = true;
    setMailbox(null);
    if (signedIn) fetch("/api/v1/account/mailbox").then(response => response.ok ? response.json() : null)
      .then(value => { if (current) setMailbox(asMailboxView(value)?.connection ?? null); })
      .catch(() => { /* Without the connection, the own-email-app path is still offered. */ });
    return () => { current = false; };
  }, [signedIn]);
  async function act(action: "sent" | "discard" | "mailbox-send" | "own-email-app") {
    setBusy(true); setError(undefined);
    try { setHandoff(await request(`${path}/${action}`, "POST", action === "mailbox-send")); await onChanged(); }
    catch (failure) {
      setError((failure as Error).message);
      // A refused send may have returned the message to the own-email-app path.
      if (action === "mailbox-send") await request(path).then(setHandoff).catch(() => undefined);
    }
    finally { setBusy(false); }
  }
  async function copy(what: "subject" | "message", text: string) {
    setCopied("");
    try { await navigator.clipboard.writeText(text); setCopied(what === "subject" ? t.subjectCopied : t.messageCopied); }
    catch { setCopied(what === "subject" ? t.subjectCopyUnavailable : t.messageCopyUnavailable); }
  }
  const confirmSent = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void act("sent"); };

  if (!handoff) return <div className="handoff">{error ? <p role="alert">{error}</p> : <p>{t.loading}</p>}</div>;
  const link = mailtoLink(handoff.dealerAddress, handoff.subject, handoff.body);
  const waiting = handoff.state === "HANDED_OFF";
  const unclear = handoff.state === "DELIVERY_UNKNOWN";
  const fromMailbox = handoff.sendPath === "CONNECTED_MAILBOX";
  const sentAt = handoff.sentAt ? format.dateTime(handoff.sentAt) : "";
  const sent = fromMailbox ? sentAt ? fill(t.sentFromMailboxOn, { when: sentAt }) : t.sentFromMailbox
    : sentAt ? fill(t.sentByYouOn, { when: sentAt }) : t.sentByYou;
  return <div className="handoff" aria-label={fill(t.group, { address: handoff.dealerAddress })} role="group">
    <p className="handoff-state" role="status">{waiting ? t.waiting
      : unclear ? t.unclear
      : handoff.state === "SENT" ? sent
      : t.discarded}</p>
    <dl className="facts handoff-fields">
      <div><dt>{t.to}</dt><dd>{handoff.dealerAddress}</dd></div>
      <div><dt>{t.subject}</dt><dd>{handoff.subject}</dd></div>
    </dl>
    <pre className="handoff-body">{handoff.body}</pre>
    {waiting && mailbox && <div className="handoff-mailbox">
      <p>{fillNodes(t.sendFromMailbox, {
        address: <strong className="mailbox-address">{mailbox.address}</strong>, provider: mailbox.providerLabel })}</p>
      <button type="button" disabled={busy} onClick={() => void act("mailbox-send")}>{t.sendFromMyEmail}</button>
    </div>}
    {unclear && <div className="handoff-mailbox">
      <p>{t.checkSentFolder}</p>
      <div className="handoff-actions">
        <button type="button" disabled={busy} onClick={() => void act("sent")}>{t.itWasSent}</button>
        <button type="button" disabled={busy} onClick={() => void act("mailbox-send")}>{t.sendAgain}</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => void act("own-email-app")}>
          {t.notSentUseApp}</button>
      </div>
    </div>}
    {waiting && <>
      <p>{t.exactlyAsApproved}</p>
      <div className="handoff-actions">
        {link.length <= MAILTO_LIMIT
          ? <a className="button-link" href={link}>{t.openInEmailApp}</a>
          : <p>{t.tooLong}</p>}
        <button type="button" onClick={() => void copy("subject", handoff.subject)}>{t.copySubject}</button>
        <button type="button" onClick={() => void copy("message", handoff.body)}>{t.copyMessage}</button>
      </div>
      <p className="handoff-copied" role="status" aria-live="polite">{copied}</p>
      <form onSubmit={confirmSent} className="handoff-actions">
        <button type="submit" disabled={busy}>{t.iSentIt}</button>
        <button type="button" disabled={busy} onClick={() => void act("discard")}>{t.discard}</button>
      </form>
    </>}
    {error && <p role="alert">{error}</p>}
  </div>;
}

/** The dealer's reply, pasted or attached by the Buyer, enters the Dealer Conversation as untrusted dealer content. */
export function DealerReplyPaste({ caseId, token, quoteRequestId, dealerAddress, onAdded }: {
  caseId: string; token: string; quoteRequestId: string; dealerAddress: string; onAdded: () => Promise<void> | void;
}) {
  const { m: { messages: { dealerReply: t } }, fill } = useMessages();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [added, setAdded] = useState("");
  const key = useRef(crypto.randomUUID());
  const id = `reply-${quoteRequestId}`;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const files = data.getAll("file").filter(file => file instanceof File && file.size > 0);
    data.delete("file");
    files.forEach(file => data.append("file", file));
    if (!String(data.get("text") ?? "").trim() && files.length === 0) {
      setError(t.empty); return;
    }
    setBusy(true); setError(undefined); setAdded("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/quote-requests/${encodeURIComponent(quoteRequestId)}/replies`,
        { method: "POST", headers: { "Case-Access-Token": token, "Idempotency-Key": key.current }, body: data });
      if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        throw new Error(typeof detail.detail === "string" ? detail.detail : t.addFailed);
      }
      const result = await response.json();
      key.current = crypto.randomUUID(); form.reset();
      setAdded(result?.outcome === "REJECTED_MALWARE" ? t.malware : t.added);
      await onAdded();
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  return <form className="handoff-reply" onSubmit={event => void submit(event)} aria-labelledby={`${id}-heading`}>
    <h4 id={`${id}-heading`}>{fill(t.heading, { address: dealerAddress })}</h4>
    <p>{t.intro}</p>
    <label>{t.subject}<input name="subject" maxLength={300} disabled={busy} autoComplete="off" /></label>
    <label>{t.reply}<textarea name="text" rows={6} disabled={busy} /></label>
    <label>{t.attachments}<input name="file" type="file" multiple disabled={busy} /></label>
    <button type="submit" disabled={busy}>{t.add}</button>
    <p role="status">{added}</p>
    {error && <p role="alert">{error}</p>}
  </form>;
}
