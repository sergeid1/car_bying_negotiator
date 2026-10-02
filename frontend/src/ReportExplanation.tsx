import { useEffect, useId, useState } from "react";

export type ExplanationSegment = { text: string; amountId: string | null; amountIndex: number | null; precision: string | null };
export type ExplanationSource = { id: string; sourcePointer: string; sourceValue: string | null };
export type ExplanationStatement = { text: string; segments: ExplanationSegment[]; sources: ExplanationSource[] };
export type ReportExplanationPayload = {
  policyVersion: string; path: "MODEL" | "TEMPLATE"; fallbackReason: string | null; reportVersion: number;
  statements: ExplanationStatement[]; scope: string;
  provenance: { modelId: string; promptVersion: string; schemaVersion: string } | null;
};

function producerNote(explanation: ReportExplanationPayload) {
  if (explanation.path === "MODEL") return "Generated text, shown only because every sentence was checked in code against this report's recorded findings. Each figure is inserted from the report itself.";
  const reason = explanation.fallbackReason === "MODEL_PATH_OFF" ? "Generated explanations are switched off."
    : explanation.fallbackReason?.startsWith("PROVIDER_") || explanation.fallbackReason === "CONTRACT_FAILURE" ? "A generated explanation was unavailable."
    : "A generated explanation was set aside because a sentence could not be matched to this report's recorded findings.";
  return `${reason} This is the fixed template wording for this report's findings.`;
}

/** Loads the checked explanation of one saved report. It is read-only and changes nothing in the report. */
export function ReportExplanationLoader({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
  const [explanation, setExplanation] = useState<ReportExplanationPayload>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setExplanation(undefined); setFailed(false);
    fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/reports/${reportVersion}/explanation`, {
      headers: { "Case-Access-Token": token }, signal: controller.signal,
    }).then(async response => {
      if (!response.ok) throw new Error("Explanation unavailable");
      const result = await response.json();
      if (!Array.isArray(result?.statements)) throw new Error("Explanation unavailable");
      if (!controller.signal.aborted) setExplanation(result);
    }).catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, [caseId, token, reportVersion]);
  if (failed) return <p className="panel" role="status">The explanation of this report is unavailable. The findings and amounts below are unaffected.</p>;
  if (!explanation) return <p role="status">Loading the explanation of this report…</p>;
  return <ReportExplanationView explanation={explanation} />;
}

export function ReportExplanationView({ explanation }: { explanation: ReportExplanationPayload }) {
  const id = useId();
  return <section className="panel report-explanation" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>What the findings mean</h3>
    <p><span className={explanation.path === "MODEL" ? "badge" : "badge badge-warn"}>{explanation.path === "MODEL" ? "Generated explanation" : "Template explanation"}</span></p>
    <p>{producerNote(explanation)}</p>
    <ul>{explanation.statements.map((statement, index) => <li key={index}>
      <p>{statement.segments.map((segment, part) => segment.amountIndex === null
        ? <span key={part}>{segment.text}</span>
        : <a key={part} href={`#amount-evidence-${segment.amountIndex}`}>{segment.text}</a>)}</p>
      <details><summary>Recorded {statement.sources.length === 1 ? "source" : "sources"} for sentence {index + 1}</summary>
        <ul>{statement.sources.map(source => <li key={source.id}>Report {explanation.reportVersion} · <code>{source.sourcePointer}</code>{source.sourceValue !== null && <> · <code>{source.sourceValue}</code></>}</li>)}</ul>
      </details>
    </li>)}</ul>
    <p>{explanation.scope}</p>
    {explanation.provenance && <p><small>Model {explanation.provenance.modelId} · {explanation.provenance.promptVersion} · {explanation.provenance.schemaVersion}</small></p>}
  </section>;
}
