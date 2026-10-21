import { useMessages } from "./i18n/LanguageProvider";

export type GateDecision = {
  pass: boolean;
  gateVersion: string;
  reasons: { code: string; text: string }[];
};

export function OutboundGateDecision({ decision }: { decision: GateDecision }) {
  const { m: { messages: { outboundGateDecision: t } } } = useMessages();
  return <section className="card" aria-label={t.label}>
    <h2>{decision.pass ? t.passed : t.blocked}</h2>
    <p>{decision.pass ? t.passedDetail : t.blockedDetail}</p>
    {decision.reasons.length > 0 && <ul>{decision.reasons.map(reason =>
      <li key={reason.code}>{reason.text}</li>)}</ul>}
  </section>;
}
