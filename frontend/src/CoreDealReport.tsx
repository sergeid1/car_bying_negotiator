import { OutcomeFeedback } from "./PilotFeedback";
import { CaseDownloads } from "./CasePortability";
import { NegotiationLoader } from "./NegotiationDraft";
import { RevisedOfferReview, type DealReview } from "./RevisedOfferReview";
import { OutsideFinancing } from "./OutsideFinancing";
import { DealerFinancingForm, DealerFinancingResult, type FinancingResult } from "./DealerFinancing";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
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
  return <section className="stack" aria-label="Core deal report">
    {report && <CoreDealReport report={report} caseId={caseId} token={token} />}
    <DealerFinancingForm key={`dealer-${caseId}-${token}`} caseId={caseId} token={token} />
    <OutsideFinancing key={`outside-${caseId}-${token}`} caseId={caseId} token={token} />
    <div className="panel">
      <div className="actions">
        <button onClick={() => load(true)} disabled={busy}>Generate core deal report</button>
        <button className="btn-secondary" onClick={() => load(false)} disabled={busy}>Open latest saved report</button>
      </div>
      {busy && <p role="status">Loading report…</p>}
      {error && <p role="alert">{error}</p>}
    </div>
    {report && <>
      <OutcomeFeedback key={`feedback-${caseId}-${token}`} caseId={caseId} token={token} />
      <CaseDownloads key={`download-${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />
      <NegotiationLoader key={`${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />
    </>}
  </section>;
}

export function CoreDealReport({ report, caseId, token }: { report: CoreReport; caseId?: string; token?: string }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, [report]);
  const { analysis } = report;
  const total = analysis.materialAmounts.find(amount => amount.label === "Out-the-door total" || amount.pointer === "/result/outTheDoorTotal");
  const reasons = analysis.topReasons.slice(0, 3);
  const [questions, setQuestions] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  useEffect(() => { setQuestions(report.analysis.topReasons.slice(0, 3).map(reason => `Could you explain this finding: ${reason}`).join("\n")); setCopyStatus(""); }, [report]);
  const money = (amount: CoreReport["analysis"]["materialAmounts"][number] | undefined) => !amount || amount.minorUnits == null || amount.precision === "UNAVAILABLE" ? "Unknown" : new Intl.NumberFormat("en-US", { style: "currency", currency: amount.currency }).format(amount.minorUnits / 100);
  const totalIndex = total ? analysis.materialAmounts.indexOf(total) : -1;
  const amountLabel = (label: string) => label.replace("/payload/plan/", "Approved ")
    .replace("/result/", "").replaceAll("/", " · ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/Minor$/, "");
  return <article className="report" aria-labelledby="core-report-heading">
    <section className="outcome report-leading" aria-label="Total, findings and next action">
      <h2 id="core-report-heading" tabIndex={-1} ref={heading}>Deal Analysis Report</h2>
      <h3>Out-the-door total</h3>
      <p className="report-leading-total">{total ? <a href={`#amount-evidence-${totalIndex}`}>{money(total)}</a> : "Unknown"} <small>{total?.precision.toLowerCase() ?? "unavailable"}</small></p>
      <h3>Three material findings</h3>
      <ol>{reasons.map((reason, index) => <li key={index}><a href={`#finding-${index}`}>{reason}</a></li>)}</ol>
      {reasons.length < 3 && <p>{3 - reasons.length} further findings: unknown in this saved report.</p>}
      <h3>Next action</h3>
      <p className="next-action">{analysis.nextAction}</p>
    </section>
    <section className="panel" aria-labelledby="core-questions">
      <h3 id="core-questions">Questions to ask the dealer</h3>
      <p>Edit your questions before copying. Edits stay on this screen; the saved report and PDF keep the original findings.</p>
      <label htmlFor="report-questions">Your questions<textarea id="report-questions" rows={4} value={questions} onChange={event => setQuestions(event.target.value)} /></label>
      <button type="button" onClick={async () => { try { await navigator.clipboard.writeText(questions); setCopyStatus("Questions copied."); } catch { setCopyStatus("Copy unavailable. Select the questions and copy them manually."); } }}>Copy questions</button>
      {copyStatus && <p role="status">{copyStatus}</p>}
      <ol>{reasons.map((reason, index) => <li id={`finding-${index}`} tabIndex={-1} key={index}>{reason} <a href="#core-evidence">Frozen evidence</a> · <code>/analysis/topReasons/{index}</code></li>)}</ol>
    </section>
    {caseId && token && <ReportTotalMeasure key={`${caseId}-${token}-${report.version}`} report={report} caseId={caseId} token={token} />}
    <section className="panel"><h3>Review and rating</h3><div className="status-row"><strong>{analysis.reviewLabel}</strong><span>{analysis.dealRating.replaceAll("_", " ")}</span></div><p>{analysis.ratingBasis}</p></section>
    <p>Version {report.version} · {report.status} · As of <time dateTime={report.asOf}>{report.asOf}</time></p>
    {report.status === "PARTIAL" && <p className="notice">Warning: this report is partial. One or more analysis areas are unavailable.</p>}
    <nav aria-label="Report sections">{analysis.dealReview && <><a href="#revised-offer-review">Revised offer comparison</a> · </>}<a href="#core-amounts">Amounts</a> · {analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE" && <><a href="#core-financing">Financing</a> · </>} <a href="#core-evidence">Evidence and calculations</a> · <a href="#core-scope">Scope and policies</a></nav>
    {analysis.dealReview && <RevisedOfferReview review={analysis.dealReview} />}
    <section className="panel" id="core-amounts" tabIndex={-1}>
      <h3>Amounts and sources</h3>
      <p>Market analysis: {analysis.marketAvailability}. Cash economics: {analysis.cashAvailability}.</p>
      <dl className="money-list">
        {analysis.materialAmounts.map((amount, index) => <div key={index}>
          <dt>{amountLabel(amount.label)}<small>{amount.precision.toLowerCase()} · <a href={`#amount-evidence-${index}`} title={`${amount.source}${amount.pointer}`}>Source and calculation {index + 1}</a></small></dt>
          <dd>{money(amount)}</dd>
        </div>)}
      </dl>
    </section>
    {analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE" && <DealerFinancingResult result={analysis.financing ?? null} availability={analysis.financingAvailability} terms={analysis.financingTerms} />}
    <section className="panel" id="core-evidence" tabIndex={-1}>
      <h3>Frozen evidence and reproducible calculations</h3>
      {analysis.materialAmounts.map((amount, index) => <section key={index} id={`amount-evidence-${index}`} tabIndex={-1}>
        <h4>{amountLabel(amount.label)} — source and calculation</h4>
        <p>{amount.source}{amount.pointer} · {amount.precision.toLowerCase()}</p>
        <a href={`#evidence-${amount.source}`}>Open full frozen source</a>
        <pre>{JSON.stringify(amount.pointer.split("/").filter(Boolean).reduce<unknown>((value, key) => value != null && typeof value === "object" ? (value as Record<string, unknown>)[key] : undefined, report.evidence[amount.source]) ?? "Unknown", null, 2)}</pre>
      </section>)}
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
