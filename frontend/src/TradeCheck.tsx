import { useId } from "react";
import { TradeTacticAnnotation, type DeskTacticFinding } from "./DeskTactics";

export type TradeCheckFinding = {
  code: string; severity: "BLOCKING" | "WARNING" | "INFORMATIONAL"; summary: string;
  amountMinor: number | null; deskQuestion: string | null; estimate: boolean;
};
/** The trade-in part of a Desk Check or report (ticket 103), as the server computed it. */
export type TradeCheckResult = {
  status: "TRADE_NOT_RECORDED" | "CHECKED" | "NO_ALLOWANCE";
  allowanceMinor: number | null; paperworkPayoffMinor: number | null;
  buyerPayoff: null | { state: "CURRENT" | "STALE" | "UNKNOWN" | "NOT_OWED"; amountMinor: number | null; goodThrough: string | null };
  payoffDifferenceMinor: number | null; equityMinor: number | null; equityBasis: string;
  negativeEquityFinancedMinor: number | null;
  negativeEquityCost: null | { status: "AVAILABLE" | "UNAVAILABLE"; reason: string | null; aprPercent: number | null; termMonths: number | null;
    paymentIncreaseMinor: number | null; scheduledPaymentsIncreaseMinor: number | null; interestMinor: number | null };
  bestOffer: null | { id: string; amountMinor: number; source: string; offerDate: string; expiresOn: string; label: string };
  shortfallToOfferMinor: number | null;
  taxCredit: null | { status: "AVAILABLE" | "NOT_APPLICABLE" | "UNAVAILABLE"; reason: string | null; jurisdiction: string | null;
    policyVersion: string | null; creditMinor: number | null };
  offerGapAfterTaxMinor: number | null;
  valuation: null | { availability: string; notice: string | null; source: string | null; synthetic: boolean; retrievedAt: string | null;
    conditionAssumption: string | null; lowMinor: number | null; typicalMinor: number | null; highMinor: number | null; position: string | null;
    /** trade-desk-check.v2: BUYER_ESTIMATE when the Buyer's own looked-up value stood in (ticket 106); absent on v1 results. */
    basis?: "PROVIDER" | "BUYER_ESTIMATE"; checkedOn?: string | null; old?: boolean | null; label?: string | null };
  vinMatch: null | { status: "SAME" | "DIFFERENT" | "NOT_ON_PAPERWORK" | "NO_TRADE_RECORDED"; paperworkVin: string | null; recordedVin: string | null };
  findings: TradeCheckFinding[];
};

function money(minor: number) {
  return `${minor < 0 ? "−" : ""}${new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(Math.abs(minor) / 100)}`;
}

const PAYOFF_STATE: Record<string, string> = {
  CURRENT: "", STALE: " (past its valid-through date; get a current payoff quote)", UNKNOWN: "Unknown", NOT_OWED: "Nothing owed",
};

function yourPayoff(payoff: NonNullable<TradeCheckResult["buyerPayoff"]>) {
  if (payoff.state === "UNKNOWN" || payoff.state === "NOT_OWED") return PAYOFF_STATE[payoff.state];
  return `${money(payoff.amountMinor ?? 0)} valid through ${payoff.goodThrough}${PAYOFF_STATE[payoff.state]}`;
}

