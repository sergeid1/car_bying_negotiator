import { FormEvent, useEffect, useId, useRef, useState } from "react";
import "./LoanPreApprovals.css";
import { howOtdogMakesMoneyLink } from "./HowOtdogMakesMoney";
import { en } from "./i18n/en";
import * as format from "./i18n/format";
import { type Messages, useMessages } from "./i18n/LanguageProvider";
import { fillIn, type Message, type Values } from "./i18n/messages";

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

type LoanMessages = Messages["money"]["loanPreApprovals"];
const HOUSING: Housing[] = ["RENT", "OWN", "OTHER"];
const TERMS = [24, 36, 48, 60, 72, 84];

/** Fills a message outside a component, in the language the provider set. */
function fillText<M extends Message>(message: M, values: Values<M>) {
  return fillIn(format.locale(), message, values);
}

export function money(minor: number) {
  return format.money(minor, { maximumFractionDigits: minor % 100 ? 2 : 0 });
}
function day(iso: string) {
  return format.date(iso, { month: "short", day: "numeric", year: "numeric", timeZone: "America/New_York" });
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

function asView(value: unknown, unavailable: string): LoanView {
  const v = value as Partial<LoanView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !v.authorization)
    throw new Problem(unavailable);
  return v as LoanView;
}

/** The lender's own payment figure beside FIN-01's, as a reconciliation (FIN-02). */
export function reconciliationText(offer: LoanOffer, t: LoanMessages = en.money.loanPreApprovals) {
  if (offer.lenderPaymentMinor == null || offer.lenderMinusCalculatedMinor == null) return null;
  const payment = money(offer.lenderPaymentMinor);
  if (offer.lenderPaymentWithinTolerance) return fillText(t.lenderMatches, { payment });
  const gap = offer.lenderMinusCalculatedMinor;
  return fillText(gap > 0 ? t.lenderMore : t.lenderLess, { payment, gap: money(Math.abs(gap)) });
}

function conditionsText(conditions: LoanOffer["conditions"], t: LoanMessages) {
  if (!conditions) return null;
  const parts = [];
  if (conditions.maximumVehicleAgeYears != null) parts.push(fillText(t.conditionAge, { years: String(conditions.maximumVehicleAgeYears) }));
  if (conditions.maximumMileage != null) parts.push(fillText(t.conditionMileage, { miles: format.number(conditions.maximumMileage) }));
  if (conditions.minimumDownPaymentMinor) parts.push(fillText(t.conditionDown, { amount: money(conditions.minimumDownPaymentMinor) }));
  return parts.length ? fillText(t.conditions, { conditions: parts.join(", ") }) : null;
}

/**
 * Loan pre-approvals from many lenders with one application and soft inquiries only, through a licensed lending
 * partner (spec 5.10, LOAN-01 to LOAN-03). The offer the Buyer chooses becomes their own financing: the outside
 * financing comparison and the Desk Check compare the dealer's financing with it. Opened from a case it uses the
 * case's credential, and a case in a Buying Journey shares the journey's one application; opened from a journey on
 * its own it uses the account session.
 */
