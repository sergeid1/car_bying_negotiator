import { useId } from "react";
import { TradeTacticAnnotation, type DeskTacticFinding } from "./DeskTactics";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";
import { findingCode } from "./i18n/findings";

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
    policyVersion: string | null; creditMinor: number | null; taxName?: string | null };
  offerGapAfterTaxMinor: number | null;
  valuation: null | { availability: string; notice: string | null; source: string | null; synthetic: boolean; retrievedAt: string | null;
    conditionAssumption: string | null; lowMinor: number | null; typicalMinor: number | null; highMinor: number | null; position: string | null;
    /** trade-desk-check.v2: BUYER_ESTIMATE when the Buyer's own looked-up value stood in (ticket 106); absent on v1 results. */
    basis?: "PROVIDER" | "BUYER_ESTIMATE"; checkedOn?: string | null; old?: boolean | null; label?: string | null };
  vinMatch: null | { status: "SAME" | "DIFFERENT" | "NOT_ON_PAPERWORK" | "NO_TRADE_RECORDED"; paperworkVin: string | null; recordedVin: string | null };
  findings: TradeCheckFinding[];
};

function money(minor: number) {
  return `${minor < 0 ? "−" : ""}${format.money(Math.abs(minor))}`;
}

type Copy = Messages["dealer"]["tradeCheck"];
type Fill = ReturnType<typeof useMessages>["fill"];

/** The state's own name for the tax, such as "North Carolina highway use tax"; "Sales tax" before ticket 137. */
function taxHeading(t: Copy, fill: Fill, name: string | null | undefined) {
  if (name == null) return t.salesTaxHeading;
  return fill(t.taxHeading, { tax: `${name.charAt(0).toUpperCase()}${name.slice(1)}` });
}

function yourPayoff(t: Copy, fill: Fill, payoff: NonNullable<TradeCheckResult["buyerPayoff"]>) {
  if (payoff.state === "UNKNOWN") return t.payoffUnknown;
  if (payoff.state === "NOT_OWED") return t.payoffNotOwed;
  const values = { amount: money(payoff.amountMinor ?? 0), date: String(payoff.goodThrough) };
  return fill(payoff.state === "STALE" ? t.payoffStale : t.payoffCurrent, values);
}

