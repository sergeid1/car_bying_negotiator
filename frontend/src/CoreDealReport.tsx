import { CaseDownloads } from "./CasePortability";
import { NegotiationLoader } from "./NegotiationDraft";
import { RevisedOfferReview, type DealReview } from "./RevisedOfferReview";
import { OutsideFinancing } from "./OutsideFinancing";
import { DealerFinancingForm, DealerFinancingResult, type FinancingResult } from "./DealerFinancing";
import { useEffect, useRef, useState } from "react";

export type CoreReport = {
  version: number; asOf: string; status: string;
  analysis: {
    dealReview?: DealReview;
    financingAvailability?: string; financing?: FinancingResult | null; financingTerms?: { aprPercent: number | null; termMonths: number | null } | null;
    reviewLabel: string; dealRating: string; ratingBasis: string; nextAction: string;
    topReasons: string[]; marketAvailability: string; cashAvailability: string;
    materialAmounts: { label: string; minorUnits: number | null; currency: string; precision: string; source: string; pointer: string }[];
    policyVersions: Record<string, string>; limitations: string[];
  };
  evidence: Record<string, unknown>;
  reproduction: Record<string, unknown>;
};

export function CoreReportLoader({ caseId, token }: { caseId: string; token: string }) {
  const [report, setReport] = useState<CoreReport>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState(() => crypto.randomUUID());
  async function load(generate: boolean) {
    setBusy(true); setError(undefined);
    const path = `/api/v1/cases/${encodeURIComponent(caseId)}`;
    try {
      if (generate) {
        const response = await fetch(`${path}/analyses`, { method: "POST", headers: {
          "Case-Access-Token": token, "Idempotency-Key": key,
        } });
        if (!response.ok) throw new Error("Analysis request failed. Retry with the same request key.");
      }
      const response = await fetch(`${path}/reports/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("Report unavailable. Check the case credentials and generate a report.");
      const payload = await response.json();
      if (!payload.analysis?.materialAmounts) throw new Error("This case has a synthetic report. Use a verified Buying Case for the core report.");
      setReport(payload);
      if (generate) setKey(crypto.randomUUID());
    } catch (caught) { setError(String(caught)); } finally { setBusy(false); }
  }
  return <section aria-label="Core deal report">
    <DealerFinancingForm key={`dealer-${caseId}-${token}`} caseId={caseId} token={token} />
    <OutsideFinancing key={`outside-${caseId}-${token}`} caseId={caseId} token={token} />
    <div className="panel">
      <button onClick={() => load(true)} disabled={busy}>Generate core deal report</button>
      <button onClick={() => load(false)} disabled={busy}>Open latest saved report</button>
      {busy && <p role="status">Loading report…</p>}
      {error && <p role="alert">{error}</p>}
    </div>
    {report && <>
      <CoreDealReport report={report} />
      <CaseDownloads key={`download-${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />
      <NegotiationLoader key={`${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />
    </>}
  </section>;
}

export function CoreDealReport({ report }: { report: CoreReport }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [report]);
  const { analysis } = report;
  const amountLabel = (label: string) => label.replace("/payload/plan/", "Approved ")
    .replace("/result/", "").replaceAll("/", " · ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/Minor$/, "");
  return <article className="report" aria-labelledby="core-report-heading">
    <section className="outcome">
      <h2 id="core-report-heading" tabIndex={-1} ref={heading}>Deal Analysis Report</h2>
      <div className="status-row"><strong>{analysis.reviewLabel}</strong><span>{analysis.dealRating.replaceAll("_", " ")}</span></div>
      <p>{analysis.ratingBasis}</p>
      <ol>{analysis.topReasons.map(reason => <li key={reason}>{reason}</li>)}</ol>
      <p className="next-action">Next action: {analysis.nextAction}</p>
    </section>
    <p>Version {report.version} · {report.status} · As of <time dateTime={report.asOf}>{report.asOf}</time></p>
    {report.status === "PARTIAL" && <p>Warning: this report is partial. One or more analysis areas are unavailable.</p>}
    <nav aria-label="Report sections">{analysis.dealReview && <><a href="#revised-offer-review">Revised offer comparison</a> · </>}<a href="#core-amounts">Amounts</a> · {analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE" && <><a href="#core-financing">Financing</a> · </>} <a href="#core-evidence">Evidence and calculations</a> · <a href="#core-scope">Scope and policies</a></nav>
    {analysis.dealReview && <RevisedOfferReview review={analysis.dealReview} />}
    <section className="panel" id="core-amounts" tabIndex={-1}>
      <h3>Amounts and sources</h3>
      <p>Market analysis: {analysis.marketAvailability}. Cash economics: {analysis.cashAvailability}.</p>
      <dl className="money-list">
        {analysis.materialAmounts.map((amount, index) => <div key={index}>
          <dt>{amountLabel(amount.label)}<small>{amount.precision.toLowerCase()} · <a href={`#evidence-${amount.source}`} title={`${amount.source}${amount.pointer}`}>Source and calculation {index + 1}</a></small></dt>
          <dd>{amount.minorUnits == null || amount.precision === "UNAVAILABLE" ? "Unavailable" : new Intl.NumberFormat("en-US", { style: "currency", currency: amount.currency }).format(amount.minorUnits / 100)}</dd>
        </div>)}
      </dl>
    </section>
    {analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE" && <DealerFinancingResult result={analysis.financing ?? null} availability={analysis.financingAvailability} terms={analysis.financingTerms} />}
    <section className="panel" id="core-evidence" tabIndex={-1}>
      <h3>Frozen evidence and reproducible calculations</h3>
      {Object.entries(report.evidence).map(([source, value]) => <section key={source} id={`evidence-${source}`} tabIndex={-1}>
        <h4>{source}</h4><pre>{value == null ? "Unavailable" : JSON.stringify(value, null, 2)}</pre>
      </section>)}
      <details><summary>Reproduction metadata</summary><pre>{JSON.stringify(report.reproduction, null, 2)}</pre></details>
    </section>
    <section className="limitations" id="core-scope" tabIndex={-1}>
      <h3>Scope and policies</h3>
      {analysis.limitations.map(text => <p key={text}>{text}</p>)}
      <dl>{Object.entries(analysis.policyVersions).map(([name, version]) => <div key={name}><dt>{name}</dt><dd>{version}</dd></div>)}</dl>
    </section>
  </article>;
}
