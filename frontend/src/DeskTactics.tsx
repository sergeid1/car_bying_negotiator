import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

export type DeskTacticFinding = {
  code: string; label: string; summary: string; policyVersion: string; verified: boolean;
  monthlyEffectMinor: number | null; totalEffectMinor: number | null; effectBasis: string;
  noDollarEffectReason: string | null; deskQuestion: string; questionReview: string;
  tradeFindingCode?: string | null;
  evidenceRegions: { field: string; region: { pageNumber?: number; source?: string; version?: number } | null; verified: boolean }[];
};

const money = (minor: number) => format.money(minor);
/** Not a tactic: the dealer's financing costs less than the Buyer's own offer, said plainly (ticket 129). */
export const DEALER_FINANCING_COSTS_LESS = "DEALER_FINANCING_COSTS_LESS";

type Copy = Messages["dealer"]["deskTactics"];
type Fill = ReturnType<typeof useMessages>["fill"];

/** One evidence line, such as "unit price: verified, page 2, buyer order, version 1.", its details joined by commas. */
function evidenceLine(t: Copy, fill: Fill, evidence: DeskTacticFinding["evidenceRegions"][number], full: boolean) {
  const details = [evidence.verified ? t.evidenceVerified : t.evidenceAsRead,
    evidence.region?.pageNumber ? fill(t.evidencePage, { page: String(evidence.region.pageNumber) }) : "",
    full && evidence.region?.source ? evidence.region.source.replaceAll("_", " ") : "",
    full && evidence.region?.version ? fill(t.evidenceVersion, { version: String(evidence.region.version) }) : ""];
  return fill(t.evidence, { field: evidence.field.replaceAll("_", " "), details: details.filter(Boolean).join(", ") });
}

export function DeskTactics({ findings = [] }: { findings?: DeskTacticFinding[] }) {
  const { m: { dealer: { deskTactics: t, common } }, fill } = useMessages();
  const cheaper = findings.find(finding => finding.code === DEALER_FINANCING_COSTS_LESS);
  findings = findings.filter(finding => !finding.tradeFindingCode && finding.code !== DEALER_FINANCING_COSTS_LESS);
  if (!findings.length && !cheaper) return null;
  return <>{cheaper && <DealerFinancingCostsLess finding={cheaper} />}
  {findings.length > 0 && <section className="desk-tactics" aria-label={t.label}>
    <h3>{t.heading}</h3>
    <ul>{findings.map(finding => <li key={finding.code}>
      <h4>{finding.label}</h4>
      <p>{finding.summary}</p>
      <p>{finding.verified ? common.verifiedTerms : common.asReadSentence}
        {finding.monthlyEffectMinor !== null && ` ${fill(t.monthlyEffect, { amount: money(finding.monthlyEffectMinor) })}`}
        {finding.totalEffectMinor !== null && ` ${fill(t.fullTermEffect, { amount: money(finding.totalEffectMinor) })}`}
        {finding.noDollarEffectReason && ` ${fill(t.dollarEffectUnavailable, { reason: finding.noDollarEffectReason })}`}</p>
      <p>{fill(t.draftQuestion, { question: finding.deskQuestion })}</p>
      {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">{common.awaitsReview}</p>}
      <details><summary>{t.calculation}</summary>
        <p>{finding.effectBasis}</p>
        <p>{fill(t.policyCode, { policy: finding.policyVersion, code: finding.code })}</p>
        <ul>{finding.evidenceRegions.map((evidence, index) => <li key={`${evidence.field}-${index}`}>
          {evidenceLine(t, fill, evidence, true)}
        </li>)}</ul>
      </details>
    </li>)}</ul>
  </section>}</>;
}

/** The dealer's financing costs less than the Buyer's own offer; its effects are negative, so they are shown as amounts less. */
function DealerFinancingCostsLess({ finding }: { finding: DeskTacticFinding }) {
  const { m: { dealer: { deskTactics: t, common } }, fill } = useMessages();
  return <section className="desk-tactics desk-own-financing" aria-label={t.ownFinancing}>
    <h3>{finding.label}</h3>
    <p>{finding.summary}</p>
    <p>{finding.verified ? common.verifiedTerms : common.asReadSentence}
      {finding.monthlyEffectMinor !== null && ` ${fill(t.lessAMonth, { amount: money(Math.abs(finding.monthlyEffectMinor)) })}`}
      {finding.totalEffectMinor !== null && ` ${fill(t.lessInterest, { amount: money(Math.abs(finding.totalEffectMinor)) })}`}
      {finding.noDollarEffectReason && ` ${fill(t.dollarEffectUnavailable, { reason: finding.noDollarEffectReason })}`}</p>
    <p>{fill(t.draftQuestion, { question: finding.deskQuestion })}</p>
    {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">{common.awaitsReview}</p>}
    <details><summary>{t.calculation}</summary>
      <p>{finding.effectBasis}</p>
      <p>{fill(t.policyCode, { policy: finding.policyVersion, code: finding.code })}</p>
    </details>
  </section>;
}

/** Adds the tactic name and policy context to an existing trade line, without another dollar display. */
export function TradeTacticAnnotation({ finding }: { finding: DeskTacticFinding }) {
  const { m: { dealer: { deskTactics: t, common } }, fill, fillNodes } = useMessages();
  return <div className="desk-tactics">
    <p><strong>{finding.label}</strong></p>
    <p>{fillNodes(t.draftQuestion, { question: <q>{finding.deskQuestion}</q> })}</p>
    {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">{common.awaitsReview}</p>}
    <details><summary>{t.tacticEvidence}</summary>
      <p>{finding.verified ? common.verifiedTerms : common.asReadSentence}</p>
      <p>{finding.effectBasis}</p>
      {finding.noDollarEffectReason && <p>{finding.noDollarEffectReason}</p>}
      <p>{fill(t.policyCode, { policy: finding.policyVersion, code: finding.code })}</p>
      <ul>{finding.evidenceRegions.map((evidence, index) => <li key={`${evidence.field}-${index}`}>
        {evidenceLine(t, fill, evidence, false)}
      </li>)}</ul>
    </details>
  </div>;
}