/** Trade findings first, then the figures they rest on. Range figures are labelled as estimates from their source. */
export function TradeCheckPanel({ result, heading = "Trade-in check", tactics = [] }: {
  result: TradeCheckResult; heading?: string; tactics?: DeskTacticFinding[];
}) {
  const id = useId();
  if (result.status === "NO_ALLOWANCE") return null;
  const valuation = result.valuation;
  const cost = result.negativeEquityCost;
  const tax = result.taxCredit;
  return <section className="trade-check" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>{heading}</h3>
    <ol className="trade-check-findings" aria-label="Trade-in findings">
      {result.findings.map(finding => {
        const tactic = tactics.find(item => item.tradeFindingCode === finding.code);
        return <li key={finding.code} data-severity={finding.severity}>
        <p>{finding.summary}</p>
        {(finding.estimate || finding.deskQuestion) && <p className="desk-check-meta">
          {finding.estimate && <span className="badge">Estimate</span>}
          {finding.deskQuestion && !tactic && <span>Ask at the desk: <q>{finding.deskQuestion}</q></span>}
        </p>}
        {tactic && <TradeTacticAnnotation finding={tactic} />}
      </li>; })}
    </ol>
    <dl className="trade-check-figures">
      {result.allowanceMinor !== null && <div><dt>Trade-in allowance on the paperwork</dt><dd>{money(result.allowanceMinor)}</dd></div>}
      {result.status === "CHECKED" && <div><dt>Best unexpired written offer</dt>
        <dd>{result.bestOffer ? `${money(result.bestOffer.amountMinor)} from ${result.bestOffer.source}, dated ${result.bestOffer.offerDate}, expires ${result.bestOffer.expiresOn}` : "None recorded"}</dd></div>}
      {valuation?.basis === "BUYER_ESTIMATE" && valuation.lowMinor !== null && valuation.highMinor !== null && <div>
        <dt>{valuation.label ? valuation.label.charAt(0).toUpperCase() + valuation.label.slice(1) : "Your estimate"}</dt>
        <dd>{valuation.lowMinor === valuation.highMinor ? money(valuation.lowMinor) : `${money(valuation.lowMinor)} to ${money(valuation.highMinor)}`}.
          {" "}An outside estimate you looked up; otdog did not check it, and it is not an offer.
          {valuation.old && <> <span className="badge badge-warn">Old</span> Looked up more than 30 days ago; look it up again before relying on it.</>}</dd></div>}
      {valuation && valuation.basis !== "BUYER_ESTIMATE" && <div><dt>Trade-in range{valuation.source ? `, an estimate from ${valuation.source}` : ""}</dt>
        <dd>{valuation.availability === "AVAILABLE" && valuation.lowMinor !== null && valuation.highMinor !== null
          ? `${money(valuation.lowMinor)} to ${money(valuation.highMinor)}${valuation.conditionAssumption ? `, assuming ${valuation.conditionAssumption.toLowerCase()} condition` : ""}${valuation.retrievedAt ? `, retrieved ${valuation.retrievedAt.slice(0, 10)}` : ""}${valuation.notice ? `. ${valuation.notice}` : ""}`
          : `Unavailable. ${valuation.notice ?? ""}`}</dd></div>}
      <div><dt>Payoff on the paperwork</dt><dd>{result.paperworkPayoffMinor !== null ? money(result.paperworkPayoffMinor) : "Not shown"}</dd></div>
      {result.buyerPayoff && <div><dt>Your payoff</dt><dd>{yourPayoff(result.buyerPayoff)}</dd></div>}
      <div><dt>Trade equity</dt><dd>{result.equityMinor !== null ? money(result.equityMinor) : "Unavailable until the payoff is known"}</dd></div>
      {result.negativeEquityFinancedMinor !== null && <div><dt>Negative equity in the amount financed</dt>
        <dd>{money(result.negativeEquityFinancedMinor)}{cost?.status === "AVAILABLE" && cost.paymentIncreaseMinor !== null
          && cost.scheduledPaymentsIncreaseMinor !== null && cost.interestMinor !== null
          ? `: ${money(cost.paymentIncreaseMinor)} a month, ${money(cost.scheduledPaymentsIncreaseMinor)} in scheduled payments for ${cost.termMonths} months, ${money(cost.interestMinor)} of it interest`
          : cost?.reason ? `. ${cost.reason}` : ""}</dd></div>}
      {tax && <div><dt>Sales tax a trade-in removes</dt>
        <dd>{tax.status === "AVAILABLE" && tax.creditMinor !== null ? `About ${money(tax.creditMinor)} under ${tax.jurisdiction} rules (${tax.policyVersion})`
          : tax.status === "NOT_APPLICABLE" ? `None under ${tax.jurisdiction} rules` : tax.reason}
          {tax.status === "AVAILABLE" && tax.reason && <>. {tax.reason}</>}</dd></div>}
      {result.vinMatch?.paperworkVin && <div><dt>Trade-in VIN on the paperwork</dt><dd>{result.vinMatch.paperworkVin}</dd></div>}
    </dl>
    <p className="muted">A trade-in shortfall is a trade figure, never a discount on the car. otdog does not buy cars or send your trade anywhere.</p>
  </section>;
}
