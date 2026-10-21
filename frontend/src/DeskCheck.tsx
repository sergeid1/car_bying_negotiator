import { useEffect, useRef, useState } from "react";
import { BuyingCaseIntake } from "./BuyingCaseIntake";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { TradeCheckPanel, type TradeCheckResult } from "./TradeCheck";
import { DeskTactics, type DeskTacticFinding } from "./DeskTactics";
import { ListingPriceNote, type ListingPriceCheck } from "./ListingCapture";
import { GovernmentFeeNote, type GovernmentFeeCheck } from "./GovernmentFees";
import { DealerAddOnsNote, type DealerAddOnCheck } from "./DealerAddOns";
import { SigningNote } from "./SigningNote";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

type Credentials = { caseId: string; token: string };
type Term = { value?: string | null; unit?: string; amountMinor?: number; currency?: string; category?: string } | number;
export type DeskFinding = {
  group: string; term: string; label: string; kind: string; agreed: Term | null; desk: Term | null;
  delta: number | null; unit: string | null; severity: string; verified: boolean;
  evidence: { pageNumber?: number } | null; buyerAction: string | null;
  /** The add-on catalogue's name for a charge line (ticket 142). */
  chargeName?: string | null;
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
  freshCase: null | { vin: string; dealerLocation?: string; dealerPostalCode?: string | null; purchaseMethod: string };
  dealReviewVersion: number | null;
  trade?: TradeCheckResult | null;
  tacticFindings?: DeskTacticFinding[];
  /** The price in the ad the Buyer saved, beside this paperwork's price (ticket 115). */
  listingPrice?: ListingPriceCheck | null;
  /** The state fee lines against the state's fee schedule (ticket 139). */
  governmentFees?: GovernmentFeeCheck | null;
  /** Each add-on and extra dealer charge on the paperwork, named from the catalogue (ticket 142). */
  addOns?: DealerAddOnCheck | null;
};
export type FreshDeskCase = Credentials & { agreed: Credentials; agreedVin: string | null; vin: string; files: File[] };

type Copy = Messages["dealer"]["deskCheck"];
type Fill = ReturnType<typeof useMessages>["fill"];

function money(minor: number, signed = false) {
  const text = format.money(Math.abs(minor));
  return signed ? `${minor < 0 ? "−" : "+"}${text}` : minor < 0 ? `−${text}` : text;
}
function labelOf(t: Copy, finding: DeskFinding) {
  return (t.labels as Record<string, string>)[finding.label] ?? (finding.term.startsWith("line:") ? finding.label
    : finding.label.toLowerCase().replaceAll("_", " ").replace(/^./, first => first.toUpperCase()));
}
function show(t: Copy, fill: Fill, term: Term | null) {
  if (term === null || term === undefined) return t.nothing;
  if (typeof term === "number") return money(term);
  if (typeof term.amountMinor === "number") return money(term.amountMinor);
  if (term.value === null || term.value === undefined) return t.unreadable;
  if (term.unit === "USD_MINOR" && /^-?\d+$/.test(term.value)) return money(Number(term.value));
  if (term.unit === "PERCENT") return `${term.value}%`;
  if (term.unit === "MONTHS") return fill(t.months, { value: term.value });
  return term.value;
}
/** One plain sentence per difference, saying what changed relative to the Agreed Price. */
export function describeFinding(t: Copy, fill: Fill, finding: DeskFinding) {
  const label = labelOf(t, finding);
  const term = (value: Term | null) => show(t, fill, value);
  if (finding.kind === "DOC_FEE_CHECK") return fill(t.docFee, { label });
  switch (finding.group) {
    case "VEHICLE": return fill(t.vehicle, { desk: term(finding.desk), agreed: term(finding.agreed) });
    case "ADDED": return finding.chargeName
      ? fill(t.addedNamed, { label, name: finding.chargeName, amount: term(finding.desk) })
      : fill(t.added, { label, amount: term(finding.desk) });
    case "REMOVED": return fill(t.removed, { label, amount: term(finding.agreed) });
    case "LIMIT": return fill(t.limit, { label, limit: term(finding.agreed), amount: money(finding.delta ?? 0) });
    default: {
      if (finding.desk === null) return fill(t.notOnPaperwork, { label, amount: term(finding.agreed) });
      if (finding.agreed === null) return fill(t.newOnPaperwork, { label, amount: term(finding.desk) });
      const values = { label, agreed: term(finding.agreed), desk: term(finding.desk) };
      return finding.delta !== null && (finding.unit ?? "").endsWith("_MINOR") && finding.delta !== 0
        ? fill(t.changedBy, { ...values, delta: money(finding.delta, true) }) : fill(t.changed, values);
    }
  }
}
/** A trade finding's sentence comes from the trade check itself (ticket 103). */
function describe(t: Copy, fill: Fill, view: DeskCheckView, finding: DeskFinding) {
  if (finding.term.startsWith("trade:")) {
    const trade = view.trade?.findings.find(item => `trade:${item.code}` === finding.term);
    if (trade) return trade.summary;
  }
  return describeFinding(t, fill, finding);
}
/** The lead line: how many differences and what they do to the total. Its clauses are joined with semicolons. */
export function totalEffectSentence(t: Copy, fill: Fill, view: DeskCheckView) {
  const effect = view.totalEffect;
  const parts: string[] = [];
  if (effect.outTheDoorDeltaMinor !== null) {
    const amount = money(Math.abs(effect.outTheDoorDeltaMinor));
    const calculated = effect.basis === "CALCULATED";
    parts.push(effect.outTheDoorDeltaMinor === 0 ? t.totalSame
      : fill(effect.outTheDoorDeltaMinor > 0 ? (calculated ? t.totalHigherCalculated : t.totalHigherPrinted)
        : (calculated ? t.totalLowerCalculated : t.totalLowerPrinted), { amount }));
  } else parts.push(t.totalUnavailable);
  if (effect.basis === "CALCULATED" && effect.preTaxDeltaMinor !== null && effect.taxEffectMinor !== null)
    parts.push(fill(t.taxSplit, { preTax: money(effect.preTaxDeltaMinor, true), tax: money(effect.taxEffectMinor, true) }));
  if (effect.addedChargesMinor) parts.push(fill(t.addedCharges, { amount: money(effect.addedChargesMinor) }));
  return parts.join("; ") + ".";
}

