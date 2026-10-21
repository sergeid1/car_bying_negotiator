import { useEffect, useId, useState } from "react";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

export type ExplanationSegment = { text: string; amountId: string | null; amountIndex: number | null; precision: string | null };
export type ExplanationSource = { id: string; sourcePointer: string; sourceValue: string | null };
export type ExplanationStatement = { text: string; segments: ExplanationSegment[]; sources: ExplanationSource[] };
export type ReportExplanationPayload = {
  policyVersion: string; path: "MODEL" | "TEMPLATE"; fallbackReason: string | null; reportVersion: number;
  statements: ExplanationStatement[]; scope: string;
  provenance: { modelId: string; promptVersion: string; schemaVersion: string } | null;
};

function producerNote(explanation: ReportExplanationPayload, t: Messages["paperwork"]["reportExplanation"]) {
  if (explanation.path === "MODEL") return t.modelNote;
  return explanation.fallbackReason === "MODEL_PATH_OFF" ? t.switchedOff
    : explanation.fallbackReason?.startsWith("PROVIDER_") || explanation.fallbackReason === "CONTRACT_FAILURE" ? t.unavailable
    : t.setAside;
}

/** Loads the checked explanation of one saved report. It is read-only and changes nothing in the report. */
export function ReportExplanationLoader({ caseId, token, reportVersion }: { caseId: string; token: string; reportVersion: number }) {
  const { m: { paperwork: { reportExplanation: t } }, language } = useMessages();
  const [explanation, setExplanation] = useState<ReportExplanationPayload>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    setExplanation(undefined); setFailed(false);
    fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/reports/${reportVersion}/explanation${language === "es" ? "?language=es" : ""}`, {
      headers: { "Case-Access-Token": token }, signal: controller.signal,
    }).then(async response => {
      if (!response.ok) throw new Error("explanation-unavailable");
      const result = await response.json();
      if (!Array.isArray(result?.statements)) throw new Error("explanation-unavailable");
      if (!controller.signal.aborted) setExplanation(result);
    }).catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, [caseId, token, reportVersion, language]);
  if (failed) return <p className="panel" role="status">{t.loadFailed}</p>;
  if (!explanation) return <p role="status">{t.loading}</p>;
  return <ReportExplanationView explanation={explanation} />;
}

export function ReportExplanationView({ explanation }: { explanation: ReportExplanationPayload }) {
  const { m: { paperwork: { reportExplanation: t } }, fill, fillNodes } = useMessages();
  const id = useId();
  return <section className="panel report-explanation" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>{t.heading}</h3>
    <p><span className={explanation.path === "MODEL" ? "badge" : "badge badge-warn"}>{explanation.path === "MODEL" ? t.generated : t.template}</span></p>
    <p>{producerNote(explanation, t)}</p>
    <ul>{explanation.statements.map((statement, index) => <li key={index}>
      <p>{statement.segments.map((segment, part) => segment.amountIndex === null
        ? <span key={part}>{segment.text}</span>
        : <a key={part} href={`#amount-evidence-${segment.amountIndex}`}>{segment.text}</a>)}</p>
      <details><summary>{fill(t.sources, { count: statement.sources.length, number: index + 1 })}</summary>
        <ul>{statement.sources.map(source => <li key={source.id}>{fillNodes(t.source, { version: explanation.reportVersion, pointer: <code>{source.sourcePointer}</code> })}{source.sourceValue !== null && <> · <code>{source.sourceValue}</code></>}</li>)}</ul>
      </details>
    </li>)}</ul>
    <p>{explanation.scope}</p>
    {explanation.provenance && <p><small>{fill(t.model, { model: explanation.provenance.modelId, prompt: explanation.provenance.promptVersion, schema: explanation.provenance.schemaVersion })}</small></p>}
  </section>;
}
