import { useEffect, useRef, useState } from "react";
import { BuyingCaseIntake } from "./BuyingCaseIntake";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { TradeCheckPanel, type TradeCheckResult } from "./TradeCheck";
import { DeskTactics, type DeskTacticFinding } from "./DeskTactics";

type Credentials = { caseId: string; token: string };
type Term = { value?: string | null; unit?: string; amountMinor?: number; currency?: string; category?: string } | number;
export type DeskFinding = {
  group: string; term: string; label: string; kind: string; agreed: Term | null; desk: Term | null;
  delta: number | null; unit: string | null; severity: string; verified: boolean;
  evidence: { pageNumber?: number } | null; buyerAction: string | null;
};
export type DeskCheckView = {
  status: "NO_AGREED_PRICE" | "AWAITING_PAPERWORK" | "READING" | "DIFFERENT_VIN" | "DIFFERENCES" | "NO_DIFFERENCES";
  source: "READ_FROM_PAPERWORK" | "VERIFIED_OFFER" | "DEAL_REVIEW" | null;
  agreedPrice: null | { offerVersion: number; baselineVersion: number; vin: string | null;
    sellingPriceMinor: number | null; outTheDoorMinor: number | null; acceptedAt: string };
  paperwork: null | { documentId: string; uploadedAt: string; offerVersion: number | null; vin: string | null };
  findings: DeskFinding[]; otherDifferences: DeskFinding[]; unchangedTerms: number;
  totalEffect: { basis: "CALCULATED" | "QUOTED" | "UNAVAILABLE"; currency: string; outTheDoorDeltaMinor: number | null;
    preTaxDeltaMinor: number | null; taxEffectMinor: number | null; addedChargesMinor: number | null };
  freshCase: null | { vin: string; dealerPostalCode: string | null; purchaseMethod: string };
  dealReviewVersion: number | null;
  trade?: TradeCheckResult | null;
  tacticFindings?: DeskTacticFinding[];
};
export type FreshDeskCase = Credentials & { agreed: Credentials; agreedVin: string | null; vin: string; files: File[] };

const LABELS: Record<string, string> = {
  SELLING_PRICE: "Selling price", DISCOUNT_TOTAL: "Discount", REBATE_TOTAL: "Rebate",
  OUT_THE_DOOR_TOTAL: "Out-the-door total", SUBTOTAL: "Subtotal", TAX_TOTAL: "Taxes",
  DEALER_CHARGES_TOTAL: "Dealer charges", GOVERNMENT_CHARGES_TOTAL: "Government charges", ADD_ON_TOTAL: "Add-ons total",
  AMOUNT_FINANCED: "Amount financed", APR: "APR", TERM_MONTHS: "Loan term", MONTHLY_PAYMENT: "Monthly payment",
  FINANCE_CHARGE: "Finance charge", TOTAL_OF_PAYMENTS: "Total of payments", CASH_DOWN: "Cash down",
  TRADE_ALLOWANCE: "Trade-in allowance", TRADE_PAYOFF: "Trade-in payoff", VEHICLE_VIN: "VIN", TRADE_VIN: "Trade-in VIN",
  "sellingPrice.walkAwayMinor": "Your walk-away selling price", buyerMaxSellingPriceMinor: "Your maximum selling price",
  "outTheDoor.walkAwayMinor": "Your walk-away out-the-door price", buyerMaxOutTheDoorPriceMinor: "Your maximum out-the-door price",
};

