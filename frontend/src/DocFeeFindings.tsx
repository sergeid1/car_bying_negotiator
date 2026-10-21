import { useMessages } from "./i18n/LanguageProvider";

export type DocFeeFinding = {
  reason: string; buyerAction: string; uncertain: boolean;
  observedEvidence: { rule: { citation: string; url: string } };
};

/** These are state-rule questions, separate from changes against an agreed quote. */
export function DocFeeFindings({ findings }: { findings?: DocFeeFinding[] }) {
  const { m: { paperwork: { docFeeFindings: t } }, fill } = useMessages();
  if (!findings?.length) return null;
  return <div aria-label={t.label}>
    <h4>{t.heading}</h4>
    <ul>{findings.map((finding, index) => <li key={index}>
      <p>{finding.reason}</p>
      {finding.uncertain && <p>{t.uncertain}</p>}
      <p>{fill(t.ask, { action: finding.buyerAction })}</p>
      <a href={finding.observedEvidence.rule.url} target="_blank" rel="noreferrer">{finding.observedEvidence.rule.citation}</a>
    </li>)}</ul>
  </div>;
}
