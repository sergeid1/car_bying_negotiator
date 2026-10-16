import { FormEvent, useEffect, useId, useRef, useState } from "react";
import "./LoanPreApprovals.css";
import { howOtdogMakesMoneyLink } from "./HowOtdogMakesMoney";

type Housing = "OWN" | "RENT" | "OTHER";
export type LoanApplication = {
  amountMinor: number; termMonths: number;
  vehicle: { vin: string | null; modelYear: number; make: string; model: string; mileage: number };
  annualIncomeMinor: number; housing: Housing; monthlyHousingCostMinor: number;
};
export type LoanOffer = {
  id: string; lender: string; source: "PARTNER" | "BUYER_ENTERED"; amountMinor: number; aprPercent: number; termMonths: number;
  upfrontFeesMinor: number; paymentMinor: number | null; totalInterestMinor: number | null; totalCostMinor: number | null;
  lenderPaymentMinor: number | null; lenderMinusCalculatedMinor: number | null; lenderPaymentWithinTolerance: boolean | null;
  conditions: { maximumVehicleAgeYears: number | null; maximumMileage: number | null; minimumDownPaymentMinor: number | null } | null;
  offeredAt: string; expiresAt: string | null; continueUrl: string | null; differences: ("AMOUNT" | "TERM")[]; chosen: boolean;
};
type Outcome = "OFFERED" | "DECLINED" | "NEEDS_MORE_INFORMATION" | "UNAVAILABLE" | "TIMED_OUT";
export type ChosenOffer = { offerId: string; lender: string; source: "PARTNER" | "BUYER_ENTERED"; amountMinor: number; aprPercent: number;
  termMonths: number; upfrontFeesMinor: number; expiresAt: string | null; expired: boolean; chosenAt: string };
export type LoanView = {
  holder: "JOURNEY" | "CASE";
  caseVehicle: { vin: string | null; modelYear: number | null; make: string | null; model: string | null; mileage: number | null } | null;
  revision: number | null; changedAt: string | null; application: LoanApplication | null;
  authorization: { version: string; partner: string; synthetic: boolean; text: string[] };
  results: null | {
    request: null | { id: string; requestedAt: string; retrievedAt: string; partner: string; synthetic: boolean;
      availability: string; notice: string | null; outdated: boolean };
    sameTerms: LoanOffer[]; differentTerms: LoanOffer[]; expired: LoanOffer[]; withoutOffer: { lender: string; outcome: Outcome }[];
  };
  chosen: ChosenOffer | null;
};
type FieldError = { field: string; message: string };
class Problem extends Error {
  constructor(message: string, readonly errors: FieldError[] = [], readonly status = 0) { super(message); }
}
type Access = { caseId: string; token: string } | { journeyId: string; onSessionEnded: () => void };

const HOUSING: { value: Housing; label: string }[] = [
  { value: "RENT", label: "I rent" }, { value: "OWN", label: "I own my home" }, { value: "OTHER", label: "Something else" }];
const TERMS = [24, 36, 48, 60, 72, 84];
const OUTCOMES: Record<Outcome, string> = {
  OFFERED: "Offered", DECLINED: "Did not pre-approve", NEEDS_MORE_INFORMATION: "Needs more information before an offer",
  UNAVAILABLE: "Unavailable", TIMED_OUT: "Did not answer in time" };

export function money(minor: number) {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: minor % 100 ? 2 : 0 })
    .format(minor / 100);
}
function day(iso: string) {
  return new Date(iso).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "America/New_York" });
}
/** Dollars as typed, to whole cents; null when it is not an amount. */
function toMinor(text: string) {
  const cleaned = text.replace(/[$,\s]/g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(Number(cleaned) * 100);
}
function whole(text: string) {
  const cleaned = text.replace(/[,\s]/g, "");
  return /^\d{1,7}$/.test(cleaned) ? Number(cleaned) : null;
}
function percent(text: string) {
  const cleaned = text.replace(/[%\s]/g, "");
  return /^\d{1,3}(\.\d{1,8})?$/.test(cleaned) ? Number(cleaned) : null;
}

function asView(value: unknown): LoanView {
  const v = value as Partial<LoanView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !v.authorization)
    throw new Problem("Your loan application is unavailable. Try refreshing.");
  return v as LoanView;
}

