import * as format from "./i18n/format";

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

export function DeskTactics({ findings = [] }: { findings?: DeskTacticFinding[] }) {
  const cheaper = findings.find(finding => finding.code === DEALER_FINANCING_COSTS_LESS);
  findings = findings.filter(finding => !finding.tradeFindingCode && finding.code !== DEALER_FINANCING_COSTS_LESS);
  if (!findings.length && !cheaper) return null;
  return <>{cheaper && <DealerFinancingCostsLess finding={cheaper} />}
  {findings.length > 0 && <section className="desk-tactics" aria-label="Worksheet tactics">
    <h3>Worksheet tactics to ask about</h3>
    <ul>{findings.map(finding => <li key={finding.code}>
      <h4>{finding.label}</h4>
      <p>{finding.summary}</p>
      <p>{finding.verified ? "Verified terms." : "As read, not verified."}
        {finding.monthlyEffectMinor !== null && ` Monthly effect: ${money(finding.monthlyEffectMinor)}.`}
        {finding.totalEffectMinor !== null && ` Full-term effect: ${money(finding.totalEffectMinor)}.`}
        {finding.noDollarEffectReason && ` Dollar effect unavailable: ${finding.noDollarEffectReason}`}</p>
      <p>Draft desk question: {finding.deskQuestion}</p>
      {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">Question wording awaits operator review.</p>}
      <details><summary>Calculation and evidence</summary>
        <p>{finding.effectBasis}</p>
        <p>Policy: {finding.policyVersion}. Code: {finding.code}.</p>
        <ul>{finding.evidenceRegions.map((evidence, index) => <li key={`${evidence.field}-${index}`}>
          {evidence.field.replaceAll("_", " ")}: {evidence.verified ? "verified" : "as read"}
          {evidence.region?.pageNumber ? `, page ${evidence.region.pageNumber}` : ""}
          {evidence.region?.source ? `, ${evidence.region.source.replaceAll("_", " ")}` : ""}
          {evidence.region?.version ? `, version ${evidence.region.version}` : ""}.
        </li>)}</ul>
      </details>
    </li>)}</ul>
  </section>}</>;
}

/** The dealer's financing costs less than the Buyer's own offer; its effects are negative, so they are shown as amounts less. */
function DealerFinancingCostsLess({ finding }: { finding: DeskTacticFinding }) {
  return <section className="desk-tactics desk-own-financing" aria-label="Your own financing">
    <h3>{finding.label}</h3>
    <p>{finding.summary}</p>
    <p>{finding.verified ? "Verified terms." : "As read, not verified."}
      {finding.monthlyEffectMinor !== null && ` ${money(Math.abs(finding.monthlyEffectMinor))} less a month.`}
      {finding.totalEffectMinor !== null && ` ${money(Math.abs(finding.totalEffectMinor))} less in scheduled interest over the term.`}
      {finding.noDollarEffectReason && ` Dollar effect unavailable: ${finding.noDollarEffectReason}`}</p>
    <p>Draft desk question: {finding.deskQuestion}</p>
    {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">Question wording awaits operator review.</p>}
    <details><summary>Calculation and evidence</summary>
      <p>{finding.effectBasis}</p>
      <p>Policy: {finding.policyVersion}. Code: {finding.code}.</p>
    </details>
  </section>;
}

/** Adds the tactic name and policy context to an existing trade line, without another dollar display. */
export function TradeTacticAnnotation({ finding }: { finding: DeskTacticFinding }) {
  return <div className="desk-tactics">
    <p><strong>{finding.label}</strong></p>
    <p>Draft desk question: <q>{finding.deskQuestion}</q></p>
    {finding.questionReview === "PENDING_OPERATOR_REVIEW" && <p className="muted">Question wording awaits operator review.</p>}
    <details><summary>Tactic evidence and policy</summary>
      <p>{finding.verified ? "Verified terms." : "As read, not verified."}</p>
      <p>{finding.effectBasis}</p>
      {finding.noDollarEffectReason && <p>{finding.noDollarEffectReason}</p>}
      <p>Policy: {finding.policyVersion}. Code: {finding.code}.</p>
      <ul>{finding.evidenceRegions.map((evidence, index) => <li key={`${evidence.field}-${index}`}>
        {evidence.field.replaceAll("_", " ")}: {evidence.verified ? "verified" : "as read"}
        {evidence.region?.pageNumber ? `, page ${evidence.region.pageNumber}` : ""}.
      </li>)}</ul>
    </details>
  </div>;
}