function money(minor: number, signed = false) {
  const text = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(Math.abs(minor) / 100);
  return signed ? `${minor < 0 ? "−" : "+"}${text}` : minor < 0 ? `−${text}` : text;
}
function labelOf(finding: DeskFinding) {
  return LABELS[finding.label] ?? (finding.term.startsWith("line:") ? finding.label
    : finding.label.toLowerCase().replaceAll("_", " ").replace(/^./, first => first.toUpperCase()));
}
function show(term: Term | null) {
  if (term === null || term === undefined) return "nothing";
  if (typeof term === "number") return money(term);
  if (typeof term.amountMinor === "number") return money(term.amountMinor);
  if (term.value === null || term.value === undefined) return "unreadable";
  if (term.unit === "USD_MINOR" && /^-?\d+$/.test(term.value)) return money(Number(term.value));
  if (term.unit === "PERCENT") return `${term.value}%`;
  if (term.unit === "MONTHS") return `${term.value} months`;
  return term.value;
}
function moneyDelta(finding: DeskFinding) {
  return finding.delta !== null && (finding.unit ?? "").endsWith("_MINOR") && finding.delta !== 0 ? ` (${money(finding.delta, true)})` : "";
}
/** One plain sentence per difference, saying what changed relative to the Agreed Price. */
export function describeFinding(finding: DeskFinding) {
  const label = labelOf(finding);
  switch (finding.group) {
    case "VEHICLE": return `This paperwork is for VIN ${show(finding.desk)}, but your Agreed Price is for VIN ${show(finding.agreed)}.`;
    case "ADDED": return `Added: ${label}, ${show(finding.desk)}. It is not in your Agreed Price.`;
    case "REMOVED": return `Missing: ${label}, ${show(finding.agreed)} in your Agreed Price, is not on this paperwork.`;
    case "LIMIT": return `${label} of ${show(finding.agreed)} is exceeded by ${money(finding.delta ?? 0)}.`;
    default:
      if (finding.desk === null) return `${label} of ${show(finding.agreed)} in your Agreed Price is not on this paperwork.`;
      if (finding.agreed === null) return `${label} is new on this paperwork: ${show(finding.desk)}.`;
      return `${label} changed from ${show(finding.agreed)} to ${show(finding.desk)}${moneyDelta(finding)}.`;
  }
}
/** A trade finding's sentence comes from the trade check itself (ticket 103). */
function describe(view: DeskCheckView, finding: DeskFinding) {
  if (finding.term.startsWith("trade:")) {
    const trade = view.trade?.findings.find(item => `trade:${item.code}` === finding.term);
    if (trade) return trade.summary;
  }
  return describeFinding(finding);
}
/** The lead line: how many differences and what they do to the total. */
export function totalEffectSentence(view: DeskCheckView) {
  const effect = view.totalEffect;
  const parts: string[] = [];
  if (effect.outTheDoorDeltaMinor !== null) {
    const amount = money(Math.abs(effect.outTheDoorDeltaMinor));
    parts.push(effect.outTheDoorDeltaMinor === 0 ? "The out-the-door total is the same as agreed"
      : `The out-the-door total is ${amount} ${effect.outTheDoorDeltaMinor > 0 ? "higher" : "lower"} than agreed`
        + (effect.basis === "CALCULATED" ? ", calculated from the verified terms" : ", as printed on the paperwork"));
  } else parts.push("The total effect is unavailable until the paperwork's total can be read");
  if (effect.basis === "CALCULATED" && effect.preTaxDeltaMinor !== null && effect.taxEffectMinor !== null)
    parts.push(`${money(effect.preTaxDeltaMinor, true)} before tax and ${money(effect.taxEffectMinor, true)} in tax`);
  if (effect.addedChargesMinor) parts.push(`added charges come to ${money(effect.addedChargesMinor)}`);
  return parts.join("; ") + ".";
}

function asView(value: unknown): DeskCheckView {
  const v = value as Partial<DeskCheckView> | null;
  if (!v || typeof v.status !== "string" || !Array.isArray(v.findings) || !Array.isArray(v.otherDifferences) || !v.totalEffect)
    throw new Error("The Desk Check is unavailable. Try refreshing.");
  return v as DeskCheckView;
}