/** The lender's own payment figure beside FIN-01's, as a reconciliation (FIN-02). */
export function reconciliationText(offer: LoanOffer) {
  if (offer.lenderPaymentMinor == null || offer.lenderMinusCalculatedMinor == null) return null;
  if (offer.lenderPaymentWithinTolerance) return `The lender's stated payment of ${money(offer.lenderPaymentMinor)} matches this calculation.`;
  const gap = offer.lenderMinusCalculatedMinor;
  return `The lender states ${money(offer.lenderPaymentMinor)} a month, ${money(Math.abs(gap))} ${gap > 0 ? "more" : "less"} than this `
    + "calculation. Ask the lender what makes up the difference.";
}

function conditionsText(conditions: LoanOffer["conditions"]) {
  if (!conditions) return null;
  const parts = [];
  if (conditions.maximumVehicleAgeYears != null) parts.push(`a car up to ${conditions.maximumVehicleAgeYears} years old`);
  if (conditions.maximumMileage != null) parts.push(`up to ${conditions.maximumMileage.toLocaleString("en-US")} miles`);
  if (conditions.minimumDownPaymentMinor) parts.push(`at least ${money(conditions.minimumDownPaymentMinor)} down`);
  return parts.length ? `Conditions: ${parts.join(", ")}.` : null;
}

/**
 * Loan pre-approvals from many lenders with one application and soft inquiries only, through a licensed lending
 * partner (spec 5.10, LOAN-01 to LOAN-03). The offer the Buyer chooses becomes their own financing: the outside
 * financing comparison and the Desk Check compare the dealer's financing with it. Opened from a case it uses the
 * case's credential, and a case in a Buying Journey shares the journey's one application; opened from a journey on
 * its own it uses the account session.
 */
