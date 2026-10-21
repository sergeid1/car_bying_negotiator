import { OutcomeFeedback } from "./PilotFeedback";
import { DocFeeFindings, type DocFeeFinding } from "./DocFeeFindings";
import { DealerAddOnsNote, type DealerAddOnCheck } from "./DealerAddOns";
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
import { TradeCheckPanel, type TradeCheckResult } from "./TradeCheck";
import { DEALER_FINANCING_COSTS_LESS, DeskTactics, type DeskTacticFinding } from "./DeskTactics";
import { VehicleSafetyFacts, type VehicleSafetyView } from "./VehicleSafety";
import { ListingPriceNote, type ListingPriceCheck } from "./ListingCapture";
import { GovernmentFeeNote, type GovernmentFeeCheck } from "./GovernmentFees";
import { SigningNote } from "./SigningNote";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";
import { errorMessage } from "./i18n/errors";
import { findingCode, localizeReport, type Finding } from "./i18n/findings";

export type MaterialAmount = {
  label: string; minorUnits: number | null; currency: string; precision: string; source: string; pointer: string;
  documentSource?: DocumentSource | null; sourceNote?: string | null; calculation?: Calculation | null;
};

export type CoreReport = {
  textFindings?: Record<string, Finding>;
  version: number; asOf: string; status: string;
  analysis: {
    tacticFindings?: DeskTacticFinding[];
    dealReview?: DealReview;
    docFeeFindings?: DocFeeFinding[];
    dealerAddOns?: DealerAddOnCheck | null;
    tradeCheck?: TradeCheckResult | null;
    listingPriceCheck?: ListingPriceCheck | null;
    governmentFeeCheck?: GovernmentFeeCheck | null;
    financingAvailability?: string; financing?: FinancingResult | null; financingTerms?: { aprPercent: number | null; termMonths: number | null } | null;
    reviewLabel: string; dealRating: string; ratingBasis: string; nextAction: string;
    topReasons: string[]; marketAvailability: string; cashAvailability: string;
    marketEvidence?: { status: string; paragraphs: string[] };
    materialAmounts: MaterialAmount[];
    policyVersions: Record<string, string>; limitations: string[];
  };
  evidence: Record<string, unknown>;
  reproduction: Record<string, unknown>;
  vehicleSafety?: VehicleSafetyView | null;
};

/** The report path of one leading finding, shown beside it; a path, not copy. */
const reasonPointer = (index: number) => `/analysis/topReasons/${index}`;

