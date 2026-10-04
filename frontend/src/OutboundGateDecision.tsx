export type GateDecision = {
  pass: boolean;
  gateVersion: string;
  reasons: { code: string; text: string }[];
};

export function OutboundGateDecision({ decision }: { decision: GateDecision }) {
  return <section className="card" aria-label="Dealer draft policy check">
    <h2>{decision.pass ? "Draft passed the policy check" : "Draft blocked by the policy check"}</h2>
    <p>{decision.pass
      ? "This stored check applies to this draft and its saved mandate. Sending requires a current check and your approval."
      : "This draft cannot proceed. Review these reasons and revise the message."}</p>
    {decision.reasons.length > 0 && <ul>{decision.reasons.map(reason =>
      <li key={reason.code}>{reason.text}</li>)}</ul>}
  </section>;
}