export function LoanPreApprovals(props: Access & { level?: 2 | 3 }) {
  const level = props.level ?? 2;
  const id = useId();
  const [view, setView] = useState<LoanView>();
  const [error, setError] = useState<string>();
  const [mode, setMode] = useState<"summary" | "editing" | "authorization">("summary");
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);
  const isCase = "caseId" in props;
  const path = isCase ? `/api/v1/cases/${encodeURIComponent(props.caseId)}/loan`
    : `/api/v1/journeys/${encodeURIComponent(props.journeyId)}/loan`;
  const credential = isCase ? props.token : "";

  async function request(url: string, init: RequestInit & { key?: string } = {}) {
    const { key, ...rest } = init;
    const write = ["POST", "PUT", "DELETE"].includes(rest.method ?? "GET");
    const response = await fetch(url, { ...rest, headers: {
      ...(isCase ? { "Case-Access-Token": credential } : write ? { "Account-Request": "1" } : {}),
      ...(rest.body ? { "Content-Type": "application/json" } : {}),
      ...(key ? { "Idempotency-Key": key } : {}),
    } });
    if (!isCase && response.status === 401) { props.onSessionEnded(); throw new Problem("Your sign-in ended.", [], 401); }
    if (response.status === 204) throw new Problem("Your loan application is unavailable. Try refreshing.", [], 204);
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Problem(body?.detail ?? "That did not work. Nothing changed; try again.",
      Array.isArray(body?.errors) ? body.errors : [], response.status);
    return asView(body);
  }

  useEffect(() => {
    let current = true;
    request(path).then(value => { if (current) setView(value); }).catch(failure => {
      // A case or journey this panel cannot reach shows nothing rather than a second error beside its own.
      if (current && !(failure instanceof Problem && [204, 401, 404].includes(failure.status))) setError((failure as Error).message);
    });
    return () => { current = false; };
    // The path and its credential define this application.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, credential]);

  const Heading = `h${level}` as "h2" | "h3";
  const Sub = `h${level + 1}` as "h3" | "h4";
  const headingId = `${id}-loan-heading`;
  if (!view) return error ? <section className="panel loan-pre-approvals" aria-labelledby={headingId}>
    <Heading id={headingId}>Loan pre-approvals</Heading><p className="notice">{error}</p></section> : null;

  const application = view.application;
  async function act(action: () => Promise<LoanView>) {
    setBusy(true); setError(undefined);
    try { setView(await action()); return true; }
    catch (failure) { if ((failure as Problem).status !== 401) setError((failure as Error).message); return false; }
    finally { setBusy(false); }
  }

  return <section className="panel loan-pre-approvals" aria-labelledby={headingId}>
    <div className="section-heading"><div>
      <p className="step">Financing</p>
      <Heading id={headingId}>Loan pre-approvals</Heading>
    </div></div>
    <p>Compare loan pre-approvals from many lenders with one application, through a licensed lending partner. Lenders check
      your credit with a soft inquiry only, which does not affect your credit score. It's optional, and a pre-approval is not a
      loan. The one you choose becomes your own financing offer, which the Desk Check compares with the dealer's.</p>
    <p className="muted">{view.holder === "JOURNEY"
      ? "This application belongs to your Buying Journey, so every case in the journey uses the same one."
      : "This case holds its own application."}</p>

    {view.chosen && <ChosenSummary chosen={view.chosen} Sub={Sub} busy={busy}
      onClear={() => void act(() => request(`${path}/chosen-offer`, { method: "DELETE" }))} />}

    {!application && mode !== "editing" && <button type="button" onClick={() => setMode("editing")}>Start a loan application</button>}
    {application && mode === "summary" && <>
      <ApplicationSummary application={application} Sub={Sub} />
      <div className="loan-actions">
        <button type="button" disabled={busy} onClick={() => setMode("authorization")}>
          {view.results?.request ? "Get new pre-approvals" : "Get pre-approvals"}</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => setMode("editing")}>Edit application</button>
        {!removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => setRemoving(true)}>Remove application</button>}
        {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void act(() => request(path, { method: "DELETE" }))
          .then(done => { if (done) setRemoving(false); })}>Remove the application and its offers</button>}
      </div>
    </>}
    {mode === "editing" && <ApplicationForm view={view} busy={busy} onCancel={() => setMode("summary")} onSave={async input => {
      setBusy(true); setError(undefined);
      try {
        setView(await request(path, { method: "PUT", body: JSON.stringify({ basedOnRevision: view.revision, ...input }) }));
        setMode("summary");
      } finally { setBusy(false); }
    }} />}
    {mode === "authorization" && application && <AuthorizationStep view={view} busy={busy} Sub={Sub} onCancel={() => setMode("summary")}
      onAuthorize={() => void act(() => request(`${path}/pre-approval-requests`, { method: "POST", key: crypto.randomUUID(),
        body: JSON.stringify({ authorizationTextVersion: view.authorization.version, authorized: true }) }))
        .then(done => { if (done) setMode("summary"); })} />}
    {error && <p role="alert">{error}</p>}
    {application && mode === "summary" && <Results view={view} Sub={Sub} busy={busy}
      onChoose={offerId => void act(() => request(`${path}/chosen-offer`, { method: "PUT", body: JSON.stringify({ offerId }) }))}
      onRemove={offerId => void act(() => request(`${path}/own-offers/${encodeURIComponent(offerId)}`, { method: "DELETE" }))}
      onAdd={async input => {
        setBusy(true); setError(undefined);
        try { setView(await request(`${path}/own-offers`, { method: "POST", key: crypto.randomUUID(), body: JSON.stringify(input) })); }
        finally { setBusy(false); }
      }} />}
  </section>;
}