export function DeskCheck({ caseId, token, refreshKey = 0, onFreshCase }: Credentials & {
  refreshKey?: number; onFreshCase?: (value: FreshDeskCase) => void;
}) {
  const [view, setView] = useState<DeskCheckView>();
  const [error, setError] = useState<string>();
  const [starting, setStarting] = useState(false);
  const files = useRef<File[]>([]);
  const heading = useRef<HTMLHeadingElement>(null);
  const shown = useRef(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/desk-check`;
  async function load() {
    const response = await fetch(path, { headers: { "Case-Access-Token": token } });
    if (response.status === 402) throw new Error("The Desk Check uses this case's report, which needs the one-time purchase first.");
    if (!response.ok) throw new Error("The Desk Check is unavailable. Try refreshing.");
    return asView(await response.json());
  }
  async function refresh() {
    setError(undefined);
    try { setView(await load()); }
    catch (failure) { setError((failure as Error).message); }
  }
  useEffect(() => {
    let current = true;
    load().then(value => { if (current) setView(value); })
      .catch(failure => { if (current) setError((failure as Error).message); });
    return () => { current = false; };
    // The case, its token and an outside change (a newly verified offer) define this check.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token, refreshKey]);
  useEffect(() => {
    // At the desk the check comes first: bring it into view once when the case has an Agreed Price.
    if (view && view.status !== "NO_AGREED_PRICE" && !shown.current) {
      shown.current = true;
      heading.current?.scrollIntoView?.({ block: "start" });
    }
  }, [view]);

  if (!view && !error) return null;
  if (view?.status === "NO_AGREED_PRICE") return null;
  const agreed = view?.agreedPrice;
  const read = view?.source === "READ_FROM_PAPERWORK";
  const shownFindings = view?.findings.filter(finding => !view.tacticFindings?.some(tactic =>
    tactic.tradeFindingCode && finding.term === `trade:${tactic.tradeFindingCode}`)) ?? [];
  return <section className="stack desk-check" aria-label="Desk Check">
    <div className="panel desk-check-result">
      <div className="section-heading"><div>
        <p className="step">At the dealership</p>
        <h2 id="desk-check-heading" ref={heading} tabIndex={-1}>Desk Check</h2>
      </div></div>
      {agreed && <p className="muted">Compares the paperwork presented at the desk with your Agreed Price
        {agreed.outTheDoorMinor !== null ? ` of ${money(agreed.outTheDoorMinor)} out the door` : ""}
        {agreed.vin ? ` for VIN ${agreed.vin}` : ""}.</p>}
      {view?.status === "AWAITING_PAPERWORK" && <p role="status">Photograph the paperwork the dealer gives you. Any difference from your Agreed Price is listed first.</p>}
      {view?.status === "READING" && <p role="status">Reading the desk paperwork. The comparison appears here when the terms are read.</p>}
      {view?.status === "DIFFERENT_VIN" && view.freshCase && <>
        <p role="alert">Different car: this paperwork is for VIN {view.freshCase.vin}, not the agreed car{agreed?.vin ? ` (VIN ${agreed.vin})` : ""}. Nothing on it was compared with or added to your Agreed Price.</p>
        <p>If you are switching to this car, or it is the car you want checked, start a separate case for it. That case gets its own analysis, including your state's fees and taxes, and your Agreed Price stays as it is.</p>
        {!starting && <button type="button" onClick={() => setStarting(true)}>Start a fresh case for this car</button>}
      </>}
      {(view?.status === "DIFFERENCES" || view?.status === "NO_DIFFERENCES") && <>
        <p role="status" className="desk-check-lead">{view.status === "DIFFERENCES"
          ? `${view.findings.length} ${view.findings.length === 1 ? "difference" : "differences"} from your Agreed Price. ${totalEffectSentence(view)}`
          : `No differences from your Agreed Price in the ${read ? "terms as read" : "verified terms"}. ${totalEffectSentence(view)}`}</p>
        {shownFindings.length > 0 && <ol className="desk-check-findings" aria-label="Differences from your Agreed Price">
          {shownFindings.map(finding => <li key={`${finding.group}-${finding.term}`} data-severity={finding.severity}>
            <p>{describe(view, finding)}</p>
            <p className="desk-check-meta">
              <span className={finding.verified ? "cash-badge" : "badge badge-warn"}>{finding.verified ? "Verified" : "As read, not verified"}</span>
              {finding.evidence?.pageNumber ? <span> Page {finding.evidence.pageNumber}.</span> : null}
              {finding.buyerAction ? <span> {finding.buyerAction}</span> : null}
            </p>
          </li>)}
        </ol>}
        {view.trade && <TradeCheckPanel result={view.trade} tactics={view.tacticFindings} />}
        {view.otherDifferences.length > 0 && <details>
          <summary>{view.otherDifferences.length} other {view.otherDifferences.length === 1 ? "change" : "changes"} on the paperwork</summary>
          <ul>{view.otherDifferences.map(finding => <li key={`${finding.group}-${finding.term}`}>
            {describeFinding(finding)} {finding.verified ? "Verified." : "As read, not verified."}
          </li>)}</ul>
        </details>}
        <p className="muted">{read
          ? "These terms are as read from the paperwork and are not yet verified. Check each one against the page in “Check the terms against the paperwork” before relying on it."
          : view.source === "DEAL_REVIEW"
            ? `From saved Deal Review ${view.dealReviewVersion}, against the Approved Baseline of your Agreed Price.`
            : "From the verified paperwork terms. Open the analysis of this offer to save the comparison as a Deal Review."}</p>
        {read && <button type="button" className="btn-secondary" onClick={() => {
          const target = document.getElementById("verify-heading");
          target?.scrollIntoView?.({ block: "start" }); target?.focus();
        }}>Verify these terms</button>}
      </>}
      <button type="button" className="btn-secondary" onClick={() => void refresh()}>Refresh Desk Check</button>
      <DeskTactics findings={view?.tacticFindings} />
      {error && <p role="alert">{error}</p>}
    </div>
    {starting && view?.freshCase && <BuyingCaseIntake title={`Start a fresh case for VIN ${view.freshCase.vin}`}
      initial={{ vin: view.freshCase.vin, dealerPostalCode: view.freshCase.dealerPostalCode ?? undefined, purchaseMethod: view.freshCase.purchaseMethod }}
      onCaseOpened={value => onFreshCase?.({ ...value, agreed: { caseId, token }, agreedVin: agreed?.vin ?? null,
        vin: view.freshCase!.vin, files: files.current })} />}
    {view && <DealerDocumentUpload caseId={caseId} token={token}
      heading={{ id: "desk-paperwork-heading", step: "Desk Check", title: "Photograph the desk paperwork" }}
      onChosen={chosen => { files.current = chosen; }} onExtracted={() => void refresh()} />}
  </section>;
}

/** Shown on a fresh desk case so it is never mistaken for the agreed car. */
export function DeskFreshCase({ value, onBack }: { value: FreshDeskCase; onBack: () => void }) {
  return <section className="panel desk-fresh-case" aria-labelledby="desk-fresh-heading">
    <h2 id="desk-fresh-heading">Separate case for VIN {value.vin}</h2>
    <p>This case is for the car on the desk paperwork. It is separate from your Agreed Price
      {value.agreedVin ? ` on VIN ${value.agreedVin}` : ""}, which is unchanged. The paperwork is analyzed here as a new deal.</p>
    {value.files.length > 0 && <p className="muted">The paperwork you photographed is added to this case below.</p>}
    <button type="button" className="btn-secondary" onClick={onBack}>Back to the agreed car</button>
  </section>;
}