export function LoanPreApprovals(props: Access & { level?: 2 | 3 }) {
  const { m: { money: { common: c, loanPreApprovals: t } } } = useMessages();
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
    if (!isCase && response.status === 401) { props.onSessionEnded(); throw new Problem(c.signInEnded, [], 401); }
    if (response.status === 204) throw new Problem(t.unavailable, [], 204);
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Problem(body?.detail ?? c.actionFailed,
      Array.isArray(body?.errors) ? body.errors : [], response.status);
    return asView(body, t.unavailable);
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
    <Heading id={headingId}>{t.heading}</Heading><p className="notice">{error}</p></section> : null;

  const application = view.application;
  async function act(action: () => Promise<LoanView>) {
    setBusy(true); setError(undefined);
    try { setView(await action()); return true; }
    catch (failure) { if ((failure as Problem).status !== 401) setError((failure as Error).message); return false; }
    finally { setBusy(false); }
  }

  return <section className="panel loan-pre-approvals" aria-labelledby={headingId}>
    <div className="section-heading"><div>
      <p className="step">{t.step}</p>
      <Heading id={headingId}>{t.heading}</Heading>
    </div></div>
    <p>{t.intro}</p>
    <p className="muted">{view.holder === "JOURNEY" ? c.holderJourney : c.holderCase}</p>

    {view.chosen && <ChosenSummary chosen={view.chosen} Sub={Sub} busy={busy}
      onClear={() => void act(() => request(`${path}/chosen-offer`, { method: "DELETE" }))} />}

    {!application && mode !== "editing" && <button type="button" onClick={() => setMode("editing")}>{t.start}</button>}
    {application && mode === "summary" && <>
      <ApplicationSummary application={application} Sub={Sub} />
      <div className="loan-actions">
        <button type="button" disabled={busy} onClick={() => setMode("authorization")}>
          {view.results?.request ? t.getNewPreApprovals : t.getPreApprovals}</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => setMode("editing")}>{c.editApplication}</button>
        {!removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => setRemoving(true)}>{c.removeApplication}</button>}
        {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void act(() => request(path, { method: "DELETE" }))
          .then(done => { if (done) setRemoving(false); })}>{t.removeWithOffers}</button>}
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
  const { m: { money: { loanPreApprovals: t } }, fill, fillNodes } = useMessages();
  const heading = useId();
  const terms = { lender: <strong>{chosen.lender}</strong>, apr: String(chosen.aprPercent), months: String(chosen.termMonths),
    amount: money(chosen.amountMinor) };
  return <section className="loan-chosen" aria-labelledby={heading}>
    <Sub id={heading}>{t.ownFinancing}</Sub>
    <p>{chosen.upfrontFeesMinor > 0 ? fillNodes(t.chosenTermsWithFees, { ...terms, fees: money(chosen.upfrontFeesMinor) })
      : fillNodes(t.chosenTerms, terms)}</p>
    {chosen.expired
      ? <p role="alert">{chosen.expiresAt ? fill(t.chosenExpiredOn, { date: day(chosen.expiresAt) }) : t.chosenExpired}</p>
      : <p className="muted">{chosen.expiresAt ? fill(t.chosenComparedUntil, { date: day(chosen.expiresAt) }) : t.chosenCompared}</p>}
    <div className="loan-actions">
      <button type="button" className="btn-secondary" disabled={busy} onClick={onClear}>{t.stopUsing}</button>
    </div>
  </section>;
}

