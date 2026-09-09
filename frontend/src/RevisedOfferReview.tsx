type CashDelta = {
  availability: string; currency?: string; preTaxDeltaMinor?: number;
  taxEffectMinor?: number; outTheDoorDeltaMinor?: number; reason?: string;
};
export type DealReview = {
  version: number; baselineVersion: number | null; previousOfferVersion: number; currentOfferVersion: number;
  status: string; checkedAt: string; tolerancePolicy: string; scope: string;
  discrepancies: {
    comparison: string; term: string; kind: string; expected: unknown; observed: unknown;
    delta: number | null; unit: string; severity: string; reason: string;
    expectedEvidence: unknown; observedEvidence: unknown; confidence: number | null;
    uncertain: boolean; buyerAction: string;
  }[];
  cashDeltas: Record<string, CashDelta>;
};

const value = (v: unknown): string => v == null ? "Missing" : typeof v === "object"
  ? Object.entries(v).map(([key, item]) => `${key}: ${item ?? "Missing"}`).join(" · ") : String(v);

export function RevisedOfferReview({ review }: { review: DealReview }) {
  return <section className="panel" id="revised-offer-review" tabIndex={-1} aria-labelledby="revised-review-heading">
    <h3 id="revised-review-heading">Revised Dealer Offer review</h3>
    <p>Baseline {review.baselineVersion ?? "unavailable"} · Previous offer {review.previousOfferVersion} · Current offer {review.currentOfferVersion}</p>
    <p>Review {review.version} · {review.status.replaceAll("_", " ")} · Checked {review.checkedAt}</p>
    <p>{review.scope}</p>
    <p>All differences are shown, including those below materiality. {review.tolerancePolicy}</p>
    {Object.entries(review.cashDeltas).map(([comparison, delta]) => {
      const money = (minor: number | undefined) => minor == null ? "Unavailable"
        : new Intl.NumberFormat("en-US", { style: "currency", currency: delta.currency ?? "USD" }).format(minor / 100);
      return <section key={comparison} aria-label={`Cash difference against ${comparison}`}>
        <h4>Cash difference against {comparison}</h4>
        {delta.availability === "UNAVAILABLE" ? <p>Unavailable. {delta.reason}</p> : <>
          <p>Pre-tax difference: {money(delta.preTaxDeltaMinor)}</p>
          <p>Tax effect: {money(delta.taxEffectMinor)}</p>
          <p>Out-the-door difference: {money(delta.outTheDoorDeltaMinor)}</p>
          <p>{delta.reason}</p>
        </>}
      </section>;
    })}
    {review.discrepancies.length === 0 && <p>No document term differences found. Check the review status and scope before proceeding.</p>}
    <ol>{review.discrepancies.map((d, index) => <li key={index}>
      <h4>{d.comparison} · {d.term}</h4>
      <p>{d.kind} · {d.severity}{d.uncertain ? " · Uncertain" : ""}</p>
      <dl>
        <dt>Expected</dt><dd>{value(d.expected)}</dd>
        <dt>Observed</dt><dd>{value(d.observed)}</dd>
        <dt>Change</dt><dd>{d.delta == null ? "Not numerically comparable" : `${d.delta} ${d.unit}`}</dd>
        <dt>Extraction confidence</dt><dd>{d.confidence == null ? "Unavailable" : `${Math.round(d.confidence * 100)}%`}</dd>
      </dl>
      <p>{d.reason}</p><p>Buyer action: {d.buyerAction}</p>
      <details><summary>Evidence for difference {index + 1}</summary>
        <h5>Expected evidence</h5><pre>{JSON.stringify(d.expectedEvidence, null, 2) ?? "Missing"}</pre>
        <h5>Observed evidence</h5><pre>{JSON.stringify(d.observedEvidence, null, 2) ?? "Missing"}</pre>
      </details>
    </li>)}</ol>
  </section>;
}
