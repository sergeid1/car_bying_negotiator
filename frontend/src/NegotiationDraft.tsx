import { FindingFeedback } from "./PilotFeedback";
import { useEffect, useId, useState, type FormEvent } from "react";
import * as format from "./i18n/format";

export type NegotiationVersion = {
  reportVersion: number; generatedAt: string; sourceReport: string;
  language: {
    policyVersion: string; scope: string; draft: string;
    items: { sourcePointer: string; sourceValue: string; explanation: string; question: string }[];
  };
};

export function NegotiationLoader({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
  const [version, setVersion] = useState<NegotiationVersion>();
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setVersion(undefined); setError(false);
    fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/reports/${reportVersion}/negotiation`, {
      headers: { "Case-Access-Token": token }, signal: controller.signal,
    }).then(async response => {
      if (!response.ok) throw new Error("Draft unavailable");
      const result = await response.json();
      if (!controller.signal.aborted) setVersion(result);
    }).catch(() => { if (!controller.signal.aborted) setError(true); });
    return () => controller.abort();
  }, [caseId, token, reportVersion, attempt]);
  if (error) return <section className="panel"><p role="alert">Saved draft unavailable for this report. Older reports may need regeneration.</p><button className="btn-secondary" onClick={() => setAttempt(attempt + 1)}>Retry loading draft</button></section>;
  if (!version) return <p role="status">Loading saved negotiation draft…</p>;
  return <NegotiationDraft key={`${caseId}-${reportVersion}`} version={version} credentials={{ caseId, token }} />;
}

export function NegotiationDraft({ version, credentials }: { version: NegotiationVersion; credentials?: { caseId: string; token: string } }) {
  const [draft, setDraft] = useState(version.language.draft);
  const [status, setStatus] = useState("");
  async function copy() {
    try { await navigator.clipboard.writeText(draft); setStatus("Draft copied."); }
    catch { setStatus("Copy unavailable. Select the draft text to copy manually, or download it."); }
  }
  return <section className="panel" aria-labelledby="negotiation-heading">
    <h3 id="negotiation-heading">Explanations and negotiation draft</h3>
    <p>Based on report version {version.reportVersion}, saved {version.generatedAt}. Questions are listed in review order.</p>
    <ol>{version.language.items.map(item => <li key={item.sourcePointer}>
      <p>{item.explanation}</p><p>{item.question}</p>
      <details><summary>Supporting finding: {item.sourcePointer.replace("/analysis/", "")}</summary>
        <p>Report {version.reportVersion} · <code>{item.sourcePointer}</code></p><pre>{item.sourceValue}</pre>
      </details>
      {credentials && <FindingFeedback {...credentials} reportVersion={version.reportVersion} findingPointer={item.sourcePointer} />}
    </li>)}</ol>
    <p>{version.language.scope}</p>
    <label htmlFor="negotiation-draft">Your negotiation draft</label>
    <textarea id="negotiation-draft" rows={12} value={draft} onChange={event => { setDraft(event.target.value); setStatus(""); }} />
    <p>Buyer edits are not verified and stay in this editor until you leave or load another report. Copy or download to keep them.</p>
    <div className="actions">
      <button onClick={copy}>Copy draft</button>
      <a download={`negotiation-report-${version.reportVersion}.txt`} href={`data:text/plain;charset=utf-8,${encodeURIComponent(draft)}`}>Download draft</a>
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

function producerNote(reply: ReplySuggestion) {
  if (reply.path === "MODEL") return "Generated from the Dealer's response, then checked in code against this report's recorded figures and your approved limits.";
  const reason = reply.fallbackReason === "MODEL_PATH_OFF" ? "Generated replies are switched off."
    : reply.fallbackReason?.startsWith("PROVIDER_") || reply.fallbackReason === "CONTRACT_FAILURE" ? "A generated reply was unavailable."
    : "A generated reply was set aside because it did not pass the recorded-source or limit checks.";
  return `${reason} This reply comes from the fixed template, uses only this report's recorded figures, and does not read the Dealer's response.`;
}

/** Drafts a reply to one Dealer answer. The Buyer edits and sends it; nothing here contacts the Dealer. */
export function DealerReplyAssistant({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
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
        body: JSON.stringify({ dealerResponse }),
      });
      if (!response.ok) {
        const problem = await response.json().catch(() => undefined);
        throw new Error(response.status === 400 && problem?.detail ? problem.detail : "Reply suggestion unavailable. Try again.");
      }
      const result: ReplySuggestion = await response.json();
      setReply(result); setText(result.draft);
    } catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }
  async function copy() {
    try { await navigator.clipboard.writeText(text); setStatus("Reply copied."); }
    catch { setStatus("Copy unavailable. Select the reply text to copy manually, or download it."); }
  }
  const sources = reply?.statements.filter(statement => statement.kind === "FACT") ?? [];
  return <section className="panel" aria-labelledby={`${id}-heading`}>
    <h4 id={`${id}-heading`}>Reply to a Dealer response</h4>
    <form onSubmit={draft}>
      <label htmlFor={`${id}-dealer`}>Dealer's response</label>
      <textarea id={`${id}-dealer`} rows={5} maxLength={MAX_DEALER_RESPONSE} value={dealerResponse} required
        aria-describedby={`${id}-dealer-help`} onChange={event => setDealerResponse(event.target.value)} />
      <p id={`${id}-dealer-help`}>Paste what the Dealer said. It is used only to draft this reply and is not saved.</p>
      <button type="submit" disabled={busy || !dealerResponse.trim()}>{busy ? "Drafting reply…" : "Draft a reply"}</button>
    </form>
    {error && <p role="alert">{error}</p>}
    {reply && <>
      <p><span className={reply.path === "MODEL" ? "badge" : "badge badge-warn"}>{reply.path === "MODEL" ? "Generated reply" : "Template reply"}</span></p>
      <p>{producerNote(reply)}</p>
      {reply.omittedStatements > 0 && <p>{reply.omittedStatements} generated {reply.omittedStatements === 1 ? "statement was" : "statements were"} left out because {reply.omittedStatements === 1 ? "it" : "they"} could not be traced to a recorded source.</p>}
      {reply.proposal && <p>Counter selling price {dollars(reply.proposal.sellingPriceMinor)}, within your approved selling-price walk-away limit of {dollars(reply.proposal.sellingWalkAwayMinor)}{reply.proposal.outTheDoorWalkAwayMinor !== null ? ` and out-the-door limit of ${dollars(reply.proposal.outTheDoorWalkAwayMinor)}` : ""}.</p>}
      {sources.length > 0 && <details><summary>Recorded sources for this reply</summary>
        <ul>{sources.map(source => <li key={source.sourcePointer}>{source.text} <span>Report {reply.reportVersion} · <code>{source.sourcePointer}</code></span></li>)}</ul>
      </details>}
      <label htmlFor={`${id}-reply`}>Your reply</label>
      <textarea id={`${id}-reply`} rows={10} value={text} onChange={event => { setText(event.target.value); setStatus(""); }} />
      <p>You edit and send this reply yourself; the product does not contact the Dealer. Your edits are not verified.</p>
      <p>{reply.scope}</p>
      <div className="actions">
        <button onClick={copy}>Copy reply</button>
        <a download={`dealer-reply-report-${reply.reportVersion}.txt`} href={`data:text/plain;charset=utf-8,${encodeURIComponent(text)}`}>Download reply</a>
      </div>
      <p role="status">{status}</p>
    </>}
  </section>;
}
