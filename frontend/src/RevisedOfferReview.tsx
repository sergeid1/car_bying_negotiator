import { DocumentSourceView, formatMoney, type DocumentSource } from "./EvidenceSource";

type CashDelta = {
  availability: string; currency?: string; preTaxDeltaMinor?: number;
  taxEffectMinor?: number; outTheDoorDeltaMinor?: number; reason?: string;
  expectedOutTheDoorMinor?: number; observedOutTheDoorMinor?: number;
  expectedTaxesMinor?: number; observedTaxesMinor?: number;
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

type Fields = Record<string, unknown>;
const fields = (v: unknown): Fields | null => v != null && typeof v === "object" && !Array.isArray(v) ? v as Fields : null;
const signed = (minor: number, currency = "USD") => `${minor > 0 ? "+" : ""}${formatMoney(minor, currency)}`;

/** Plain value of a frozen term: money, percent or wording, never the serialized object. */
function termValue(v: unknown, unit: string): string {
  if (v == null) return "Not present";
  const term = fields(v);
  if (!term) return unit.endsWith("_MINOR") && typeof v === "number" ? formatMoney(v, unit.replace("_MINOR", "")) : String(v);
  if (typeof term.amountMinor === "number") return formatMoney(term.amountMinor, typeof term.currency === "string" ? term.currency : "USD");
  const raw = term.value;
  if (raw == null) return "Missing";
  const termUnit = typeof term.unit === "string" ? term.unit : unit;
  if (termUnit.endsWith("_MINOR") && !Number.isNaN(Number(raw))) return formatMoney(Number(raw), termUnit.replace("_MINOR", ""));
  if (termUnit === "PERCENT") return `${raw}%`;
  return String(raw);
}

function classification(v: unknown): string | null {
  const term = fields(v);
  if (!term) return null;
  const parts = [["category", "Category"], ["taxClaim", "Tax"], ["optionalityClaim", "Optional"]]
    .filter(([key]) => typeof term[key] === "string")
    .map(([key, name]) => `${name}: ${String(term[key]).replaceAll("_", " ").toLowerCase()}`);
  return parts.length ? parts.join(" · ") : null;
}

const LIMITS: Record<string, string> = {
  buyerMaxOutTheDoorPriceMinor: "Your out-the-door limit", buyerMaxSellingPriceMinor: "Your selling-price limit",
  "outTheDoor.walkAwayMinor": "Out-the-door walk-away price", "sellingPrice.walkAwayMinor": "Selling-price walk-away price",
};

function termName(term: string) {
  const [, kind, name, occurrence] = term.match(/^(candidate|line):(.*)#(\d+)$/) ?? [];
  if (!kind) return LIMITS[term] ?? term.replace(/Minor$/, "").replace(/([a-z])([A-Z])/g, "$1 $2").replaceAll(".", " · ");
  const readable = kind === "candidate" ? name.replaceAll("_", " ").toLowerCase() : name;
  return `${readable.charAt(0).toUpperCase()}${readable.slice(1)}${occurrence !== "1" ? ` (occurrence ${occurrence})` : ""}`;
}

function difference(d: DealReview["discrepancies"][number]) {
  if (d.delta == null) return "Not numerically comparable";
  if (d.unit.endsWith("_MINOR")) return signed(d.delta, d.unit.replace("_MINOR", ""));
  if (d.unit === "PERCENT") return `${d.delta > 0 ? "+" : ""}${d.delta} percentage points`;
  return `${d.delta > 0 ? "+" : ""}${d.delta} ${d.unit.toLowerCase()}`;
}

function documentSource(evidence: unknown, offerVersion: number | null): DocumentSource | null {
  const term = fields(evidence), region = fields(term?.evidence);
  if (!term || !region || typeof region.pageNumber !== "number") return null;
  const wording = typeof term.originalLabel === "string" ? term.originalLabel : typeof term.originalWording === "string" ? term.originalWording : null;
  return { documentId: null, extractionId: null, offerVersion, wording, pageNumber: region.pageNumber,
    region: { x: Number(region.x ?? 0), y: Number(region.y ?? 0), width: Number(region.width ?? 0), height: Number(region.height ?? 0) } };
}

function SourceLine({ evidence, side, offerVersion, baselineVersion, term, absent, redacted }: {
  evidence: unknown; side: "expected" | "observed"; offerVersion: number | null; baselineVersion: number | null; term: string;
  absent: boolean; redacted?: boolean;
}) {
  const source = documentSource(evidence, offerVersion);
  if (source) return <DocumentSourceView source={source} what={`the ${side} ${term}`} />;
  const recorded = fields(evidence);
  // An example report strips document pages and regions; that is a removal, not a missing line.
  if (redacted && !absent && !(recorded && (typeof recorded.baselineId === "string" || typeof recorded.basis === "string")))
    return <p>Source: removed from this example. Example reports keep no document pages or regions.</p>;
  if (recorded && typeof recorded.baselineId === "string")
    return <p>Source: your approved limit in Approved Baseline{baselineVersion != null ? ` version ${baselineVersion}` : ""}. No dealer document source exists for it.</p>;
  if (recorded && typeof recorded.basis === "string")
    return <p>Source: Dealer Offer version {offerVersion}, using the {recorded.basis.toLowerCase()}. It is not a single document line.</p>;
  if (evidence == null) return <p>Source: this line is not present in {side === "expected" ? "the earlier terms" : "the current offer"}, so no document source exists.</p>;
  return <p>Source: no document page or region is recorded for this term.</p>;
}

const comparisonName = (comparison: string) => comparison === "BASELINE" ? "Approved Baseline" : comparison === "PREVIOUS" ? "previous offer" : comparison.toLowerCase();

export function RevisedOfferReview({ review, redacted }: { review: DealReview; redacted?: boolean }) {
  const expectedVersion = (comparison: string) => comparison === "PREVIOUS" ? review.previousOfferVersion : null;
  return <section className="panel" id="revised-offer-review" tabIndex={-1} aria-labelledby="revised-review-heading">
    <h3 id="revised-review-heading">Revised Dealer Offer review</h3>
    <p>Baseline {review.baselineVersion ?? "unavailable"} · Previous offer {review.previousOfferVersion} · Current offer {review.currentOfferVersion}</p>
    <p>Review {review.version} · {review.status.replaceAll("_", " ")} · Checked {review.checkedAt}</p>
    <p>{review.scope}</p>
    <p>All differences are shown, including those below materiality. {review.tolerancePolicy}</p>
    {Object.entries(review.cashDeltas).map(([comparison, delta]) => {
      const money = (minor: number | undefined) => minor == null ? "Unavailable" : formatMoney(minor, delta.currency ?? "USD");
      const name = comparisonName(comparison.toUpperCase());
      const sides = delta.expectedTaxesMinor != null && delta.observedTaxesMinor != null;
      return <section key={comparison} id={`cash-delta-${comparison}`} tabIndex={-1} aria-label={`Cash difference against ${comparison}`}>
        <h4>Cash difference against {comparison}</h4>
        {delta.availability === "UNAVAILABLE" ? <p>Unavailable. {delta.reason}</p> : <dl>
          <dt>Out-the-door difference</dt>
          <dd>{sides && delta.expectedOutTheDoorMinor != null && delta.observedOutTheDoorMinor != null
            ? `Current offer ${money(delta.observedOutTheDoorMinor)} − ${name} ${money(delta.expectedOutTheDoorMinor)} = ${money(delta.outTheDoorDeltaMinor)}`
            : money(delta.outTheDoorDeltaMinor)}</dd>
          <dt>Tax effect, calculated separately</dt>
          <dd>{sides ? `Current offer taxes ${money(delta.observedTaxesMinor)} − ${name} taxes ${money(delta.expectedTaxesMinor)} = ${money(delta.taxEffectMinor)}` : money(delta.taxEffectMinor)}</dd>
          <dt>Pre-tax difference</dt>
          <dd>{`${money(delta.outTheDoorDeltaMinor)} − tax effect ${money(delta.taxEffectMinor)} = ${money(delta.preTaxDeltaMinor)}`}</dd>
        </dl>}
        {delta.availability !== "UNAVAILABLE" && <p>{delta.reason}</p>}
      </section>;
    })}
    {review.discrepancies.length === 0 && <p>No document term differences found. Check the review status and scope before proceeding.</p>}
    <ol>{review.discrepancies.map((d, index) => {
      const name = termName(d.term);
      const cash = review.cashDeltas[d.comparison.toLowerCase()];
      const expectedClass = classification(d.expected), observedClass = classification(d.observed);
      return <li key={index}>
        <h4>{name} · against the {comparisonName(d.comparison)}</h4>
        <p>{d.kind.replaceAll("_", " ").toLowerCase()} · {d.severity}{d.uncertain ? " · Uncertain" : ""}</p>
        <dl className="expected-observed">
          <div>
            <dt>Expected ({comparisonName(d.comparison)})</dt>
            <dd>{termValue(d.expected, d.unit)}{expectedClass && <small> · {expectedClass}</small>}
              <SourceLine evidence={d.expectedEvidence} side="expected" offerVersion={expectedVersion(d.comparison)} baselineVersion={review.baselineVersion} term={name} absent={d.kind === "ADDED"} redacted={redacted} /></dd>
          </div>
          <div>
            <dt>Observed (current offer {review.currentOfferVersion})</dt>
            <dd>{termValue(d.observed, d.unit)}{observedClass && <small> · {observedClass}</small>}
              <SourceLine evidence={d.observedEvidence} side="observed" offerVersion={review.currentOfferVersion} baselineVersion={review.baselineVersion} term={name} absent={d.kind === "MISSING"} redacted={redacted} /></dd>
          </div>
          <div>
            <dt>Difference (observed − expected)</dt>
            <dd>{difference(d)}</dd>
          </div>
          <div>
            <dt>Tax effect</dt>
            <dd>{cash && cash.availability !== "UNAVAILABLE"
              ? <>Calculated separately for the whole offer, not per line: {formatMoney(cash.taxEffectMinor, cash.currency ?? "USD")}. <a href={`#cash-delta-${d.comparison.toLowerCase()}`}>See the cash difference against {d.comparison.toLowerCase()}</a>.</>
              : "Unavailable: both offer calculations are needed to calculate it separately."}</dd>
          </div>
          <div>
            <dt>Extraction confidence</dt>
            <dd>{d.confidence == null ? "Unavailable" : `${Math.round(d.confidence * 100)}%`}</dd>
          </div>
        </dl>
        <p>{d.reason}</p><p>Buyer action: {d.buyerAction}</p>
      </li>;
    })}</ol>
  </section>;
}
