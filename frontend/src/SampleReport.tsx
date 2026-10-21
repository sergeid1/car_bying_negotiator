import { useEffect, useState } from "react";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

/** `GET /api/v1/sample-report`: a report from a synthetic case with identifying fields set to null. */
type SampleReport = {
  label: string;
  source: string;
  redaction: { method: string; version: string; removed: string[] };
  report: CoreReport;
};

type Copy = Messages["paperwork"]["sampleReport"];

function redactionMethodName(method: string, t: Copy): string {
  return (t.redactionMethods as Record<string, string>)[method] ?? method;
}

function removedName(code: string, t: Copy) {
  const named = (t.removedFields as Record<string, string>)[code];
  if (named) return named;
  const text = code.toLowerCase().replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The example report's own page, reached from the header's Example report tab. */
export const exampleReportLink = "?page=example-report";

/** Whether the address names the example report; the older `#sample-report` links still arrive here. */
export function requestedExampleReport(): boolean {
  return new URL(window.location.href).searchParams.get("page") === "example-report" || window.location.hash === "#sample-report";
}

/** Reachable before any case exists: the Buyer can see what the product returns before uploading. */
export function ExampleReportPage() {
  const { m: { paperwork: { sampleReport: t } }, fill, fillNodes } = useMessages();
  const [sample, setSample] = useState<SampleReport>();
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const previous = document.title;
    document.title = t.pageTitle;
    void load();
    return () => { document.title = previous; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function load() {
    setLoading(true); setFailed(false);
    try {
      const response = await fetch("/api/v1/sample-report");
      if (!response.ok) throw new Error();
      const body = await response.json() as SampleReport;
      if (!body?.report?.analysis?.materialAmounts) throw new Error();
      setSample(body);
    } catch {
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }

  return <article className="panel sample-report" id="sample-report" aria-labelledby="sample-report-heading">
    <p className="step">{t.step}</p>
    <h1 id="sample-report-heading">{t.heading}</h1>
    <p className="lede">{t.lede}</p>
    {loading && <p role="status">{t.loading}</p>}
    {failed && <>
      <p role="alert">{t.failed}</p>
      <button type="button" className="btn-secondary" onClick={() => void load()}>{t.retry}</button>
    </>}
    {sample && <div className="stack">
      <div className="notice sample-about" role="note" aria-label={t.about}>
        <p>{fillNodes(sample.source === "SYNTHETIC_CASE" ? t.aboutSynthetic : t.aboutOther, { label: <strong>{t.exampleLabel}</strong> })}</p>
        <p>{fill(t.redaction, { method: redactionMethodName(sample.redaction.method, t), version: sample.redaction.version })}</p>
        {sample.redaction.removed.length > 0 && <>
          <p>{t.removed}</p>
          <ul>{sample.redaction.removed.map(code => <li key={code}>{removedName(code, t)}</li>)}</ul>
        </>}
      </div>
      <CoreDealReport report={sample.report} redacted />
    </div>}
    <div className="actions">
      <a className="button-link" href="./#start">{t.checkOwn}</a>
    </div>
  </article>;
}