export function CoreReportLoader({ caseId, token }: { caseId: string; token: string }) {
  const { m: { paperwork: { coreDealReport: t, common } } } = useMessages();
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
        if (!response.ok) throw new Error(t.generateFailed);
      }
      const response = await fetch(`${path}/reports/latest`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.reportUnavailable);
      const payload = await response.json();
      if (!payload.analysis?.materialAmounts) throw new Error(t.syntheticReport);
      setReport(payload);
      if (generate) setKey(crypto.randomUUID());
    } catch (caught) { setError(errorMessage(caught, t.reportUnavailable)); } finally { setBusy(false); }
  }
  return <section className="stack" aria-label={t.loaderLabel}>
    {report && <CoreDealReport report={report} caseId={caseId} token={token}>
      <ReportSection id="core-feedback" title={common.outcomeFeedback}>
        <OutcomeFeedback key={`feedback-${caseId}-${token}`} caseId={caseId} token={token} headless />
      </ReportSection>
      <ReportSection id="core-downloads" title={common.saveCopy}>
        <CaseDownloads key={`download-${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} headless />
      </ReportSection>
    </CoreDealReport>}
    <DealerFinancingForm key={`dealer-${caseId}-${token}`} caseId={caseId} token={token} />
    <OutsideFinancing key={`outside-${caseId}-${token}`} caseId={caseId} token={token} />
    <div className="panel">
      <div className="actions">
        <button onClick={() => load(true)} disabled={busy}>{t.generate}</button>
        <button className="btn-secondary" onClick={() => load(false)} disabled={busy}>{t.openLatest}</button>
      </div>
      {busy && <p role="status">{t.loading}</p>}
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
  const { m: { paperwork: { coreDealReport: t, common } }, fill, fillNodes, language } = useMessages();
  const heading = useRef<HTMLHeadingElement>(null);
  const article = useRef<HTMLElement>(null);
  useEffect(() => { heading.current?.focus(); }, [report]);
  useSectionLinks(article, report);
  const { analysis } = localizeReport(report, language);
  const removed = t.removed;
  const text = (value: string | null | undefined) => value ?? (redacted ? removed : common.unknown);
  const total = analysis.materialAmounts.find(amount => ["/result/outTheDoorTotal", "/inputs/outTheDoorTotalMinor", "/analysis/economics/outTheDoorTotal"].includes(amount.pointer));
  const reasons = analysis.topReasons.slice(0, 3);
  const [questions, setQuestions] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  useEffect(() => { setQuestions(localizeReport(report, language).analysis.topReasons.slice(0, 3).filter(reason => reason != null).map(reason => fill(t.question, { reason })).join("\n")); setCopyStatus(""); }, [report, language]);
  const money = (amount: CoreReport["analysis"]["materialAmounts"][number] | undefined) => redacted && amount && amount.minorUnits == null && amount.precision !== "UNAVAILABLE" ? removed : !amount || amount.minorUnits == null || amount.precision === "UNAVAILABLE" ? common.unknown : format.money(amount.minorUnits, { currency: amount.currency });
  const totalIndex = total ? analysis.materialAmounts.indexOf(total) : -1;
  const amountLabel = (label: string | null) => label == null ? removed : label.replace("/payload/plan/", t.approvedPrefix)
    .replace("/result/", "").replaceAll("/", " · ").replace(/([a-z])([A-Z])/g, "$1 $2").replace(/Minor$/, "");
  const showFinancing = analysis.financingAvailability && analysis.financingAvailability !== "NOT_APPLICABLE";
  return <article className="report" aria-labelledby="core-report-heading" ref={article}>
    <section className="outcome report-leading" aria-label={t.leadingLabel}>
      <h2 id="core-report-heading" tabIndex={-1} ref={heading}>{t.title}</h2>
      <h3>{t.outTheDoor}</h3>
      <p className="report-leading-total">{total ? <a href={`#amount-evidence-${totalIndex}`}>{total.minorUnits != null && total.precision !== "UNAVAILABLE"
        ? <AnimatedAmount minorUnits={total.minorUnits} currency={total.currency} text={money(total)} /> : money(total)}</a> : common.unknown} <small>{total ? findingCode(language, total.precision) : t.precisionUnavailable}</small></p>
      <h3>{t.threeFindings}</h3>
      <ol>{reasons.map((reason, index) => <li key={index}><a href={`#finding-${index}`}>{text(reason)}</a></li>)}</ol>
      {reasons.length < 3 && <p>{fill(t.furtherUnknown, { count: 3 - reasons.length })}</p>}
      <h3>{t.nextAction}</h3>
      <p className="next-action">{text(analysis.nextAction)}</p>
    </section>
    {caseId && token && <ReportExplanationLoader key={`${caseId}-${token}-${report.version}`} caseId={caseId} token={token} reportVersion={report.version} />}
    <section className="panel" aria-labelledby="core-questions">
      <h3 id="core-questions">{t.questionsHeading}</h3>
      <p>{t.questionsNote}</p>
      <label htmlFor="report-questions">{t.yourQuestions}<textarea id="report-questions" rows={4} value={questions} onChange={event => setQuestions(event.target.value)} /></label>
      <button type="button" onClick={async () => { try { await navigator.clipboard.writeText(questions); setCopyStatus(t.copied); } catch { setCopyStatus(t.copyFailed); } }}>{t.copy}</button>
      {copyStatus && <p role="status">{copyStatus}</p>}
      <ol>{reasons.map((reason, index) => <li id={`finding-${index}`} tabIndex={-1} key={index}>{text(reason)} <a href="#core-evidence">{t.frozenEvidence}</a> · <code>{reasonPointer(index)}</code></li>)}</ol>
    </section>
    {caseId && token && <ReportTotalMeasure key={`${caseId}-${token}-${report.version}`} report={report} caseId={caseId} token={token} />}
    <section className="panel"><h3>{t.reviewRating}</h3><div className="status-row"><strong>{text(analysis.reviewLabel)}</strong><span>{findingCode(language, text(analysis.dealRating))}</span></div><p>{text(analysis.ratingBasis)}</p></section>
    <p>{fillNodes(t.versionLine, { version: report.version, status: findingCode(language, report.status), time: <time dateTime={report.asOf}>{report.asOf}</time> })}</p>
    {report.status === "PARTIAL" && <p className="notice">{t.partial}</p>}
    <nav aria-label={t.sectionsNav}>{analysis.dealReview && <><a href="#revised-offer-review">{t.revisedComparison}</a> · </>}<a href="#core-amounts">{t.amounts}</a> · {showFinancing && <><a href="#core-financing">{t.financing}</a> · </>} <a href="#core-evidence">{t.evidence}</a> · <a href="#core-scope">{t.scope}</a></nav>
    {analysis.dealReview && <RevisedOfferReview review={analysis.dealReview} redacted={redacted} />}
    {analysis.tradeCheck && <div className="panel"><TradeCheckPanel result={analysis.tradeCheck} tactics={analysis.tacticFindings} /></div>}
    {analysis.listingPriceCheck && <div className="panel"><ListingPriceNote check={analysis.listingPriceCheck} title={t.savedAd} /></div>}
    {analysis.governmentFeeCheck && <div className="panel"><GovernmentFeeNote check={analysis.governmentFeeCheck} /></div>}
    <ul className="report-sections" aria-label={t.details}>
      {report.vehicleSafety && !redacted && <ReportSection id="core-vehicle-safety" title={t.vehicleSafety}>
        <VehicleSafetyFacts view={report.vehicleSafety} />
      </ReportSection>}
      <ReportSection id="core-amounts" title={t.amountsSources}>
        {!analysis.dealReview && <DocFeeFindings findings={analysis.docFeeFindings} />}
        {!analysis.dealReview && analysis.dealerAddOns && <DealerAddOnsNote check={analysis.dealerAddOns} />}
        <p>{fill(t.marketCash, { market: findingCode(language, analysis.marketAvailability), cash: findingCode(language, analysis.cashAvailability) })}</p>
        <dl className="money-list">
          {analysis.materialAmounts.map((amount, index) => <div key={index}>
            <dt>{amountLabel(amount.label)}<small>{findingCode(language, amount.precision)} · <a href={`#amount-evidence-${index}`} title={`${amount.source}${amount.pointer}`}>{fill(t.sourceCalculation, { number: index + 1 })}</a></small></dt>
            <dd>{money(amount)}</dd>
          </div>)}
        </dl>
      </ReportSection>
      {showFinancing && <ReportSection id="core-financing" title={t.dealerFinancing}>
        <DealerFinancingResult result={analysis.financing ?? null} availability={analysis.financingAvailability!} terms={analysis.financingTerms} headless />
      </ReportSection>}
      {analysis.tacticFindings?.some(finding => !finding.tradeFindingCode) && <ReportSection id="core-tactics"
        title={analysis.tacticFindings.some(finding => !finding.tradeFindingCode && finding.code !== DEALER_FINANCING_COSTS_LESS)
          ? t.worksheetTactics : t.ownFinancing}>
        <DeskTactics findings={analysis.tacticFindings} />
      </ReportSection>}
      <ReportSection id="core-market" title={t.marketEvidence} headingId="market-evidence-heading">
        <MarketEvidenceParagraphs evidence={analysis.marketEvidence} />
      </ReportSection>
      <ReportSection id="core-evidence" title={t.evidence}>
        <p>{t.evidenceIntro}</p>
        {analysis.materialAmounts.map((amount, index) => <section key={index} id={`amount-evidence-${index}`} tabIndex={-1} className="amount-evidence">
          <h4>{fill(t.amountHeading, { label: amountLabel(amount.label), amount: money(amount) })} <small>{findingCode(language, amount.precision)}</small></h4>
          {redacted && (amount.documentSource?.pageNumber == null || amount.documentSource.region == null)
            ? <p>{t.documentSourceRemoved}</p>
            : amount.documentSource
            ? <DocumentSourceView source={amount.documentSource} caseId={caseId} token={token} what={amountLabel(amount.label)} />
            : <p>{fill(t.noDocumentSource, { note: amount.sourceNote ?? t.noSourceNote })}</p>}
          {amount.calculation && <Arithmetic calculation={amount.calculation} result={amount.precision === "UNAVAILABLE" ? null : amount.minorUnits} currency={amount.currency} label={amountLabel(amount.label)} />}
        </section>)}
        {redacted ? <p>{t.diagnosticsRemoved}</p> : <ReproductionDiagnostics report={report} />}
      </ReportSection>
      <ReportSection id="core-scope" title={t.scope}>
        {analysis.limitations.map((limitation, index) => <p key={index}>{text(limitation)}</p>)}
        <SigningNote />
        <dl className="report-policies">{Object.entries(analysis.policyVersions).map(([name, version]) => <div key={name}><dt>{name}</dt><dd>{version}</dd></div>)}</dl>
      </ReportSection>
      {children}
    </ul>
  </article>;
}

