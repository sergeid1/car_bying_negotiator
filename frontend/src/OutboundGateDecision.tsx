import { en } from "./i18n/en";
import { useMessages } from "./i18n/LanguageProvider";

export type GateDecision = {
  pass: boolean;
  gateVersion: string;
  reasons: { code: string; text: string }[];
};

export function OutboundGateDecision({ decision }: { decision: GateDecision }) {
  const { m: { messages: { outboundGateDecision: t } } } = useMessages();
  const english = en.messages.outboundGateDecision.reasons;
  // Reasons arrive in the server's English; known ones show in the site's language, others as sent.
  const reasonText = (text: string) => {
    const key = (Object.keys(english) as (keyof typeof english)[]).find(name => english[name] === text);
    return key ? t.reasons[key] : text;
  };
  return <section className="card" aria-label={t.label}>
    <h2>{decision.pass ? t.passed : t.blocked}</h2>
    <p>{decision.pass ? t.passedDetail : t.blockedDetail}</p>
    {decision.reasons.length > 0 && <ul>{decision.reasons.map(reason =>
      <li key={reason.code}>{reasonText(reason.text)}</li>)}</ul>}
  </section>;
}
