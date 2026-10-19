export type DocFeeFinding = {
  reason: string; buyerAction: string; uncertain: boolean;
  observedEvidence: { rule: { citation: string; url: string } };
};

/** These are state-rule questions, separate from changes against an agreed quote. */
export function DocFeeFindings({ findings }: { findings?: DocFeeFinding[] }) {
  if (!findings?.length) return null;
  return <div aria-label="Dealer documentary fee check">
    <h4>Dealer documentary fee</h4>
    <ul>{findings.map((finding, index) => <li key={index}>
      <p>{finding.reason}</p>
      {finding.uncertain && <p>Read from paperwork; verify the fee lines before relying on this check.</p>}
      <p>Ask the dealer: {finding.buyerAction}</p>
      <a href={finding.observedEvidence.rule.url} target="_blank" rel="noreferrer">{finding.observedEvidence.rule.citation}</a>
    </li>)}</ul>
  </div>;
}