function ApplicationSummary({ application, Sub }: { application: LoanApplication; Sub: "h3" | "h4" }) {
  const { m: { money: { common: c, loanPreApprovals: t } }, fill } = useMessages();
  const v = application.vehicle;
  return <div className="loan-summary">
    <Sub>{c.yourApplication}</Sub>
    <dl className="facts">
      <div><dt>{t.amount}</dt><dd>{fill(t.amountOverTerm, { amount: money(application.amountMinor), months: String(application.termMonths) })}</dd></div>
      <div><dt>{c.car}</dt><dd>{fill(t.carValue, { car: [v.modelYear, v.make, v.model].join(" "), miles: format.number(v.mileage) })}</dd></div>
      <div><dt>{t.yearlyIncome}</dt><dd>{money(application.annualIncomeMinor)}</dd></div>
      <div><dt>{c.housing}</dt><dd>{fill(t.housingValue, { housing: t.housingOptions[application.housing] ?? "",
        amount: money(application.monthlyHousingCostMinor) })}</dd></div>
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
  const { m: { money: { common: c, loanPreApprovals: t } }, fill } = useMessages();
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

  return <form className="loan-form" onSubmit={event => void submit(event)} noValidate aria-label={t.formLabel}>
    <p className="muted">{t.formIntro}</p>

    <fieldset className="loan-group">
      <legend>{t.theLoan}</legend>
      <label>{t.amountToBorrow}<input name="amount" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.amountMinor / 100).toFixed(2) : ""} {...described("amountMinor")} /></label>
      {errorFor("amountMinor")}
      <label>{t.term}<select name="term" defaultValue={String(application?.termMonths ?? 60)} {...described("termMonths")}>
        {[...new Set([...TERMS, application?.termMonths ?? 60])].sort((a, b) => a - b)
          .map(term => <option key={term} value={term}>{fill(t.termMonths, { months: String(term) })}</option>)}
      </select></label>
      {errorFor("termMonths")}
    </fieldset>

    <fieldset className="loan-group">
      <legend>{c.theCar}</legend>
      {fromCase && !application && (fromCase.make || fromCase.vin) && <p className="muted">{c.filledFromCase}</p>}
      {caseCarDiffers && <button type="button" className="btn-secondary" onClick={() => setVehicle({
        vin: fromCase?.vin ?? "", modelYear: String(fromCase?.modelYear ?? ""), make: fromCase?.make ?? "", model: fromCase?.model ?? "",
        mileage: String(fromCase?.mileage ?? "") })}>{c.useCaseCar}</button>}
      <label>{c.vinOptional}<input className="loan-vin" autoCapitalize="characters" autoComplete="off" spellCheck={false} maxLength={17}
        value={vehicle.vin} onChange={event => setVehicle(value => ({ ...value, vin: event.target.value }))} {...described("vehicle.vin")} /></label>
      {errorFor("vehicle.vin")}
      <label>{c.modelYear}<input inputMode="numeric" autoComplete="off" maxLength={4} value={vehicle.modelYear}
        onChange={event => setVehicle(value => ({ ...value, modelYear: event.target.value }))} {...described("vehicle.modelYear")} /></label>
      {errorFor("vehicle.modelYear")}
      <label>{c.make}<input autoComplete="off" maxLength={64} value={vehicle.make}
        onChange={event => setVehicle(value => ({ ...value, make: event.target.value }))} {...described("vehicle.make")} /></label>
      {errorFor("vehicle.make")}
      <label>{c.model}<input autoComplete="off" maxLength={64} value={vehicle.model}
        onChange={event => setVehicle(value => ({ ...value, model: event.target.value }))} {...described("vehicle.model")} /></label>
      {errorFor("vehicle.model")}
      <label>{t.mileage}<input inputMode="numeric" autoComplete="off" value={vehicle.mileage}
        onChange={event => setVehicle(value => ({ ...value, mileage: event.target.value }))} {...described("vehicle.mileage")} /></label>
      {errorFor("vehicle.mileage")}
    </fieldset>

    <fieldset className="loan-group">
      <legend>{t.incomeAndHousing}</legend>
      <label>{t.incomeBeforeTax}<input name="income" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.annualIncomeMinor / 100).toFixed(2) : ""} {...described("annualIncomeMinor")} /></label>
      {errorFor("annualIncomeMinor")}
      <fieldset className="loan-choices" aria-describedby={describedBy("housing")}>
        <legend>{c.housing}</legend>
        {HOUSING.map(option => <label key={option}>
          <input type="radio" name="housing" value={option} checked={housing === option} onChange={() => setHousing(option)} />
          <span>{t.housingOptions[option]}</span>
        </label>)}
      </fieldset>
      {errorFor("housing")}
      <label>{t.housingCost}<input name="housingCost" inputMode="decimal" autoComplete="off"
        defaultValue={application ? (application.monthlyHousingCostMinor / 100).toFixed(2) : ""} {...described("monthlyHousingCostMinor")} /></label>
      {errorFor("monthlyHousingCostMinor")}
    </fieldset>

    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="loan-actions">
      <button type="submit" disabled={busy}>{busy ? c.saving : c.saveApplication}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>{c.cancel}</button>
    </div>
  </form>;
}

