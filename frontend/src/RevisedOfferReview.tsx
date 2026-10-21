import { DocumentSourceView, formatMoney, type DocumentSource } from "./EvidenceSource";
import { DocFeeFindings, type DocFeeFinding } from "./DocFeeFindings";
import { DealerAddOnsNote, type DealerAddOnCheck } from "./DealerAddOns";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

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
  docFeeFindings?: DocFeeFinding[];
  dealerAddOns?: DealerAddOnCheck | null;
};

type Copy = Messages["paperwork"]["revisedOfferReview"];
type Fill = ReturnType<typeof useMessages>["fill"];
type Fields = Record<string, unknown>;
const fields = (v: unknown): Fields | null => v != null && typeof v === "object" && !Array.isArray(v) ? v as Fields : null;
const signed = (minor: number, currency: string, unknown: string) => `${minor > 0 ? "+" : ""}${formatMoney(minor, currency, unknown)}`;

/** Plain value of a frozen term: money, percent or wording, never the serialized object. */
function termValue(v: unknown, unit: string, t: Copy, unknown: string): string {
  if (v == null) return t.notPresent;
  const term = fields(v);
  if (!term) return unit.endsWith("_MINOR") && typeof v === "number" ? formatMoney(v, unit.replace("_MINOR", ""), unknown) : String(v);
  if (typeof term.amountMinor === "number") return formatMoney(term.amountMinor, typeof term.currency === "string" ? term.currency : "USD", unknown);
  const raw = term.value;
  if (raw == null) return t.missing;
  const termUnit = typeof term.unit === "string" ? term.unit : unit;
  if (termUnit.endsWith("_MINOR") && !Number.isNaN(Number(raw))) return formatMoney(Number(raw), termUnit.replace("_MINOR", ""), unknown);
  if (termUnit === "PERCENT") return `${raw}%`;
  return String(raw);
}

function classification(v: unknown, t: Copy, fill: Fill): string | null {
  const term = fields(v);
  if (!term) return null;
  const parts = ([["category", t.category], ["taxClaim", t.tax], ["optionalityClaim", t.optional]] as const)
    .filter(([key]) => typeof term[key] === "string")
    .map(([key, name]) => fill(name, { value: String(term[key]).replaceAll("_", " ").toLowerCase() }));
  return parts.length ? parts.join(" · ") : null;
}