/** Trade findings first, then the figures they rest on. Range figures are labelled as estimates from their source. */
export function TradeCheckPanel({ result, heading, tactics = [] }: {
  result: TradeCheckResult; heading?: string; tactics?: DeskTacticFinding[];
}) {
  const { m: { dealer: { tradeCheck: t, common } }, fill, fillNodes, language } = useMessages();
  const id = useId();
  if (result.status === "NO_ALLOWANCE") return null;
  const valuation = result.valuation;
  const cost = result.negativeEquityCost;
  const tax = result.taxCredit;
  return <section className="trade-check" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>{heading ?? t.heading}</h3>
    <ol className="trade-check-findings" aria-label={t.findings}>
      {result.findings.map(finding => {
        const tactic = tactics.find(item => item.tradeFindingCode === finding.code);
        return <li key={finding.code} data-severity={finding.severity}>
        <p>{finding.summary}</p>
        {(finding.estimate || finding.deskQuestion) && <p className="desk-check-meta">
          {finding.estimate && <span className="badge">{t.estimate}</span>}
          {finding.deskQuestion && !tactic && <span>{fillNodes(t.askAtDesk, { question: <q>{finding.deskQuestion}</q> })}</span>}
        </p>}
        {tactic && <TradeTacticAnnotation finding={tactic} />}
      </li>; })}
    </ol>
    <dl className="trade-check-figures">
      {result.allowanceMinor !== null && <div><dt>{t.allowance}</dt><dd>{money(result.allowanceMinor)}</dd></div>}
      {result.status === "CHECKED" && <div><dt>{t.bestOffer}</dt>
        <dd>{result.bestOffer ? fill(t.bestOfferValue, { amount: money(result.bestOffer.amountMinor), source: result.bestOffer.source,
          date: result.bestOffer.offerDate, expires: result.bestOffer.expiresOn }) : t.noneRecorded}</dd></div>}
      {valuation?.basis === "BUYER_ESTIMATE" && valuation.lowMinor !== null && valuation.highMinor !== null && <div>
        <dt>{valuation.label ? valuation.label.charAt(0).toUpperCase() + valuation.label.slice(1) : t.yourEstimate}</dt>
        <dd>{valuation.lowMinor === valuation.highMinor ? money(valuation.lowMinor)
          : fill(common.range, { low: money(valuation.lowMinor), high: money(valuation.highMinor) })}.{" "}{t.outsideEstimate}
          {valuation.old && <> <span className="badge badge-warn">{t.old}</span> {t.oldNote}</>}</dd></div>}
      {valuation && valuation.basis !== "BUYER_ESTIMATE" && <div><dt>{valuation.source ? fill(t.rangeFrom, { source: valuation.source }) : t.range}</dt>
        <dd>{valuation.availability === "AVAILABLE" && valuation.lowMinor !== null && valuation.highMinor !== null
          ? [fill(common.range, { low: money(valuation.lowMinor), high: money(valuation.highMinor) }),
            valuation.conditionAssumption ? fill(t.assuming, { condition: findingCode(language, valuation.conditionAssumption).toLocaleLowerCase(language) }) : "",
            valuation.retrievedAt ? fill(t.retrieved, { date: valuation.retrievedAt.slice(0, 10) }) : ""].filter(Boolean).join(", ")
            + (valuation.notice ? `. ${valuation.notice}` : "")
          : fill(t.unavailableNotice, { notice: valuation.notice ?? "" })}</dd></div>}
      <div><dt>{t.paperworkPayoff}</dt><dd>{result.paperworkPayoffMinor !== null ? money(result.paperworkPayoffMinor) : t.notShown}</dd></div>
      {result.buyerPayoff && <div><dt>{t.yourPayoff}</dt><dd>{yourPayoff(t, fill, result.buyerPayoff)}</dd></div>}
      <div><dt>{t.equity}</dt><dd>{result.equityMinor !== null ? money(result.equityMinor) : t.equityUnavailable}</dd></div>
      {result.negativeEquityFinancedMinor !== null && <div><dt>{t.negativeEquity}</dt>
        <dd>{cost?.status === "AVAILABLE" && cost.paymentIncreaseMinor !== null
          && cost.scheduledPaymentsIncreaseMinor !== null && cost.interestMinor !== null
          ? fill(t.negativeEquityCost, { amount: money(result.negativeEquityFinancedMinor), payment: money(cost.paymentIncreaseMinor),
            scheduled: money(cost.scheduledPaymentsIncreaseMinor), months: String(cost.termMonths), interest: money(cost.interestMinor) })
          : money(result.negativeEquityFinancedMinor) + (cost?.reason ? `. ${cost.reason}` : "")}</dd></div>}
      {tax && <div><dt>{taxHeading(t, fill, tax.taxName)}</dt>
        <dd>{tax.status === "AVAILABLE" && tax.creditMinor !== null
          ? fill(t.taxCredit, { amount: money(tax.creditMinor), jurisdiction: String(tax.jurisdiction), policy: String(tax.policyVersion) })
          : tax.status === "NOT_APPLICABLE" ? fill(t.taxNone, { jurisdiction: String(tax.jurisdiction) }) : tax.reason}
          {tax.status === "AVAILABLE" && tax.reason && <>. {tax.reason}</>}</dd></div>}
      {result.vinMatch?.paperworkVin && <div><dt>{t.tradeVin}</dt><dd>{result.vinMatch.paperworkVin}</dd></div>}
    </dl>
    <p className="muted">{t.shortfall}</p>
  </section>;
}
