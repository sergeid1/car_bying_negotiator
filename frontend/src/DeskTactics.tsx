export type DeskTacticFinding = {
  code: string; label: string; summary: string; policyVersion: string; verified: boolean;
  monthlyEffectMinor: number | null; totalEffectMinor: number | null; effectBasis: string;
  noDollarEffectReason: string | null; deskQuestion: string; questionReview: string;
  evidenceRegions: { field: string; region: { pageNumber?: number; source?: string; version?: number } | null; verified: boolean }[];
};

const money = (minor: number) => new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(minor / 100);

export function DeskTactics({ findings = [] }: { findings?: DeskTacticFinding[] }) {
  if (!findings.length) return null;
  return <section className="desk-tactics" aria-label="Worksheet tactics">
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
  </section>;
}