function termName(term: string, t: Copy, fill: Fill) {
  const [, kind, name, occurrence] = term.match(/^(candidate|line):(.*)#(\d+)$/) ?? [];
  if (!kind) return (t.limits as Record<string, string>)[term] ?? term.replace(/Minor$/, "").replace(/([a-z])([A-Z])/g, "$1 $2").replaceAll(".", " · ");
  const readable = kind === "candidate" ? name.replaceAll("_", " ").toLowerCase() : name;
  const named = `${readable.charAt(0).toUpperCase()}${readable.slice(1)}`;
  return occurrence !== "1" ? fill(t.occurrence, { name: named, occurrence }) : named;
}

function difference(d: DealReview["discrepancies"][number], t: Copy, fill: Fill, unknown: string) {
  if (d.delta == null) return t.notComparable;
  if (d.unit.endsWith("_MINOR")) return signed(d.delta, d.unit.replace("_MINOR", ""), unknown);
  if (d.unit === "PERCENT") return fill(t.percentagePoints, { delta: `${d.delta > 0 ? "+" : ""}${d.delta}` });
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
  const { m: { paperwork: { revisedOfferReview: t } }, fill } = useMessages();
  const source = documentSource(evidence, offerVersion);
  if (source) return <DocumentSourceView source={source} what={fill(side === "expected" ? t.expectedTerm : t.observedTerm, { term })} />;
  const recorded = fields(evidence);
  // An example report strips document pages and regions; that is a removal, not a missing line.
  if (redacted && !absent && !(recorded && (typeof recorded.baselineId === "string" || typeof recorded.basis === "string")))
    return <p>{t.sourceRemoved}</p>;
  if (recorded && typeof recorded.baselineId === "string")
    return <p>{baselineVersion != null ? fill(t.sourceBaselineVersion, { version: baselineVersion }) : t.sourceBaseline}</p>;
  if (recorded && typeof recorded.basis === "string")
    return <p>{fill(t.sourceBasis, { version: String(offerVersion), basis: recorded.basis.toLowerCase() })}</p>;
  if (evidence == null) return <p>{side === "expected" ? t.sourceNotInEarlier : t.sourceNotInCurrent}</p>;
  return <p>{t.sourceNoRegion}</p>;
}

const comparisonName = (comparison: string, t: Copy) => comparison === "BASELINE" ? t.baseline : comparison === "PREVIOUS" ? t.previous : comparison.toLowerCase();

export function RevisedOfferReview({ review, redacted }: { review: DealReview; redacted?: boolean }) {
  const { m: { paperwork: { revisedOfferReview: t, common } }, fill, fillNodes } = useMessages();
  const unknown = common.unknown;
  const expectedVersion = (comparison: string) => comparison === "PREVIOUS" ? review.previousOfferVersion : null;
  return <section className="panel" id="revised-offer-review" tabIndex={-1} aria-labelledby="revised-review-heading">
    <h3 id="revised-review-heading">{t.heading}</h3>
    <p>{review.baselineVersion != null
      ? fill(t.versions, { baseline: review.baselineVersion, previous: review.previousOfferVersion, current: review.currentOfferVersion })
      : fill(t.versionsNoBaseline, { previous: review.previousOfferVersion, current: review.currentOfferVersion })}</p>
    <p>{fill(t.reviewLine, { version: review.version, status: review.status.replaceAll("_", " "), checked: review.checkedAt })}</p>
    <p>{review.scope}</p>
    <DocFeeFindings findings={review.docFeeFindings} />
    {review.dealerAddOns && <DealerAddOnsNote check={review.dealerAddOns} />}
    <p>{fill(t.allDifferences, { policy: review.tolerancePolicy })}</p>
    {Object.entries(review.cashDeltas).map(([comparison, delta]) => {
      const money = (minor: number | undefined) => minor == null ? t.unavailable : formatMoney(minor, delta.currency ?? "USD", unknown);
      const name = comparisonName(comparison.toUpperCase(), t);
      const sides = delta.expectedTaxesMinor != null && delta.observedTaxesMinor != null;
      return <section key={comparison} id={`cash-delta-${comparison}`} tabIndex={-1} aria-label={fill(t.cashDifference, { comparison })}>
        <h4>{fill(t.cashDifference, { comparison })}</h4>
        {delta.availability === "UNAVAILABLE" ? <p>{fill(t.unavailableReason, { reason: delta.reason ?? "" })}</p> : <dl>
          <dt>{t.outTheDoorDifference}</dt>
          <dd>{sides && delta.expectedOutTheDoorMinor != null && delta.observedOutTheDoorMinor != null
            ? fill(t.outTheDoorCalculation, { observed: money(delta.observedOutTheDoorMinor), name, expected: money(delta.expectedOutTheDoorMinor), difference: money(delta.outTheDoorDeltaMinor) })
            : money(delta.outTheDoorDeltaMinor)}</dd>
          <dt>{t.taxEffectSeparately}</dt>
          <dd>{sides ? fill(t.taxCalculation, { observed: money(delta.observedTaxesMinor), name, expected: money(delta.expectedTaxesMinor), difference: money(delta.taxEffectMinor) }) : money(delta.taxEffectMinor)}</dd>
          <dt>{t.preTaxDifference}</dt>
          <dd>{fill(t.preTaxCalculation, { outTheDoor: money(delta.outTheDoorDeltaMinor), taxEffect: money(delta.taxEffectMinor), preTax: money(delta.preTaxDeltaMinor) })}</dd>
        </dl>}
        {delta.availability !== "UNAVAILABLE" && <p>{delta.reason}</p>}
      </section>;
    })}
    {review.discrepancies.length === 0 && <p>{t.noDifferences}</p>}
    <ol>{review.discrepancies.map((d, index) => {
      const name = termName(d.term, t, fill);
      const cash = review.cashDeltas[d.comparison.toLowerCase()];
      const expectedClass = classification(d.expected, t, fill), observedClass = classification(d.observed, t, fill);
      return <li key={index}>
        <h4>{fill(t.termHeading, { term: name, comparison: comparisonName(d.comparison, t) })}</h4>
        <p>{fill(d.uncertain ? t.kindUncertain : t.kind, { kind: d.kind.replaceAll("_", " ").toLowerCase(), severity: d.severity })}</p>
        <dl className="expected-observed">
          <div>
            <dt>{fill(t.expected, { comparison: comparisonName(d.comparison, t) })}</dt>
            <dd>{termValue(d.expected, d.unit, t, unknown)}{expectedClass && <small> · {expectedClass}</small>}
              <SourceLine evidence={d.expectedEvidence} side="expected" offerVersion={expectedVersion(d.comparison)} baselineVersion={review.baselineVersion} term={name} absent={d.kind === "ADDED"} redacted={redacted} /></dd>
          </div>
          <div>
            <dt>{fill(t.observed, { version: review.currentOfferVersion })}</dt>
            <dd>{termValue(d.observed, d.unit, t, unknown)}{observedClass && <small> · {observedClass}</small>}
              <SourceLine evidence={d.observedEvidence} side="observed" offerVersion={review.currentOfferVersion} baselineVersion={review.baselineVersion} term={name} absent={d.kind === "MISSING"} redacted={redacted} /></dd>
          </div>
          <div>
            <dt>{t.difference}</dt>
            <dd>{difference(d, t, fill, unknown)}</dd>
          </div>
          <div>
            <dt>{t.taxEffect}</dt>
            <dd>{cash && cash.availability !== "UNAVAILABLE"
              ? fillNodes(t.taxPerOffer, { amount: formatMoney(cash.taxEffectMinor, cash.currency ?? "USD", unknown),
                link: <a href={`#cash-delta-${d.comparison.toLowerCase()}`}>{fill(t.seeCashDifference, { comparison: d.comparison.toLowerCase() })}</a> })
              : t.taxUnavailable}</dd>
          </div>
          <div>
            <dt>{t.confidence}</dt>
            <dd>{d.confidence == null ? t.unavailable : `${Math.round(d.confidence * 100)}%`}</dd>
          </div>
        </dl>
        <p>{d.reason}</p><p>{fill(t.buyerAction, { action: d.buyerAction })}</p>
      </li>;
    })}</ol>
  </section>;
}
