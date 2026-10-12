import { useEffect, useState } from "react";
import { CoreDealReport, type CoreReport } from "./CoreDealReport";

/** `GET /api/v1/sample-report`: a report from a synthetic case with identifying fields set to null. */
type SampleReport = {
  label: string;
  source: string;
  redaction: { method: string; version: string; removed: string[] };
  report: CoreReport;
};

const REDACTION_METHODS: Record<string, string> = {
  FIELD_CATEGORIES: "named categories of fields are removed; generated wording and amounts are kept",
};

function redactionMethodName(method: string): string {
  return REDACTION_METHODS[method] ?? method;
}

const REMOVED: Record<string, string> = {
  VIN: "Vehicle identification number (VIN)",
  DEALER_IDENTITY: "Dealer name and identity",
  LISTING_URL: "Listing web address",
  BUYER_LOCATION: "Buyer location below the state",
  DOCUMENT_REFERENCES: "Document references",
  PAGE_REGIONS: "Document pages and regions",
  FREE_TEXT: "Free-text notes",
};

function removedName(code: string) {
  if (REMOVED[code]) return REMOVED[code];
  const text = code.toLowerCase().replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The example report's own page, reached from the header's Example report tab. */
export const exampleReportLink = "?page=example-report";
const TITLE = "Example report · otdog";

/** Whether the address names the example report; the older `#sample-report` links still arrive here. */
export function requestedExampleReport(): boolean {
  return new URL(window.location.href).searchParams.get("page") === "example-report" || window.location.hash === "#sample-report";
}

/** Reachable before any case exists: the Buyer can see what the product returns before uploading. */
export function ExampleReportPage() {
  const [sample, setSample] = useState<SampleReport>();
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const previous = document.title;
    document.title = TITLE;
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
    <p className="step">Before you upload</p>
    <h1 id="sample-report-heading">See an example report</h1>
    <p className="lede">An example Deal Analysis Report built from a synthetic case, so you can see what you receive before starting your own.</p>
    {loading && <p role="status">Loading the example report…</p>}
    {failed && <>
      <p role="alert">The example report is unavailable right now. Starting your own case is not affected.</p>
      <button type="button" className="btn-secondary" onClick={() => void load()}>Retry</button>
    </>}
    {sample && <div className="stack">
      <div className="notice sample-about" role="note" aria-label="About this example">
        <p><strong>Example report.</strong> {sample.source === "SYNTHETIC_CASE"
          ? "Built from a synthetic case, not a real Buyer's deal."
          : "Not a real Buyer's deal."} Its amounts illustrate the format and say nothing about your deal.</p>
        <p>Redaction method: {redactionMethodName(sample.redaction.method)} (version {sample.redaction.version}).</p>
        {sample.redaction.removed.length > 0 && <>
          <p>Removed from this example:</p>
          <ul>{sample.redaction.removed.map(code => <li key={code}>{removedName(code)}</li>)}</ul>
        </>}
      </div>
      <CoreDealReport report={sample.report} redacted />
    </div>}
    <div className="actions">
      <a className="button-link" href="./#start">Check my own deal</a>
    </div>
  </article>;
}