export function MarketEvidenceSection({ evidence }: { evidence?: CoreReport["analysis"]["marketEvidence"] }) {
  const { m: { paperwork: { coreDealReport: t } } } = useMessages();
  return <section className="panel" aria-labelledby="market-evidence-heading">
    <h3 id="market-evidence-heading">{t.marketEvidence}</h3>
    <MarketEvidenceParagraphs evidence={evidence} />
  </section>;
}

function MarketEvidenceParagraphs({ evidence }: { evidence?: CoreReport["analysis"]["marketEvidence"] }) {
  const { m: { paperwork: { coreDealReport: t } } } = useMessages();
  return evidence?.paragraphs.length ? <>{evidence.paragraphs.map((paragraph, index) => <p key={index}>{paragraph}</p>)}</>
    : <p>{t.marketUnavailable}</p>;
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
  const { m: { paperwork: { coreDealReport: t } } } = useMessages();
  const [open, setOpen] = useState(false);
  return <details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>{t.diagnostics}</summary>
    <p>{t.diagnosticsNote}</p>
    {open && <>
      <h4>{t.reproduction}</h4>
      <pre>{JSON.stringify(report.reproduction, null, 2)}</pre>
      <h4>{t.frozenInputs}</h4>
      <pre>{JSON.stringify(report.evidence, null, 2)}</pre>
    </>}
  </details>;
}
