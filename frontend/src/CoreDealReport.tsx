import { OutcomeFeedback } from "./PilotFeedback";
import { CaseDownloads } from "./CasePortability";
import { NegotiationLoader } from "./NegotiationDraft";
import { RevisedOfferReview, type DealReview } from "./RevisedOfferReview";
import { OutsideFinancing } from "./OutsideFinancing";
import { DealerFinancingForm, DealerFinancingResult, type FinancingResult } from "./DealerFinancing";
import { ReportTotalMeasure } from "./ReportTotalMeasure";
import { Arithmetic, DocumentSourceView, type Calculation, type DocumentSource } from "./EvidenceSource";
import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { ReportExplanationLoader } from "./ReportExplanation";
import { AnimatedAmount } from "./AnimatedAmount";

export type MaterialAmount = {
  label: string; minorUnits: number | null; currency: string; precision: string; source: string; pointer: string;
  documentSource?: DocumentSource | null; sourceNote?: string | null; calculation?: Calculation | null;
};

export type CoreReport = {
  version: number; asOf: string; status: string;
  analysis: {
    dealReview?: DealReview;
    financingAvailability?: string; financing?: FinancingResult | null; financingTerms?: { aprPercent: number | null; termMonths: number | null } | null;
    reviewLabel: string; dealRating: string; ratingBasis: string; nextAction: string;
    topReasons: string[]; marketAvailability: string; cashAvailability: string;
    marketEvidence?: { status: string; paragraphs: string[] };
    materialAmounts: MaterialAmount[];
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
    {report && <CoreDealReport report={report} caseId={caseId} token={token}>
      <ReportSection id="core-feedback" title="Optional outcome feedback">
        <OutcomeFeedback key={`feedback-${caseId}-${token}`} caseId={caseId} token={token} headless />
      </ReportSection>
      <ReportSection id="core-downloads" title="Save a copy">
        <CaseDownloads key={`download-${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} headless />
      </ReportSection>
    </CoreDealReport>}
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
    {report && <NegotiationLoader key={`${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />}
  </section>;
}

/**
 * `redacted` marks an example report, whose removed fields are null and must read as removed.
 * `children` are further `ReportSection`s appended to the end of the collapsible list.
 */
export function CoreDealReport({ report, caseId, token, redacted, children }: { report: CoreReport; caseId?: string; token?: string; redacted?: boolean; children?: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const article = useRef<HTMLElement>(null);
  useEffect(() => { heading.current?.focus(); }, [report]);
  useSectionLinks(article, report);
  const { analysis } = report;
  const removed = "Removed from this example";
  const text = (value: string | null | undefined) => value ?? (redacted ? removed : "Unknown");
  const total = analysis.materialAmounts.find(amount => amount.label === "Out-the-door total" || amount.pointer === "/result/outTheDoorTotal");
  const reasons = analysis.topReasons.slice(0, 3);
  const [questions, setQuestions] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  useEffect(() => { setQuestions(report.analysis.topReasons.slice(0, 3).filter(reason => reason != null).map(reason => `Could you explain this finding: ${reason}`).join("\n")); setCopyStatus(""); }, [report]);
  const money = (amount: CoreReport["analysis"]["materialAmounts"][number] | undefined) => redacted && amount && amount.minorUnits == null && amount.precision !== "UNAVAILABLE" ? removed : !amount || amount.minorUnits == null || amount.precision === "UNAVAILABLE" ? "Unknown" : new Intl.NumberFormat("en-US", { style: "currency", currency: amount.currency }).format(amount.minorUnits / 100);
  const totalIndex = total ? analysis.materialAmounts.indexOf(total) : -1;
  const amountLabel = (label: string | null) => label == null ? removed : label.replace("/payload/plan/", "Approved ")
    .replace("/result/", "").replaceAll("/", " · ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/Minor$/, "");
  const showFinancing = analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE";
  return <article className="report" aria-labelledby="core-report-heading" ref={article}>
    <section className="outcome report-leading" aria-label="Total, findings and next action">
      <h2 id="core-report-heading" tabIndex={-1} ref={heading}>Deal Analysis Report</h2>
      <h3>Out-the-door total</h3>
      <p className="report-leading-total">{total ? <a href={`#amount-evidence-${totalIndex}`}>{total.minorUnits != null && total.precision !== "UNAVAILABLE"
        ? <AnimatedAmount minorUnits={total.minorUnits} currency={total.currency} text={money(total)} /> : money(total)}</a> : "Unknown"} <small>{total?.precision.toLowerCase() ?? "unavailable"}</small></p>
      <h3>Three material findings</h3>
      <ol>{reasons.map((reason, index) => <li key={index}><a href={`#finding-${index}`}>{text(reason)}</a></li>)}</ol>
      {reasons.length < 3 && <p>{3 - reasons.length} further findings: unknown in this saved report.</p>}
      <h3>Next action</h3>
      <p className="next-action">{text(analysis.nextAction)}</p>
    </section>
    {caseId && token && <ReportExplanationLoader key={`${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />}
    <section className="panel" aria-labelledby="core-questions">
      <h3 id="core-questions">Questions to ask the dealer</h3>
      <p>Edit your questions before copying. Edits stay on this screen; the saved report and PDF keep the original findings.</p>
      <label htmlFor="report-questions">Your questions<textarea id="report-questions" rows={4} value={questions} onChange={event => setQuestions(event.target.value)} /></label>
      <button type="button" onClick={async () => { try { await navigator.clipboard.writeText(questions); setCopyStatus("Questions copied."); } catch { setCopyStatus("Copy unavailable. Select the questions and copy them manually."); } }}>Copy questions</button>
      {copyStatus && <p role="status">{copyStatus}</p>}
      <ol>{reasons.map((reason, index) => <li id={`finding-${index}`} tabIndex={-1} key={index}>{text(reason)} <a href="#core-evidence">Frozen evidence</a> · <code>/analysis/topReasons/{index}</code></li>)}</ol>
    </section>
    {caseId && token && <ReportTotalMeasure key={`${caseId}-${token}-${report.version}`} report={report} caseId={caseId} token={token} />}
    <section className="panel"><h3>Review and rating</h3><div className="status-row"><strong>{text(analysis.reviewLabel)}</strong><span>{text(analysis.dealRating).replaceAll("_", " ")}</span></div><p>{text(analysis.ratingBasis)}</p></section>
    <p>Version {report.version} · {report.status} · As of <time dateTime={report.asOf}>{report.asOf}</time></p>
    {report.status === "PARTIAL" && <p className="notice">Warning: this report is partial. One or more analysis areas are unavailable.</p>}
    <nav aria-label="Report sections">{analysis.dealReview && <><a href="#revised-offer-review">Revised offer comparison</a> · </>}<a href="#core-amounts">Amounts</a> · {showFinancing && <><a href="#core-financing">Financing</a> · </>} <a href="#core-evidence">Evidence and calculations</a> · <a href="#core-scope">Scope and policies</a></nav>
    {analysis.dealReview && <RevisedOfferReview review={analysis.dealReview} redacted={redacted} />}
    <ul className="report-sections" aria-label="Report details">
      <ReportSection id="core-amounts" title="Amounts and sources">
        <p>Market analysis: {analysis.marketAvailability}. Cash economics: {analysis.cashAvailability}.</p>
        <dl className="money-list">
          {analysis.materialAmounts.map((amount, index) => <div key={index}>
            <dt>{amountLabel(amount.label)}<small>{amount.precision.toLowerCase()} · <a href={`#amount-evidence-${index}`} title={`${amount.source}${amount.pointer}`}>Source and calculation {index + 1}</a></small></dt>
            <dd>{money(amount)}</dd>
          </div>)}
        </dl>
      </ReportSection>
      {showFinancing && <ReportSection id="core-financing" title="Dealer financing">
        <DealerFinancingResult result={analysis.financing ?? null} availability={analysis.financingAvailability!} terms={analysis.financingTerms} headless />
      </ReportSection>}
      <ReportSection id="core-market" title="Market evidence" headingId="market-evidence-heading">
        <MarketEvidenceParagraphs evidence={analysis.marketEvidence} />
      </ReportSection>
      <ReportSection id="core-evidence" title="Evidence and calculations">
        <p>Each amount shows the dealer document page and region it was read from, or says that no document source exists, and each calculated amount shows its arithmetic.</p>
        {analysis.materialAmounts.map((amount, index) => <section key={index} id={`amount-evidence-${index}`} tabIndex={-1} className="amount-evidence">
          <h4>{amountLabel(amount.label)}: {money(amount)} <small>{amount.precision.toLowerCase()}</small></h4>
          {redacted && (amount.documentSource?.pageNumber == null || amount.documentSource.region == null)
            ? <p>Document source: removed from this example. Example reports keep no document pages or regions.</p>
            : amount.documentSource
            ? <DocumentSourceView source={amount.documentSource} caseId={caseId} token={token} what={amountLabel(amount.label)} />
            : <p>No document source: {amount.sourceNote ?? "this saved report does not record where this amount came from."}</p>}
          {amount.calculation && <Arithmetic calculation={amount.calculation} result={amount.precision === "UNAVAILABLE" ? null : amount.minorUnits} currency={amount.currency} label={amountLabel(amount.label)} />}
        </section>)}
        {redacted ? <p>Diagnostic data and frozen inputs: removed from this example.</p> : <ReproductionDiagnostics report={report} />}
      </ReportSection>
      <ReportSection id="core-scope" title="Scope and policies">
        {analysis.limitations.map((limitation, index) => <p key={index}>{text(limitation)}</p>)}
        <dl className="report-policies">{Object.entries(analysis.policyVersions).map(([name, version]) => <div key={name}><dt>{name}</dt><dd>{version}</dd></div>)}</dl>
      </ReportSection>
      {children}
    </ul>
  </article>;
}

export function MarketEvidenceSection({ evidence }: { evidence?: CoreReport["analysis"]["marketEvidence"] }) {
  return <section className="panel" aria-labelledby="market-evidence-heading">
    <h3 id="market-evidence-heading">Market evidence</h3>
    <MarketEvidenceParagraphs evidence={evidence} />
  </section>;
}

function MarketEvidenceParagraphs({ evidence }: { evidence?: CoreReport["analysis"]["marketEvidence"] }) {
  return evidence?.paragraphs.length ? <>{evidence.paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}</>
    : <p>Market evidence details are unavailable in this saved report.</p>;
}

/**
 * One entry of the report's collapsible list. Sections start collapsed; the open state lives only
 * in the page. The summary's heading names the section and the native disclosure announces
 * whether it is open. The `id` stays on the disclosure itself so existing links still resolve.
 */
export function ReportSection({ id, title, headingId = `${id}-heading`, children }: {
  id: string; title: string; headingId?: string; children: ReactNode;
}) {
  return <li>
    <details className="panel report-section" id={id} tabIndex={-1}>
      <summary><h3 id={headingId}>{title}</h3></summary>
      <section aria-labelledby={headingId} className="report-section-body">{children}</section>
    </details>
  </li>;
}

function sectionTarget(id: string) {
  let target: HTMLElement | null = null;
  try { target = document.getElementById(decodeURIComponent(id)); } catch { return null; }
  const section = target?.closest<HTMLDetailsElement>("details.report-section");
  return target && section ? { target, section } : null;
}

/** Opens the section holding an in-report link target, then focuses and scrolls to the target. */
function revealSectionTarget(id: string) {
  const found = sectionTarget(id);
  if (!found) return;
  found.section.open = true;
  const focusable = found.target.hasAttribute("tabindex") || found.target.matches("a[href], button, input, select, textarea, summary");
  (focusable ? found.target : found.section).focus({ preventScroll: true });
  found.target.scrollIntoView?.({ block: "start" });
}

/**
 * Links into the list open the section that holds their target: in-page clicks (including a repeat
 * click on the current hash), the back and forward buttons, and a report opened at a fragment.
 * Printing expands every section, then restores the Buyer's choices.
 */
function useSectionLinks(article: RefObject<HTMLElement | null>, report: CoreReport) {
  useEffect(() => {
    if (window.location.hash.length > 1) revealSectionTarget(window.location.hash.slice(1));
  }, [report]);
  useEffect(() => {
    const onHashChange = () => revealSectionTarget(window.location.hash.slice(1));
    const onClick = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = (event.target as Element | null)?.closest?.("a[href^='#']");
      const id = link?.getAttribute("href")?.slice(1);
      const found = id ? sectionTarget(id) : null;
      if (!id || !found) return;
      found.section.open = true;
      window.setTimeout(() => revealSectionTarget(id));
    };
    let reopened: HTMLDetailsElement[] = [];
    const onBeforePrint = () => {
      reopened = Array.from(article.current?.querySelectorAll<HTMLDetailsElement>("details.report-section:not([open])") ?? []);
      reopened.forEach(section => { section.open = true; });
    };
    const onAfterPrint = () => { reopened.forEach(section => { section.open = false; }); reopened = []; };
    window.addEventListener("hashchange", onHashChange);
    document.addEventListener("click", onClick);
    window.addEventListener("beforeprint", onBeforePrint);
    window.addEventListener("afterprint", onAfterPrint);
    return () => {
      window.removeEventListener("hashchange", onHashChange);
      document.removeEventListener("click", onClick);
      window.removeEventListener("beforeprint", onBeforePrint);
      window.removeEventListener("afterprint", onAfterPrint);
    };
  }, [article]);
}

/** Serialized inputs are only rendered once the Buyer opens this diagnostic disclosure. */
function ReproductionDiagnostics({ report }: { report: CoreReport }) {
  const [open, setOpen] = useState(false);
  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>Diagnostic data for reproducing this report</summary>
    <p>This is technical data for regenerating and auditing the report. It is not evidence to read as a Buyer; the sections above explain each amount.</p>
    {open && <>
      <h4>Reproduction metadata</h4>
      <pre>{JSON.stringify(report.reproduction, null, 2)}</pre>
      <h4>Frozen inputs</h4>
      <pre>{JSON.stringify(report.evidence, null, 2)}</pre>
    </>}
  </details>;
}