function asView(value: unknown, unavailable: string): DeskCheckView {
  const v = value as Partial<DeskCheckView> | null;
  if (!v || typeof v.status !== "string" || !Array.isArray(v.findings) || !Array.isArray(v.otherDifferences) || !v.totalEffect)
    throw new Error(unavailable);
  return v as DeskCheckView;
}

export function DeskCheck({ caseId, token, refreshKey = 0, onFreshCase }: Credentials & {
  refreshKey?: number; onFreshCase?: (value: FreshDeskCase) => void;
}) {
  const { m: { dealer: { deskCheck: t, common } }, fill } = useMessages();
  const [view, setView] = useState<DeskCheckView>();
  const [error, setError] = useState<string>();
  const [starting, setStarting] = useState(false);
  const files = useRef<File[]>([]);
  const heading = useRef<HTMLHeadingElement>(null);
  const shown = useRef(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/desk-check`;
  async function load() {
    const response = await fetch(path, { headers: { "Case-Access-Token": token } });
    if (response.status === 402) throw new Error(common.purchaseRequired);
    if (!response.ok) throw new Error(t.unavailable);
    return asView(await response.json(), t.unavailable);
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
  const total = agreed?.outTheDoorMinor ?? null;
  const compares = agreed?.vin ? (total !== null ? t.comparesTotalVin : t.comparesVin) : total !== null ? t.comparesTotal : t.compares;
  return <section className="stack desk-check" aria-label={t.title}>
    <div className="panel desk-check-result">
      <div className="section-heading"><div>
        <p className="step">{t.step}</p>
        <h2 id="desk-check-heading" ref={heading} tabIndex={-1}>{t.title}</h2>
      </div></div>
      {agreed && <p className="muted">{fill(compares, { total: total !== null ? money(total) : "", vin: agreed.vin ?? "" })}</p>}
      {view?.status === "AWAITING_PAPERWORK" && <p role="status">{t.awaiting}</p>}
      {view?.status === "READING" && <p role="status">{t.reading}</p>}
      {view?.status === "DIFFERENT_VIN" && view.freshCase && <>
        <p role="alert">{agreed?.vin ? fill(t.differentCarVin, { vin: view.freshCase.vin, agreed: agreed.vin })
          : fill(t.differentCar, { vin: view.freshCase.vin })}</p>
        <p>{t.switching}</p>
        {!starting && <button type="button" onClick={() => setStarting(true)}>{t.startFresh}</button>}
      </>}
      {(view?.status === "DIFFERENCES" || view?.status === "NO_DIFFERENCES") && <>
        <p role="status" className="desk-check-lead">{view.status === "DIFFERENCES"
          ? fill(t.differences, { count: view.findings.length, effect: totalEffectSentence(t, fill, view) })
          : fill(read ? t.noDifferencesRead : t.noDifferencesVerified, { effect: totalEffectSentence(t, fill, view) })}</p>
        {shownFindings.length > 0 && <ol className="desk-check-findings" aria-label={t.findings}>
          {shownFindings.map(finding => <li key={`${finding.group}-${finding.term}`} data-severity={finding.severity}>
            <p>{describe(t, fill, view, finding)}</p>
            <p className="desk-check-meta">
              <span className={finding.verified ? "cash-badge" : "badge badge-warn"}>{finding.verified ? t.verifiedBadge : t.asReadBadge}</span>
              {finding.evidence?.pageNumber ? <span> {fill(t.page, { page: finding.evidence.pageNumber })}</span> : null}
              {finding.buyerAction ? <span> {finding.buyerAction}</span> : null}
            </p>
          </li>)}
        </ol>}
        {view.trade && <TradeCheckPanel result={view.trade} tactics={view.tacticFindings} />}
        {view.listingPrice && <ListingPriceNote check={view.listingPrice} title={common.listingTitle} />}
        {view.governmentFees && <GovernmentFeeNote check={view.governmentFees} />}
        {view.addOns && <DealerAddOnsNote check={view.addOns} />}
        {view.otherDifferences.length > 0 && <details>
          <summary>{view.otherDifferences.some(finding => finding.kind === "DOC_FEE_CHECK")
            ? t.otherNotes
            : fill(t.otherChanges, { count: view.otherDifferences.length })}</summary>
          <ul>{view.otherDifferences.map(finding => <li key={`${finding.group}-${finding.term}`}>
            {describeFinding(t, fill, finding)} {finding.verified ? t.verifiedSentence : common.asReadSentence}
            {finding.kind === "DOC_FEE_CHECK" && <p>{finding.buyerAction}</p>}
          </li>)}</ul>
        </details>}
        <p className="muted">{read
          ? t.readNote
          : view.source === "DEAL_REVIEW"
            ? fill(t.fromDealReview, { version: String(view.dealReviewVersion) })
            : t.fromVerified}</p>
        {read && <button type="button" className="btn-secondary" onClick={() => {
          const target = document.getElementById("verify-heading");
          target?.scrollIntoView?.({ block: "start" }); target?.focus();
        }}>{t.verifyTerms}</button>}
        <SigningNote />
      </>}
      <button type="button" className="btn-secondary" onClick={() => void refresh()}>{t.refresh}</button>
      <DeskTactics findings={view?.tacticFindings} />
      {error && <p role="alert">{error}</p>}
    </div>
    {starting && view?.freshCase && <BuyingCaseIntake title={fill(t.freshCaseTitle, { vin: view.freshCase.vin })}
      initial={{ vin: view.freshCase.vin, dealerLocation: view.freshCase.dealerLocation ?? view.freshCase.dealerPostalCode ?? undefined, purchaseMethod: view.freshCase.purchaseMethod }}
      onCaseOpened={value => onFreshCase?.({ ...value, agreed: { caseId, token }, agreedVin: agreed?.vin ?? null,
        vin: view.freshCase!.vin, files: files.current })} />}
    {view && <DealerDocumentUpload caseId={caseId} token={token}
      heading={{ id: "desk-paperwork-heading", step: t.title, title: t.paperworkTitle }}
      onChosen={chosen => { files.current = chosen; }} onExtracted={() => void refresh()} />}
  </section>;
}

/** Shown on a fresh desk case so it is never mistaken for the agreed car. */
export function DeskFreshCase({ value, onBack }: { value: FreshDeskCase; onBack: () => void }) {
  const { m: { dealer: { deskFreshCase: t } }, fill } = useMessages();
  return <section className="panel desk-fresh-case" aria-labelledby="desk-fresh-heading">
    <h2 id="desk-fresh-heading">{fill(t.heading, { vin: value.vin })}</h2>
    <p>{value.agreedVin ? fill(t.bodyVin, { vin: value.agreedVin }) : t.body}</p>
    {value.files.length > 0 && <p className="muted">{t.photographed}</p>}
    <button type="button" className="btn-secondary" onClick={onBack}>{t.back}</button>
  </section>;
}
