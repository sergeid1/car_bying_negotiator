import { ReactNode, useEffect, useRef, useState } from "react";
import "./CaseFlow.css";
import { SaveCaseLink } from "./CasePortability";
import { ReferralDisclosure } from "./AcquisitionChannel";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import { OfferVerification } from "./OfferVerification";
import { DeskCheck, DeskFreshCase, totalEffectSentence, type DeskCheckView, type FreshDeskCase } from "./DeskCheck";
import { TradeVehicle } from "./TradeVehicle";
import { PricePlan } from "./PricePlan";
import { NegotiationMandate } from "./NegotiationMandate";
import { QuoteRequest } from "./QuoteRequest";
import { VerifiedOfferAnalysis } from "./VerifiedOfferAnalysis";
import { openSidePanel } from "./SidePanel";

type Credentials = { caseId: string; token: string };
export type CaseStepId = "quotes" | "compare" | "dealer" | "final";

/** The four steps of a case, in order. The names are placeholders until the operator confirms them. */
export const CASE_STEPS: readonly { id: CaseStepId; name: string; unlock: string; done: string }[] = [
  { id: "quotes", name: "Get quotes", unlock: "",
    done: "A dealer quote is in." },
  { id: "compare", name: "Compare & negotiate", unlock: "Unlocks when you have a dealer quote.",
    done: "You have an agreed price." },
  { id: "dealer", name: "At the dealer", unlock: "Unlocks when you agree a price with the dealer.",
    done: "The dealer's paperwork is in." },
  { id: "final", name: "Final check", unlock: "Unlocks when the dealer's paperwork is read.",
    done: "" },
];

/** What the existing case endpoints say about how far this case has come. */
export type CaseProgress = {
  quote: boolean; offer: boolean; agreedPrice: boolean; deskPaperwork: boolean; deskCompared: boolean;
};

/** The step a Buyer is on: the furthest one the case's own records have reached. */
export function currentStepOf(progress: CaseProgress): CaseStepId {
  if (progress.deskCompared) return "final";
  if (progress.agreedPrice || progress.deskPaperwork) return "dealer";
  if (progress.quote || progress.offer) return "compare";
  return "quotes";
}

type CaseSummary = { car?: string; dealer?: string; location?: string; paying?: string };

const PAYING: Record<string, string> = { CASH: "Paying cash", FINANCE: "Dealer financing" };

/** Reads one case resource; anything but a JSON body (not found, not yet purchased, no content) counts as absent. */
async function readJson(path: string, token: string): Promise<Record<string, unknown> | undefined> {
  try {
    const response = await fetch(path, { headers: { "Case-Access-Token": token } });
    if (!response.ok || response.status === 204) return undefined;
    const body = await response.json();
    return body && typeof body === "object" ? body as Record<string, unknown> : undefined;
  } catch { return undefined; }
}

async function loadProgress({ caseId, token }: Credentials) {
  const base = `/api/v1/cases/${encodeURIComponent(caseId)}`;
  const [quotes, offer, desk] = await Promise.all([
    readJson(`${base}/quote-requests`, token), readJson(`${base}/dealer-offers/latest`, token), readJson(`${base}/desk-check`, token)]);
  const documents = Array.isArray(quotes?.documents) ? quotes.documents : [];
  const status = typeof desk?.status === "string" ? desk.status : undefined;
  return {
    progress: {
      quote: documents.length > 0 || quotes?.negotiationState === "AWAITING_BUYER" || quotes?.negotiationState === "AGREED",
      offer: typeof offer?.version === "number",
      agreedPrice: Boolean(status && status !== "NO_AGREED_PRICE"),
      deskPaperwork: Boolean(desk?.paperwork) || status === "READING" || status === "DIFFERENT_VIN",
      deskCompared: status === "DIFFERENCES" || status === "NO_DIFFERENCES",
    } satisfies CaseProgress,
    desk: status === "DIFFERENCES" || status === "NO_DIFFERENCES" ? desk as unknown as DeskCheckView : undefined,
  };
}

async function loadSummary({ caseId, token }: Credentials): Promise<CaseSummary> {
  const base = `/api/v1/cases/${encodeURIComponent(caseId)}`;
  const [buyingCase, evidence] = await Promise.all([readJson(base, token), readJson(`${base}/vehicle-evidence`, token)]);
  type Car = { year?: number; make?: string; model?: string; trim?: string; dealer?: { name?: string } };
  const listing = evidence?.listingSnapshot as Car | undefined;
  const vehicle = (evidence?.vehicle as Car | undefined) ?? listing;
  const car = vehicle ? [vehicle.year, vehicle.make, vehicle.model, vehicle.trim].filter(Boolean).join(" ") : "";
  const location = typeof buyingCase?.dealerLocation === "string" ? buyingCase.dealerLocation : undefined;
  return {
    car: car || undefined,
    dealer: listing?.dealer?.name,
    // Shown as stored: "City, ST" for newer cases, a five-digit ZIP for older ones.
    location,
    paying: PAYING[String(buyingCase?.purchaseMethod)],
  };
}