function ChosenSummary({ chosen, Sub, busy, onClear }: { chosen: ChosenOffer; Sub: "h3" | "h4"; busy: boolean; onClear: () => void }) {
  const heading = useId();
  return <section className="loan-chosen" aria-labelledby={heading}>
    <Sub id={heading}>Your own financing</Sub>
    <p><strong>{chosen.lender}</strong>: {chosen.aprPercent}% APR for {chosen.termMonths} months on {money(chosen.amountMinor)}
      {chosen.upfrontFeesMinor > 0 && `, with ${money(chosen.upfrontFeesMinor)} in upfront fees`}.</p>
    {chosen.expired
      ? <p role="alert">This offer expired{chosen.expiresAt ? ` on ${day(chosen.expiresAt)}` : ""}, so the Desk Check no longer uses it.
        Choose another offer or get new pre-approvals.</p>
      : <p className="muted">The outside financing comparison and the Desk Check compare the dealer's financing with it, and say so
        plainly when the dealer's financing costs less.{chosen.expiresAt && ` Good until ${day(chosen.expiresAt)}.`}</p>}
    <div className="loan-actions">
      <button type="button" className="btn-secondary" disabled={busy} onClick={onClear}>Stop using it as my own financing</button>
    </div>
  </section>;
}

function ApplicationSummary({ application, Sub }: { application: LoanApplication; Sub: "h3" | "h4" }) {
  const v = application.vehicle;
  return <div className="loan-summary">
    <Sub>Your application</Sub>
    <dl className="facts">
      <div><dt>Amount</dt><dd>{money(application.amountMinor)} over {application.termMonths} months</dd></div>
      <div><dt>Car</dt><dd>{[v.modelYear, v.make, v.model].join(" ")}, {v.mileage.toLocaleString("en-US")} miles</dd></div>
      <div><dt>Yearly income</dt><dd>{money(application.annualIncomeMinor)}</dd></div>
      <div><dt>Housing</dt><dd>{HOUSING.find(option => option.value === application.housing)?.label},
        {" "}{money(application.monthlyHousingCostMinor)} a month</dd></div>
    </dl>
  </div>;
}

function useFieldErrors(prefix: string, problem?: Problem) {
  const message = (field: string) => problem?.errors.find(error => error.field === field)?.message;
  const domId = (field: string) => `${prefix}-${field.replace(/[^A-Za-z0-9]/g, "-")}-error`;
  return {
    described: (field: string) => message(field) ? { "aria-invalid": true as const, "aria-describedby": domId(field) } : {},
    errorFor: (field: string) => message(field) && <p className="error field-error" id={domId(field)}>{message(field)}</p>,
    describedBy: (field: string) => message(field) ? domId(field) : undefined,
  };
}

function ApplicationForm({ view, busy, onCancel, onSave }: { view: LoanView; busy: boolean; onCancel: () => void;
  onSave: (input: object) => Promise<void> }) {
  const prefix = useId();
  const application = view.application;
  const fromCase = view.caseVehicle;
  const start = application?.vehicle ?? null;
  const [vehicle, setVehicle] = useState({
    vin: start?.vin ?? fromCase?.vin ?? "", modelYear: String(start?.modelYear ?? fromCase?.modelYear ?? ""),
    make: start?.make ?? fromCase?.make ?? "", model: start?.model ?? fromCase?.model ?? "",
    mileage: String(start?.mileage ?? fromCase?.mileage ?? "") });
  const [housing, setHousing] = useState<Housing | "">(application?.housing ?? "");
  const [problem, setProblem] = useState<Problem>();
  const summary = useRef<HTMLParagraphElement>(null);
  const { described, errorFor, describedBy } = useFieldErrors(prefix, problem);
  const caseCarDiffers = Boolean(fromCase && (fromCase.make || fromCase.vin) && application && (fromCase.vin
    ? fromCase.vin !== application.vehicle.vin : fromCase.model !== application.vehicle.model || fromCase.modelYear !== application.vehicle.modelYear));

  const number = (text: string) => text.trim() === "" ? null : whole(text) ?? -1;
  const amount = (text: string) => text.trim() === "" ? null : toMinor(text) ?? -1;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    try {
      await onSave({ amountMinor: amount(text("amount")), termMonths: number(text("term")),
        vehicle: { vin: vehicle.vin.trim() || null, modelYear: number(vehicle.modelYear), make: vehicle.make, model: vehicle.model,
          mileage: number(vehicle.mileage) },
        annualIncomeMinor: amount(text("income")), housing: housing || null, monthlyHousingCostMinor: amount(text("housingCost")) });
    } catch (failure) {
      setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message));
      requestAnimationFrame(() => summary.current?.focus());
    }
  }

  return <form className="loan-form" onSubmit={event => void submit(event)} noValidate aria-label="Loan application">
    <p className="muted">Only what lenders use for a pre-approval. otdog does not ask for your name, address, date of birth or
      Social Security number; if the lending partner needs them, you enter them in the partner's own form.</p>

    <fieldset className="loan-group">
      <legend>The loan</legend>
      <label>Amount to borrow ($)<input name="amount" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.amountMinor / 100).toFixed(2) : ""} {...described("amountMinor")} /></label>
      {errorFor("amountMinor")}
      <label>Term<select name="term" defaultValue={String(application?.termMonths ?? 60)} {...described("termMonths")}>
        {[...new Set([...TERMS, application?.termMonths ?? 60])].sort((a, b) => a - b)
          .map(term => <option key={term} value={term}>{term} months</option>)}
      </select></label>
      {errorFor("termMonths")}
    </fieldset>

    <fieldset className="loan-group">
      <legend>The car</legend>
      {fromCase && !application && (fromCase.make || fromCase.vin) && <p className="muted">Filled in from this case's car. Change anything that is not right.</p>}
      {caseCarDiffers && <button type="button" className="btn-secondary" onClick={() => setVehicle({
        vin: fromCase?.vin ?? "", modelYear: String(fromCase?.modelYear ?? ""), make: fromCase?.make ?? "", model: fromCase?.model ?? "",
        mileage: String(fromCase?.mileage ?? "") })}>Use this case's car</button>}
      <label>VIN (optional)<input className="loan-vin" autoCapitalize="characters" autoComplete="off" spellCheck={false} maxLength={17}
        value={vehicle.vin} onChange={event => setVehicle(value => ({ ...value, vin: event.target.value }))} {...described("vehicle.vin")} /></label>
      {errorFor("vehicle.vin")}
      <label>Model year<input inputMode="numeric" autoComplete="off" maxLength={4} value={vehicle.modelYear}
        onChange={event => setVehicle(value => ({ ...value, modelYear: event.target.value }))} {...described("vehicle.modelYear")} /></label>
      {errorFor("vehicle.modelYear")}
      <label>Make<input autoComplete="off" maxLength={64} value={vehicle.make}
        onChange={event => setVehicle(value => ({ ...value, make: event.target.value }))} {...described("vehicle.make")} /></label>
      {errorFor("vehicle.make")}
      <label>Model<input autoComplete="off" maxLength={64} value={vehicle.model}
        onChange={event => setVehicle(value => ({ ...value, model: event.target.value }))} {...described("vehicle.model")} /></label>
      {errorFor("vehicle.model")}
      <label>Mileage<input inputMode="numeric" autoComplete="off" value={vehicle.mileage}
        onChange={event => setVehicle(value => ({ ...value, mileage: event.target.value }))} {...described("vehicle.mileage")} /></label>
      {errorFor("vehicle.mileage")}
    </fieldset>

    <fieldset className="loan-group">
      <legend>Income and housing</legend>
      <label>Yearly income before tax ($)<input name="income" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.annualIncomeMinor / 100).toFixed(2) : ""} {...described("annualIncomeMinor")} /></label>
      {errorFor("annualIncomeMinor")}
      <fieldset className="loan-choices" aria-describedby={describedBy("housing")}>
        <legend>Housing</legend>
        {HOUSING.map(option => <label key={option.value}>
          <input type="radio" name="housing" value={option.value} checked={housing === option.value} onChange={() => setHousing(option.value)} />
          <span>{option.label}</span>
        </label>)}
      </fieldset>
      {errorFor("housing")}
      <label>Monthly rent or mortgage ($)<input name="housingCost" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.monthlyHousingCostMinor / 100).toFixed(2) : ""} {...described("monthlyHousingCostMinor")} /></label>
      {errorFor("monthlyHousingCostMinor")}
    </fieldset>

    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="loan-actions">
      <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save application"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </form>;
}

