import { FindingFeedback } from "./PilotFeedback";
import { useEffect, useId, useState, type FormEvent } from "react";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";
import { DealerReadingCopy } from "./DealerReadingCopy";
import { serverText } from "./i18n/findings";

export type NegotiationVersion = {
  reportVersion: number; generatedAt: string; sourceReport: string;
  language: {
    policyVersion: string; scope: string; draft: string;
    items: { sourcePointer: string; sourceValue: string; explanation: string; question: string }[];
  };
};

export function NegotiationLoader({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
  const { m: { messages: { negotiationDraft: t } } } = useMessages();
  const [version, setVersion] = useState<NegotiationVersion>();
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setVersion(undefined); setError(false);
    fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/reports/${reportVersion}/negotiation`, {
      headers: { "Case-Access-Token": token }, signal: controller.signal,
    }).then(async response => {
      if (!response.ok) throw new Error("draft-unavailable");
      const result = await response.json();
      if (!controller.signal.aborted) setVersion(result);
    }).catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, [caseId, token, reportVersion, attempt]);
  if (error) return <section className="panel"><p role="alert">{t.loadFailed}</p><button className="btn-secondary" onClick={() => setAttempt(attempt + 1)}>{t.retry}</button></section>;
  if (!version) return <p role="status">{t.loading}</p>;
  return <NegotiationDraft key={`${caseId}-${reportVersion}`} version={version} credentials={{ caseId, token }} />;
}

export function NegotiationDraft({ version, credentials }: { version: NegotiationVersion; credentials?: { caseId: string; token: string } }) {
  const { m: { messages: { negotiationDraft: t } }, fill, fillNodes, language } = useMessages();
  const [draft, setDraft] = useState(version.language.draft);
  const [status, setStatus] = useState("");
  async function copy() {
    try { await navigator.clipboard.writeText(draft); setStatus(t.copied); }
    catch { setStatus(t.copyUnavailable); }
  }
  return <section className="panel" aria-labelledby="negotiation-heading">
    <h3 id="negotiation-heading">{t.heading}</h3>
    <p>{fill(t.basedOn, { version: String(version.reportVersion), savedAt: version.generatedAt })}</p>
    <ol>{version.language.items.map(item => <li key={item.sourcePointer}>
      <p>{serverText(language, item.explanation)}</p><p>{serverText(language, item.question)}</p>
      <details><summary>{fill(t.supportingFinding, { finding: item.sourcePointer.replace("/analysis/", "") })}</summary>
        <p>{fillNodes(t.reportSource, { version: String(version.reportVersion), pointer: <code>{item.sourcePointer}</code> })}</p><pre>{item.sourceValue}</pre>
      </details>
      {credentials && <FindingFeedback {...credentials} reportVersion={version.reportVersion} findingPointer={item.sourcePointer} />}
    </li>)}</ol>
    <p>{serverText(language, version.language.scope)}</p>
    <label htmlFor="negotiation-draft">{t.yourDraft}</label>
    <DealerReadingCopy english={draft} />
    <textarea lang="en" id="negotiation-draft" rows={12} value={draft} onChange={event => { setDraft(event.target.value); setStatus(""); }} />
    <p>{t.editsNotVerified}</p>
    <div className="actions">
      <button onClick={copy}>{t.copy}</button>
      <a download={`negotiation-report-${version.reportVersion}.txt`} href={`data:text/plain;charset=utf-8,${encodeURIComponent(draft)}`}>{t.download}</a>
    </div>
    <p role="status">{status}</p>
    {credentials && <DealerReplyAssistant {...credentials} reportVersion={version.reportVersion} />}
  </section>;
}

export type ReplyStatement = { kind: "OPENING" | "FACT" | "PROPOSAL" | "QUESTION" | "CLOSING"; text: string; sourcePointer: string | null; sourceValue: string | null };
export type ReplySuggestion = {
  policyVersion: string; path: "MODEL" | "TEMPLATE"; fallbackReason: string | null; reportVersion: number;
  dealerOfferId: string | null; dealerOfferVersion: number | null; statements: ReplyStatement[]; draft: string;
  omittedStatements: number; scope: string;
  proposal: { currency: string; sellingPriceMinor: number; ceilingMinor: number; sellingWalkAwayMinor: number; outTheDoorWalkAwayMinor: number | null } | null;
  provenance: { modelId: string; promptVersion: string; schemaVersion: string } | null;
};

const MAX_DEALER_RESPONSE = 4000;
const dollars = (minor: number) => format.money(minor);

function producerNote(reply: ReplySuggestion, t: Messages["messages"]["dealerReplyAssistant"]) {
  if (reply.path === "MODEL") return t.modelNote;
  return reply.fallbackReason === "MODEL_PATH_OFF" ? t.templateModelOff
    : reply.fallbackReason?.startsWith("PROVIDER_") || reply.fallbackReason === "CONTRACT_FAILURE" ? t.templateModelUnavailable
    : t.templateSetAside;
}

/** Drafts a reply to one Dealer answer. The Buyer edits and sends it; nothing here contacts the Dealer. */
export function DealerReplyAssistant({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
  const { m: { messages: { dealerReplyAssistant: t } }, fill, fillNodes, language } = useMessages();
  const id = useId();
  const [dealerResponse, setDealerResponse] = useState("");
  const [reply, setReply] = useState<ReplySuggestion>();
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  async function draft(event: FormEvent) {
    event.preventDefault();
    setBusy(true); setError(""); setStatus("");
    try {
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/reports/${reportVersion}/negotiation/replies`, {
        method: "POST", headers: { "Content-Type": "application/json", "Case-Access-Token": token },
        body: JSON.stringify({ dealerResponse, ...(language === "es" ? { language } : {}) }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => undefined);
        throw new Error(response.status === 400 && problem?.detail ? problem.detail : t.unavailable);
      }
      const result: ReplySuggestion = await response.json();
      setReply(result); setText(result.draft);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(text); setStatus(t.copied); }
    catch { setStatus(t.copyUnavailable); }
  }
  const sources = reply?.statements.filter(statement => statement.kind === "FACT") ?? [];
  return <section className="panel" aria-labelledby={`${id}-heading`}>
    <h4 id={`${id}-heading`}>{t.heading}</h4>
    <form onSubmit={draft}>
      <label htmlFor={`${id}-dealer`}>{t.dealerResponse}</label>
      <textarea id={`${id}-dealer`} rows={5} maxLength={MAX_DEALER_RESPONSE} value={dealerResponse} required
        aria-describedby={`${id}-dealer-help`} onChange={event => setDealerResponse(event.target.value)} />
      <p id={`${id}-dealer-help`}>{t.dealerResponseHelp}</p>
      <button type="submit" disabled={busy || !dealerResponse.trim()}>{busy ? t.drafting : t.draft}</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {reply && <>
      <p><span className={reply.path === "MODEL" ? "badge" : "badge badge-warn"}>{reply.path === "MODEL" ? t.generated : t.template}</span></p>
      <p>{producerNote(reply, t)}</p>
      {reply.omittedStatements > 0 && <p>{fill(t.omitted, { count: reply.omittedStatements })}</p>}
      {reply.proposal && <p>{reply.proposal.outTheDoorWalkAwayMinor !== null
        ? fill(t.proposalWithOutTheDoor, { price: dollars(reply.proposal.sellingPriceMinor),
          limit: dollars(reply.proposal.sellingWalkAwayMinor), outTheDoorLimit: dollars(reply.proposal.outTheDoorWalkAwayMinor) })
        : fill(t.proposal, { price: dollars(reply.proposal.sellingPriceMinor), limit: dollars(reply.proposal.sellingWalkAwayMinor) })}</p>}
      {sources.length > 0 && <details><summary>{t.sources}</summary>
        <ul>{sources.map(source => <li key={source.sourcePointer}>{serverText(language, source.text)} <span>{fillNodes(t.reportSource, { version: String(reply.reportVersion), pointer: <code>{source.sourcePointer}</code> })}</span></li>)}</ul>
      </details>}
      <label htmlFor={`${id}-reply`}>{t.yourReply}</label>
      <DealerReadingCopy english={text} />
      <textarea lang="en" id={`${id}-reply`} rows={10} value={text} onChange={event => { setText(event.target.value); setStatus(""); }} />
      <p>{t.youSend}</p>
      <p>{serverText(language, reply.scope)}</p>
      <div className="actions">
        <button onClick={copy}>{t.copy}</button>
        <a download={`dealer-reply-report-${reply.reportVersion}.txt`} href={`data:text/plain;charset=utf-8,${encodeURIComponent(text)}`}>{t.download}</a>
      </div>
      <p role="status">{status}</p>
    </>}
  </section>;
}