function AuthorizationStep({ view, busy, Sub, onCancel, onAuthorize }: { view: LoanView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onAuthorize: () => void }) {
  const { m: { money: { common: c, loanPreApprovals: t } }, fill } = useMessages();
  const [authorized, setAuthorized] = useState(false);
  const heading = useId();
  return <section className="loan-authorization" aria-labelledby={heading}>
    <Sub id={heading}>{t.permissionHeading}</Sub>
    {view.authorization.text.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    <p><a href={howOtdogMakesMoneyLink}>{c.moneyLink}</a></p>
    <label><input type="checkbox" checked={authorized} onChange={event => setAuthorized(event.target.checked)} />
      {fill(t.permission, { partner: view.authorization.partner })}</label>
    <div className="loan-actions">
      <button type="button" disabled={busy || !authorized} onClick={onAuthorize}>{busy ? t.gettingPreApprovals : t.sendAndGet}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>{c.cancel}</button>
    </div>
  </section>;
}

function Results({ view, Sub, busy, onChoose, onRemove, onAdd }: { view: LoanView; Sub: "h3" | "h4"; busy: boolean;
  onChoose: (offerId: string) => void; onRemove: (offerId: string) => void; onAdd: (input: unknown) => Promise<void> }) {
  const { m: { money: { common: c, loanPreApprovals: t } }, fill } = useMessages();
  const heading = useId();
  const results = view.results;
  const request = results?.request;
  const application = view.application!;
  const item = (offer: LoanOffer, expired = false) => <OfferItem key={offer.id} offer={offer} busy={busy} expired={expired}
    onChoose={onChoose} onRemove={onRemove} />;
  return <section className="loan-results" aria-labelledby={heading}>
    <Sub id={heading}>{t.yourPreApprovals}</Sub>
    <p className="loan-money">{c.companiesMayPay}{" "}
      <a href={howOtdogMakesMoneyLink}>{c.moneyLink}</a></p>
    {!results && <p className="muted">{t.noPreApprovals}</p>}
    {request && <p className="muted">{request.synthetic ? fill(c.requestedSynthetic, { date: day(request.requestedAt) })
      : fill(c.requested, { date: day(request.requestedAt), partner: request.partner })}</p>}
    {request?.notice && <p className="notice">{request.notice}</p>}
    {request?.outdated && <p role="alert">{t.outdated}</p>}
    {results && <>
      <p>{fill(t.offersIntro, { amount: money(application.amountMinor), months: String(application.termMonths) })}</p>
      {results.sameTerms.length === 0 ? <p className="muted">{t.noSameTerms}</p>
        : <ol className="loan-offer-list" aria-label={t.sameTermsList}>
          {results.sameTerms.map(offer => item(offer))}
        </ol>}
      {results.differentTerms.length > 0 && <>
        <p>{t.differentTermsIntro}</p>
        <ul className="loan-offer-list" aria-label={t.differentTermsList}>{results.differentTerms.map(offer => item(offer))}</ul>
      </>}
      {results.withoutOffer.length > 0 && <>
        <p>{t.withoutOfferIntro}</p>
        <ul className="loan-outcomes" aria-label={t.withoutOfferList}>
          {results.withoutOffer.map(entry => <li key={entry.lender}><strong>{entry.lender}</strong>: {t.outcomes[entry.outcome]}</li>)}
        </ul>
      </>}
      {results.expired.length > 0 && <>
        <p>{t.expiredIntro}</p>
        <ul className="loan-offer-list" aria-label={t.expiredList}>{results.expired.map(offer => item(offer, true))}</ul>
      </>}
      <p className="muted">{t.noObligation}</p>
    </>}
    <OwnOfferForm busy={busy} onAdd={onAdd} />
  </section>;
}

function OfferItem({ offer, busy, expired, onChoose, onRemove }: { offer: LoanOffer; busy: boolean; expired: boolean;
  onChoose: (offerId: string) => void; onRemove: (offerId: string) => void }) {
  const { m: { money: { common: c, loanPreApprovals: t } }, fill } = useMessages();
  const reconciliation = reconciliationText(offer, t);
  const conditions = conditionsText(offer.conditions, t);
  return <li className="loan-offer">
    <p className="loan-offer-lender"><strong>{offer.lender}</strong>
      {offer.source === "BUYER_ENTERED" && <span className="loan-tag">{c.enteredByYou}</span>}
      {offer.chosen && <span className="loan-tag loan-tag-chosen">{t.ownFinancing}</span>}</p>
    <p className="loan-offer-terms">{fill(t.offerTerms, { apr: String(offer.aprPercent), months: String(offer.termMonths), amount: money(offer.amountMinor) })}</p>
    {offer.differences.length > 0 && <p>{fill(t.differs, { differences: format.list(offer.differences
      .map(difference => t.differences[difference])) })}</p>}
    {offer.paymentMinor != null && offer.totalCostMinor != null && offer.totalInterestMinor != null ? <dl className="loan-figures">
      <div><dt>{t.monthlyPayment}</dt><dd>{money(offer.paymentMinor)}</dd></div>
      <div><dt>{t.totalInterest}</dt><dd>{money(offer.totalInterestMinor)}</dd></div>
      {offer.upfrontFeesMinor > 0 && <div><dt>{t.upfrontFees}</dt><dd>{money(offer.upfrontFeesMinor)}</dd></div>}
      <div><dt>{t.totalCost}</dt><dd>{money(offer.totalCostMinor)}</dd></div>
    </dl> : <p className="muted">{t.cannotCalculate}</p>}
    {reconciliation && <p>{reconciliation}</p>}
    {conditions && <p>{conditions}</p>}
    <p className="muted">{offer.expiresAt ? fill(expired ? c.expiredDate : c.goodUntilDate, { date: day(offer.expiresAt) }) : c.noEndDate}</p>
    <div className="loan-actions">
      {!expired && !offer.chosen && <button type="button" disabled={busy} aria-label={fill(t.useNamed, { lender: offer.lender })}
        onClick={() => onChoose(offer.id)}>{t.use}</button>}
      {offer.continueUrl && !expired && <a className="loan-continue" href={offer.continueUrl} target="_blank" rel="noopener noreferrer">
        {fill(c.continueOnSite, { name: offer.lender })}</a>}
      {offer.source === "BUYER_ENTERED" && <button type="button" className="btn-secondary" disabled={busy}
        onClick={() => onRemove(offer.id)}>{fill(t.removeOffer, { lender: offer.lender })}</button>}
    </div>
  </li>;
}

function OwnOfferForm({ busy, onAdd }: { busy: boolean; onAdd: (input: unknown) => Promise<void> }) {
  const { m: { money: { common: c, loanPreApprovals: t } } } = useMessages();
  const prefix = useId();
  const [open, setOpen] = useState(false);
  const [problem, setProblem] = useState<Problem>();
  const { described, errorFor } = useFieldErrors(prefix, problem);
  if (!open) return <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>{t.addOwn}</button>;
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
  return <form className="loan-form loan-own" onSubmit={event => void submit(event)} noValidate aria-label={t.ownFormLabel}>
    <p className="muted">{t.ownIntro}</p>
    <label>{t.lender}<input name="lender" autoComplete="off" maxLength={100} {...described("lender")} /></label>
    {errorFor("lender")}
    <label>{t.apr}<input name="apr" inputMode="decimal" autoComplete="off" {...described("aprPercent")} /></label>
    {errorFor("aprPercent")}
    <label>{t.amountOptional}<input name="amount" inputMode="decimal" autoComplete="off" {...described("amountMinor")} /></label>
    {errorFor("amountMinor")}
    <label>{t.termOptional}<input name="term" inputMode="numeric" autoComplete="off" {...described("termMonths")} /></label>
    {errorFor("termMonths")}
    <label>{t.feesOptional}<input name="fees" inputMode="decimal" autoComplete="off" {...described("upfrontFeesMinor")} /></label>
    {errorFor("upfrontFeesMinor")}
    <label>{t.paymentOptional}<input name="payment" inputMode="decimal" autoComplete="off"
      {...described("lenderPaymentMinor")} /></label>
    {errorFor("lenderPaymentMinor")}
    <label>{t.offerDateOptional}<input name="offeredOn" type="date" {...described("offeredOn")} /></label>
    {errorFor("offeredOn")}
    <label>{c.goodUntilOptional}<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
    {errorFor("expiresOn")}
    {problem && <p role="alert">{problem.message}</p>}
    <div className="loan-actions">
      <button type="submit" disabled={busy}>{t.addOffer}</button>
      <button type="button" className="btn-secondary" onClick={() => { setOpen(false); setProblem(undefined); }}>{c.cancel}</button>
    </div>
  </form>;
}