export function CaseFlow({ caseId, token, openedFromAccount, referral, deskFresh, atDealer, dealerRequest = 0, pollDelayMs,
  onFreshCase, onBackToAgreed }: Credentials & {
  openedFromAccount: boolean; referral?: string; deskFresh?: FreshDeskCase;
  /** While the "I'm at the dealer" flow leads the page, the Desk Check waits for it. */
  atDealer: boolean;
  /** Changes when the "I'm at the dealer" flow hands over, which opens the At the dealer step. */
  dealerRequest?: number;
  pollDelayMs?: number;
  onFreshCase: (value: FreshDeskCase) => void; onBackToAgreed: () => void;
}) {
  const credentials = { caseId, token };
  // Tied to this case (the flow is keyed by it) so a version saved in one case never reaches another.
  const [frozenOffer, setFrozenOffer] = useState<number>();
  const [deskRefresh, setDeskRefresh] = useState(0);
  const [summary, setSummary] = useState<CaseSummary>({});
  const [progress, setProgress] = useState<CaseProgress>();
  const [desk, setDesk] = useState<DeskCheckView>();
  const [open, setOpen] = useState<CaseStepId>();
  const current = progress ? currentStepOf(progress) : undefined;
  const shownCurrent = useRef<CaseStepId | undefined>(undefined);
  const scrollTo = useRef<CaseStepId | undefined>(undefined);

  useEffect(() => {
    let live = true;
    void loadSummary(credentials).then(value => { if (live) setSummary(value); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId, token]);
  useEffect(() => {
    let live = true;
    void loadProgress(credentials).then(value => { if (live) { setProgress(value.progress); setDesk(value.desk); } });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [caseId, token, deskRefresh]);
  // The current step opens by itself whenever the case moves on; a step the Buyer opened stays open until then.
  useEffect(() => {
    if (current && current !== shownCurrent.current) {
      const first = !shownCurrent.current;
      shownCurrent.current = current;
      // A step the Buyer chose before the first check finished is kept.
      if (first) {
        setOpen(chosen => chosen ?? current);
        // The start screen this replaced may have left the page scrolled past the case; bring its top back into view.
        const top = document.getElementById("case-flow-heading")?.getBoundingClientRect().top;
        if (top !== undefined && (top < 0 || top > window.innerHeight)) document.querySelector(".case-flow-header")?.scrollIntoView?.({ block: "start" });
      }
      else { scrollTo.current = current; setOpen(current); }
    }
  }, [current]);
  useEffect(() => {
    if (dealerRequest > 0) { scrollTo.current = "dealer"; setOpen("dealer"); }
  }, [dealerRequest]);
  useEffect(() => {
    if (open && scrollTo.current === open) {
      scrollTo.current = undefined;
      document.getElementById(`case-step-${open}`)?.scrollIntoView?.({ block: "start" });
    }
  }, [open]);

  function choose(step: CaseStepId) {
    scrollTo.current = step; setOpen(step);
    if (open === step) document.getElementById(`case-step-${step}`)?.scrollIntoView?.({ block: "start" });
  }
  const currentIndex = CASE_STEPS.findIndex(step => step.id === current);
  const stateOf = (index: number) => current === undefined ? "later"
    : index < currentIndex ? "done" : index === currentIndex ? "current" : "later";
  const details = [summary.dealer, summary.location, summary.paying].filter(Boolean);
  const folded = CASE_STEPS.filter((step, index) => stateOf(index) === "done" && open !== step.id);

  const content: Record<CaseStepId, ReactNode> = {
    quotes: <QuoteRequest key={`quote-${caseId}-${token}`} {...credentials} />,
    compare: <>
      <OfferVerification key={`verify-${caseId}-${token}`} {...credentials}
        onOfferFrozen={offer => { setFrozenOffer(offer.version); setDeskRefresh(value => value + 1); }} />
      <VerifiedOfferAnalysis key={`analysis-${caseId}-${token}`} {...credentials} offerVersion={frozenOffer} pollDelayMs={pollDelayMs} />
      <PricePlan key={caseId + token} {...credentials} />
      <NegotiationMandate key={`mandate-${caseId}-${token}`} {...credentials} />
    </>,
    dealer: <>
      {/* The Buyer confirms which car the paperwork is for in the flow before the Desk Check compares it. */}
      {!atDealer && <DeskCheck key={`desk-${caseId}-${token}`} {...credentials} refreshKey={deskRefresh} onFreshCase={onFreshCase} />}
      <DealerDocumentUpload key={`documents-${caseId}-${token}`} {...credentials}
        initialFiles={deskFresh?.caseId === caseId ? deskFresh.files : undefined} />
      <TradeVehicle key={`trade-${caseId}-${token}`} {...credentials}
        carryFrom={deskFresh?.caseId === caseId ? deskFresh.agreed : undefined} />
    </>,
    final: <FinalCheck desk={desk} onShowDifferences={() => choose("dealer")} />,
  };

  return <div className="case-flow stack">
    <section className="panel case-flow-header" aria-labelledby="case-flow-heading">
      <div className="case-flow-title">
        <h1 id="case-flow-heading">{summary.car ?? "Your car"}</h1>
        {details.length > 0 && <p className="case-flow-details">{details.join(" · ")}</p>}
      </div>
      <button type="button" className="btn-secondary" aria-haspopup="dialog" onClick={() => openSidePanel("case")}>This case</button>
    </section>

    {deskFresh?.caseId === caseId && <DeskFreshCase value={deskFresh} onBack={onBackToAgreed} />}
    {/* A referral is disclosed before any step asks for the Buyer's paperwork. */}
    {referral && <ReferralDisclosure text={referral} />}

    <nav className="case-progress" aria-label="Case progress">
      <ol>
        {CASE_STEPS.map((step, index) => <li key={step.id} data-state={stateOf(index)}>
          <button type="button" aria-current={step.id === current ? "step" : undefined}
            aria-expanded={open === step.id} aria-controls={`case-step-${step.id}-body`} onClick={() => choose(step.id)}>
            <span className="case-progress-number" aria-hidden="true">{stateOf(index) === "done" ? "✓" : index + 1}</span>
            <span className="case-progress-name">{step.name}</span>
            <span className="visually-hidden">{stateOf(index) === "done" ? ", done" : stateOf(index) === "later" ? ", not reached yet" : ""}</span>
          </button>
        </li>)}
      </ol>
      {!progress && <p className="muted" role="status">Checking where your case is…</p>}
    </nav>

    {folded.length > 0 && <p className="case-done-summary">
      <span className="case-done-mark" aria-hidden="true">✓</span>
      <span>Done: {folded.map((step, index) => <span key={step.id}>{index > 0 && (index === folded.length - 1 ? " and " : ", ")}
        <button type="button" className="case-done-step" aria-expanded="false" aria-controls={`case-step-${step.id}-body`}
          title={step.done} onClick={() => choose(step.id)}>{step.name}<span className="visually-hidden">. {step.done} Reopen this step</span></button>
      </span>)}.</span>
    </p>}

    {CASE_STEPS.map((step, index) => {
      const state = stateOf(index);
      const isOpen = open === step.id;
      // A finished step that is not open is listed in the summary line above; it stays mounted so its forms keep their place.
      return <section key={step.id} id={`case-step-${step.id}`} className="case-step" data-state={state} data-open={isOpen}
        aria-labelledby={`case-step-${step.id}-heading`} tabIndex={-1} hidden={state === "done" && !isOpen}>
        <div className="case-step-head">
          <div className="case-step-title">
            <h2 id={`case-step-${step.id}-heading`}><span className="case-step-count">Step {index + 1} of {CASE_STEPS.length}</span> {step.name}</h2>
            {state === "done" && <p className="case-step-note">Done. {step.done}</p>}
            {state === "later" && step.unlock && progress && <p className="case-step-note">{step.unlock}</p>}
          </div>
          {!isOpen && <button type="button" className="btn-ghost" aria-expanded="false" aria-controls={`case-step-${step.id}-body`}
            onClick={() => choose(step.id)}>{state === "done" ? "Reopen" : "Open"}{" "}<span className="visually-hidden">this step: {step.name}</span></button>}
        </div>
        {/* Closed steps stay mounted so their forms and checks keep their place. */}
        <div id={`case-step-${step.id}-body`} className="case-step-body stack" hidden={!isOpen}>{content[step.id]}</div>
      </section>;
    })}

    {!openedFromAccount && <SaveCaseLink {...credentials} />}
    {openedFromAccount && <section className="panel" aria-label="Opened from your Buyer Account">
      <h2>Opened from your Buyer Account</h2>
      <p>This case stays open while you are signed in. Signing out closes it here; the private case link you saved
        still opens it.</p>
    </section>}
  </div>;
}

/** The comparison of the dealer's paperwork with the agreed price, read before signing. */
function FinalCheck({ desk, onShowDifferences }: { desk?: DeskCheckView; onShowDifferences: () => void }) {
  if (!desk) return <section className="panel" aria-label="Final check">
    <p>When the dealer's paperwork is read, this step compares it with your agreed price, line by line.</p>
  </section>;
  const count = desk.findings.length;
  return <section className="panel final-check" aria-label="Final check">
    <p className="desk-check-lead">{desk.status === "DIFFERENCES"
      ? `${count} ${count === 1 ? "difference" : "differences"} from your agreed price. ${totalEffectSentence(desk)}`
      : `No differences from your agreed price. ${totalEffectSentence(desk)}`}</p>
    <button type="button" className="btn-secondary" onClick={onShowDifferences}>
      {desk.status === "DIFFERENCES" ? "See each difference" : "See the Desk Check"}</button>
    <p className="muted">This checks the numbers on the paperwork. It is not a legal review. Read every page before you sign.</p>
  </section>;
}
