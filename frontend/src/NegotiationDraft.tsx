import { FindingFeedback } from "./PilotFeedback";
import { useEffect, useState } from "react";

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
  if (error) return <section className="panel"><p role="alert">Saved draft unavailable for this report. Older reports may need regeneration.</p><button onClick={() => setAttempt(attempt + 1)}>Retry loading draft</button></section>;
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
    <button onClick={copy}>Copy draft</button>{" "}
    <a download={`negotiation-report-${version.reportVersion}.txt`} href={`data:text/plain;charset=utf-8,${encodeURIComponent(draft)}`}>Download draft</a>
    <p role="status">{status}</p>
  </section>;
}