function AuthorizationStep({ view, busy, Sub, onCancel, onAuthorize }: { view: LoanView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onAuthorize: () => void }) {
  const [authorized, setAuthorized] = useState(false);
  const heading = useId();
  return <section className="loan-authorization" aria-labelledby={heading}>
    <Sub id={heading}>Your written permission for soft credit inquiries</Sub>
    {view.authorization.text.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    <p><a href={howOtdogMakesMoneyLink}>How otdog makes money</a></p>
    <label><input type="checkbox" checked={authorized} onChange={event => setAuthorized(event.target.checked)} />
      I give this written permission for soft inquiries and agree to send my application to {view.authorization.partner} as
      described above.</label>
    <div className="loan-actions">
      <button type="button" disabled={busy || !authorized} onClick={onAuthorize}>{busy ? "Getting pre-approvals…" : "Send and get pre-approvals"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </section>;
}

function Results({ view, Sub, busy, onChoose, onRemove, onAdd }: { view: LoanView; Sub: "h3" | "h4"; busy: boolean;
  onChoose: (offerId: string) => void; onRemove: (offerId: string) => void; onAdd: (input: unknown) => Promise<void> }) {
  const heading = useId();
  const results = view.results;
  const request = results?.request;
  const application = view.application!;
  const item = (offer: LoanOffer, expired = false) => <OfferItem key={offer.id} offer={offer} busy={busy} expired={expired}
    onChoose={onChoose} onRemove={onRemove} />;
  return <section className="loan-results" aria-labelledby={heading}>
    <Sub id={heading}>Your pre-approvals</Sub>
    <p className="loan-money">Companies shown here may pay otdog. That never changes the order or what otdog says.{" "}
      <a href={howOtdogMakesMoneyLink}>How otdog makes money</a></p>
    {!results && <p className="muted">No pre-approvals yet. Get them through the partner, or add an offer you already have.</p>}
    {request && <p className="muted">Requested {day(request.requestedAt)} through {request.synthetic
      ? "the synthetic test partner" : request.partner}.</p>}
    {request?.notice && <p className="notice">{request.notice}</p>}
    {request?.outdated && <p role="alert">Your application changed after these pre-approvals were requested. Get new pre-approvals for it
      as it is now.</p>}
    {results && <>
      <p>Offers for {money(application.amountMinor)} over {application.termMonths} months, lowest total cost first. Payment, interest
        and total cost are otdog's own calculation from each offer's amount, APR, term and fees.</p>
      {results.sameTerms.length === 0 ? <p className="muted">No offer matched the amount and term you asked for.</p>
        : <ol className="loan-offer-list" aria-label="Offers for the amount and term you asked for">
          {results.sameTerms.map(offer => item(offer))}
        </ol>}
      {results.differentTerms.length > 0 && <>
        <p>Offers for a different amount or term. These are not ranked against the offers above, because they price a different loan:</p>
        <ul className="loan-offer-list" aria-label="Offers for a different amount or term">{results.differentTerms.map(offer => item(offer))}</ul>
      </>}
      {results.withoutOffer.length > 0 && <>
        <p>Lenders that did not make an offer:</p>
        <ul className="loan-outcomes" aria-label="Lenders that did not make an offer">
          {results.withoutOffer.map(entry => <li key={entry.lender}><strong>{entry.lender}</strong>: {OUTCOMES[entry.outcome]}</li>)}
        </ul>
      </>}
      {results.expired.length > 0 && <>
        <p>Expired offers. They are not compared:</p>
        <ul className="loan-offer-list" aria-label="Expired offers">{results.expired.map(offer => item(offer, true))}</ul>
      </>}
      <p className="muted">You don't have to take any of these offers. otdog never accepts an offer or signs a loan.</p>
    </>}
    <OwnOfferForm busy={busy} onAdd={onAdd} />
  </section>;
}

function OfferItem({ offer, busy, expired, onChoose, onRemove }: { offer: LoanOffer; busy: boolean; expired: boolean;
  onChoose: (offerId: string) => void; onRemove: (offerId: string) => void }) {
  const reconciliation = reconciliationText(offer);
  const conditions = conditionsText(offer.conditions);
  return <li className="loan-offer">
    <p className="loan-offer-lender"><strong>{offer.lender}</strong>
      {offer.source === "BUYER_ENTERED" && <span className="loan-tag">Entered by you</span>}
      {offer.chosen && <span className="loan-tag loan-tag-chosen">Your own financing</span>}</p>
    <p className="loan-offer-terms">{offer.aprPercent}% APR · {offer.termMonths} months · {money(offer.amountMinor)}</p>
    {offer.differences.length > 0 && <p>Differs from your application in its {offer.differences
      .map(difference => difference === "AMOUNT" ? "amount" : "term").join(" and ")}.</p>}
    {offer.paymentMinor != null && offer.totalCostMinor != null && offer.totalInterestMinor != null ? <dl className="loan-figures">
      <div><dt>Monthly payment</dt><dd>{money(offer.paymentMinor)}</dd></div>
      <div><dt>Total interest</dt><dd>{money(offer.totalInterestMinor)}</dd></div>
      {offer.upfrontFeesMinor > 0 && <div><dt>Upfront fees</dt><dd>{money(offer.upfrontFeesMinor)}</dd></div>}
      <div><dt>Total cost</dt><dd>{money(offer.totalCostMinor)}</dd></div>
    </dl> : <p className="muted">These terms are outside the ordinary monthly loan otdog can calculate.</p>}
    {reconciliation && <p>{reconciliation}</p>}
    {conditions && <p>{conditions}</p>}
    <p className="muted">{offer.expiresAt ? `${expired ? "Expired" : "Good until"} ${day(offer.expiresAt)}` : "No end date entered"}</p>
    <div className="loan-actions">
      {!expired && !offer.chosen && <button type="button" disabled={busy} aria-label={`Use this pre-approval from ${offer.lender}`}
        onClick={() => onChoose(offer.id)}>Use this pre-approval</button>}
      {offer.continueUrl && !expired && <a className="loan-continue" href={offer.continueUrl} target="_blank" rel="noopener noreferrer">
        Continue with {offer.lender} on its own site</a>}
      {offer.source === "BUYER_ENTERED" && <button type="button" className="btn-secondary" disabled={busy}
        onClick={() => onRemove(offer.id)}>Remove {offer.lender}'s offer</button>}
    </div>
  </li>;
}

function OwnOfferForm({ busy, onAdd }: { busy: boolean; onAdd: (input: unknown) => Promise<void> }) {
  const prefix = useId();
  const [open, setOpen] = useState(false);
  const [problem, setProblem] = useState<Problem>();
  const { described, errorFor } = useFieldErrors(prefix, problem);
  if (!open) return <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>Add an offer I already have</button>;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const optional = (value: string, parse: (text: string) => number | null) => value ? parse(value) ?? -1 : null;
    try {
      await onAdd({ lender: text("lender"), aprPercent: optional(text("apr"), percent), amountMinor: optional(text("amount"), toMinor),
        termMonths: optional(text("term"), whole), upfrontFeesMinor: optional(text("fees"), toMinor),
        lenderPaymentMinor: optional(text("payment"), toMinor), offeredOn: text("offeredOn") || null, expiresOn: text("expiresOn") || null });
      setProblem(undefined); setOpen(false);
    } catch (failure) { setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message)); }
  }
  return <form className="loan-form loan-own" onSubmit={event => void submit(event)} noValidate aria-label="An offer you already have">
    <p className="muted">An offer from a lender that does not work with the partner, such as your bank or credit union. It is costed and
      compared on the same terms as the partner's offers. Leave the amount and term empty when they match your application.</p>
    <label>Lender<input name="lender" autoComplete="off" maxLength={100} {...described("lender")} /></label>
    {errorFor("lender")}
    <label>APR (%)<input name="apr" inputMode="decimal" autoComplete="off" {...described("aprPercent")} /></label>
    {errorFor("aprPercent")}
    <label>Amount ($, optional)<input name="amount" inputMode="decimal" autoComplete="off" {...described("amountMinor")} /></label>
    {errorFor("amountMinor")}
    <label>Term in months (optional)<input name="term" inputMode="numeric" autoComplete="off" {...described("termMonths")} /></label>
    {errorFor("termMonths")}
    <label>Upfront lender fees ($, optional)<input name="fees" inputMode="decimal" autoComplete="off" {...described("upfrontFeesMinor")} /></label>
    {errorFor("upfrontFeesMinor")}
    <label>Monthly payment the lender stated ($, optional)<input name="payment" inputMode="decimal" autoComplete="off"
      {...described("lenderPaymentMinor")} /></label>
    {errorFor("lenderPaymentMinor")}
    <label>Date of the offer (optional)<input name="offeredOn" type="date" {...described("offeredOn")} /></label>
    {errorFor("offeredOn")}
    <label>Good until (optional)<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
    {errorFor("expiresOn")}
    {problem && <p role="alert">{problem.message}</p>}
    <div className="loan-actions">
      <button type="submit" disabled={busy}>Add offer</button>
      <button type="button" className="btn-secondary" onClick={() => { setOpen(false); setProblem(undefined); }}>Cancel</button>
    </div>
  </form>;
}
