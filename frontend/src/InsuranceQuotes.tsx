import { FormEvent, ReactNode, useEffect, useId, useRef, useState } from "react";
import "./InsuranceQuotes.css";
import { howOtdogMakesMoneyLink } from "./HowOtdogMakesMoney";

export type CoverageType = "BODILY_INJURY_LIABILITY" | "PROPERTY_DAMAGE_LIABILITY" | "PERSONAL_INJURY_PROTECTION"
  | "UNINSURED_MOTORIST" | "COLLISION" | "COMPREHENSIVE";
type Term = "SIX_MONTHS" | "TWELVE_MONTHS";
type Usage = "COMMUTE" | "PLEASURE" | "BUSINESS";
export type CoverageLine = { type: CoverageType; perPersonLimitMinor: number | null; perAccidentLimitMinor: number | null;
  deductibleMinor: number | null };
type Driver = { ageYears: number; licenceState: string; yearsLicensed: number; atFaultAccidents: number; violations: number };
type CurrentPolicy = { insurer: string | null; premiumMinor: number; term: Term; coverage: "SAME_AS_CHOSEN" | "DIFFERENT" | "NOT_SURE";
  lines: CoverageLine[] | null };
export type InsuranceApplication = {
  drivers: Driver[]; garaging: { zip: string; state: string };
  vehicle: { vin: string | null; modelYear: number; make: string; model: string; trim: string | null; usage: Usage;
    annualMileage: number | null };
  coverage: CoverageLine[]; currentPolicy: CurrentPolicy | null;
};
type Difference = { type: CoverageType; aspect: "NOT_IN_QUOTE" | "NOT_CHOSEN" | "PER_PERSON_LIMIT" | "PER_ACCIDENT_LIMIT" | "DEDUCTIBLE";
  chosenMinor: number | null; pricedMinor: number | null };
type YearlyDifference = { quoteYearlyMinor: number; currentYearlyMinor: number; quoteMinusCurrentMinor: number;
  currentCoverage: "SAME_AS_CHOSEN" | "DIFFERENT_FROM_CHOSEN" | "NOT_ENTERED" };
export type InsuranceQuote = { id: string; insurer: string; source: "PARTNER" | "BUYER_ENTERED"; premiumMinor: number; term: Term;
  yearlyPremiumMinor: number; quotedAt: string; expiresAt: string | null; continueUrl: string | null; differences: Difference[];
  yearlyDifference: YearlyDifference | null };
type Outcome = "OFFERED" | "DECLINED" | "NEEDS_MORE_INFORMATION" | "UNAVAILABLE" | "TIMED_OUT";
export type InsuranceView = {
  holder: "JOURNEY" | "CASE";
  caseVehicle: { vin: string | null; modelYear: number | null; make: string | null; model: string | null; trim: string | null } | null;
  revision: number | null; changedAt: string | null; application: InsuranceApplication | null;
  consent: { version: string; partner: string; synthetic: boolean; text: string[] };
  coverageGuide: { type: CoverageType; label: string; explanation: string; perPersonLimit: boolean; perAccidentLimit: boolean;
    deductible: boolean }[];
  results: null | {
    request: null | { id: string; requestedAt: string; retrievedAt: string; partner: string; synthetic: boolean;
      availability: string; notice: string | null; outdated: boolean };
    sameCoverage: InsuranceQuote[]; differentCoverage: InsuranceQuote[]; expired: InsuranceQuote[];
    withoutQuote: { insurer: string; outcome: Outcome }[]; currentPremiumEntered: boolean;
  };
};
type FieldError = { field: string; message: string };
class Problem extends Error {
  constructor(message: string, readonly errors: FieldError[] = [], readonly status = 0) { super(message); }
}
type Access = { caseId: string; token: string } | { journeyId: string; onSessionEnded: () => void };

const STATES = ["AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA",
  "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
  "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY"];
const USAGES: { value: Usage; label: string }[] = [
  { value: "COMMUTE", label: "Commuting to work or school" }, { value: "PLEASURE", label: "Errands and pleasure" },
  { value: "BUSINESS", label: "Business" }];
const TERMS: { value: Term; label: string }[] = [{ value: "SIX_MONTHS", label: "6 months" }, { value: "TWELVE_MONTHS", label: "12 months" }];
const OUTCOMES: Record<Outcome, string> = {
  OFFERED: "Quoted", DECLINED: "Declined to quote", NEEDS_MORE_INFORMATION: "Needs more information before quoting",
  UNAVAILABLE: "Unavailable", TIMED_OUT: "Did not answer in time" };
const dollars = (values: number[]) => values.map(value => value * 100);
const PER_PERSON = dollars([10000, 25000, 50000, 100000, 250000, 500000]);
const PER_ACCIDENT = dollars([10000, 20000, 25000, 50000, 100000, 300000, 500000]);
const DEDUCTIBLES = dollars([0, 250, 500, 1000, 1500, 2000, 2500]);
/** Florida requires personal injury protection and property damage liability of at least $10,000 each. */
const STARTING_COVERAGE: CoverageLine[] = [
  { type: "PROPERTY_DAMAGE_LIABILITY", perPersonLimitMinor: null, perAccidentLimitMinor: 1000000, deductibleMinor: null },
  { type: "PERSONAL_INJURY_PROTECTION", perPersonLimitMinor: 1000000, perAccidentLimitMinor: null, deductibleMinor: 0 }];

function money(minor: number) {
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
const perTerm = (term: Term) => term === "SIX_MONTHS" ? "6 months" : "12 months";

function asView(value: unknown): InsuranceView {
  const v = value as Partial<InsuranceView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !v.consent || !Array.isArray(v.coverageGuide))
    throw new Problem("Your insurance application is unavailable. Try refreshing.");
  return v as InsuranceView;
}

/** One line of a priced coverage that departs from the Buyer's choice, in plain words. */
export function differenceText(difference: Difference, label: string) {
  const priced = difference.pricedMinor == null ? "none" : money(difference.pricedMinor);
  const chosen = difference.chosenMinor == null ? "none" : money(difference.chosenMinor);
  switch (difference.aspect) {
    case "NOT_IN_QUOTE": return `${label}: you chose it, and this quote leaves it out.`;
    case "NOT_CHOSEN": return `${label}: included in this quote, though you did not choose it.`;
    case "PER_PERSON_LIMIT": return `${label} limit per person: ${priced} here, ${chosen} chosen.`;
    case "PER_ACCIDENT_LIMIT": return `${label} limit per accident: ${priced} here, ${chosen} chosen.`;
    case "DEDUCTIBLE": return `${label} deductible: ${priced} here, ${chosen} chosen.`;
  }
}

/** The yearly difference as arithmetic only (INS-04): what the quote costs a year against what the Buyer pays now. */
export function yearlyDifferenceText(difference: YearlyDifference) {
  const amount = money(Math.abs(difference.quoteMinusCurrentMinor));
  const now = money(difference.currentYearlyMinor);
  if (difference.quoteMinusCurrentMinor === 0) return `The same yearly cost as the ${now} a year you pay now.`;
  return `${amount} a year ${difference.quoteMinusCurrentMinor < 0 ? "less" : "more"} than the ${now} a year you pay now.`;
}

/**
 * Car insurance quotes from many insurers with one application, through a licensed comparison partner (spec 5.10,
 * INS-01 to INS-04). Opened from a case it uses the case's credential, and a case in a Buying Journey shares the
 * journey's one application; opened from a journey on its own it uses the account session.
 */
export function InsuranceQuotes(props: Access & { level?: 2 | 3 }) {
  const level = props.level ?? 2;
  const id = useId();
  const [view, setView] = useState<InsuranceView>();
  const [error, setError] = useState<string>();
  const [mode, setMode] = useState<"summary" | "editing" | "consent">("summary");
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);
  const isCase = "caseId" in props;
  const path = isCase ? `/api/v1/cases/${encodeURIComponent(props.caseId)}/insurance`
    : `/api/v1/journeys/${encodeURIComponent(props.journeyId)}/insurance`;
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
    if (response.status === 204) throw new Problem("Your insurance application is unavailable. Try refreshing.", [], 204);
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
  const headingId = `${id}-insurance-heading`;
  if (!view) return error ? <section className="panel insurance-quotes" aria-labelledby={headingId}>
    <Heading id={headingId}>Car insurance quotes</Heading><p className="notice">{error}</p></section> : null;

  const application = view.application;
  const labels = Object.fromEntries(view.coverageGuide.map(guide => [guide.type, guide.label])) as Record<CoverageType, string>;

  async function act(action: () => Promise<InsuranceView>) {
    setBusy(true); setError(undefined);
    try { setView(await action()); return true; }
    catch (failure) { if ((failure as Problem).status !== 401) setError((failure as Error).message); return false; }
    finally { setBusy(false); }
  }

  return <section className="panel insurance-quotes" aria-labelledby={headingId}>
    <div className="section-heading"><div>
      <p className="step">Insurance</p>
      <Heading id={headingId}>Car insurance quotes</Heading>
    </div></div>
    <p>Compare car insurance quotes from many insurers with one application, through a licensed comparison partner.
      It's optional, and getting quotes does not buy a policy. Quotes for the coverage you choose are listed by price.</p>
    <p className="muted">{view.holder === "JOURNEY"
      ? "This application belongs to your Buying Journey, so every case in the journey uses the same one."
      : "This case holds its own application."}</p>

    {!application && mode !== "editing" && <button type="button" onClick={() => setMode("editing")}>Start an insurance application</button>}
    {application && mode === "summary" && <>
      <ApplicationSummary application={application} labels={labels} Sub={Sub} />
      <div className="insurance-actions">
        <button type="button" disabled={busy} onClick={() => setMode("consent")}>{view.results?.request ? "Get new quotes" : "Get quotes"}</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => setMode("editing")}>Edit application</button>
        {!removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => setRemoving(true)}>Remove application</button>}
        {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void act(() => request(path, { method: "DELETE" }))
          .then(done => { if (done) setRemoving(false); })}>Remove the application and its quotes</button>}
      </div>
    </>}
    {mode === "editing" && <ApplicationForm view={view} busy={busy} Sub={Sub} onCancel={() => setMode("summary")} onSave={async input => {
      setBusy(true); setError(undefined);
      try {
        setView(await request(path, { method: "PUT", body: JSON.stringify({ basedOnRevision: view.revision, ...input }) }));
        setMode("summary");
      } finally { setBusy(false); }
    }} />}
    {mode === "consent" && application && <ConsentStep view={view} busy={busy} Sub={Sub} onCancel={() => setMode("summary")}
      onAgree={() => void act(() => request(`${path}/quote-requests`, { method: "POST", key: crypto.randomUUID(),
        body: JSON.stringify({ consentTextVersion: view.consent.version, agreed: true }) })).then(done => { if (done) setMode("summary"); })} />}
    {error && <p role="alert">{error}</p>}
    {application && mode === "summary" && <Results view={view} labels={labels} Sub={Sub} busy={busy}
      onRemove={quoteId => void act(() => request(`${path}/own-quotes/${encodeURIComponent(quoteId)}`, { method: "DELETE" }))}
      onAdd={async input => {
        setBusy(true); setError(undefined);
        try { setView(await request(`${path}/own-quotes`, { method: "POST", key: crypto.randomUUID(), body: JSON.stringify(input) })); }
        finally { setBusy(false); }
      }} />}
  </section>;
}

function ApplicationSummary({ application, labels, Sub }: { application: InsuranceApplication; labels: Record<CoverageType, string>;
  Sub: "h3" | "h4" }) {
  const v = application.vehicle;
  const current = application.currentPolicy;
  return <div className="insurance-summary">
    <Sub>Your application</Sub>
    <dl className="facts">
      <div><dt>Drivers</dt><dd>{application.drivers.map((driver, index) =>
        <span key={index} className="insurance-line">Driver {index + 1}: age {driver.ageYears}, licensed {driver.yearsLicensed} years
          in {driver.licenceState}, {driver.atFaultAccidents} at-fault accidents, {driver.violations} tickets</span>)}</dd></div>
      <div><dt>Where the car is kept</dt><dd>{application.garaging.zip}, {application.garaging.state}</dd></div>
      <div><dt>Car</dt><dd>{[v.modelYear, v.make, v.model, v.trim].filter(Boolean).join(" ")}
        {v.annualMileage != null && `, ${v.annualMileage.toLocaleString("en-US")} miles a year`}</dd></div>
      <div><dt>Coverage you chose</dt><dd>{application.coverage.map(line =>
        <span key={line.type} className="insurance-line">{labels[line.type]}: {amounts(line)}</span>)}</dd></div>
      <div><dt>Current policy</dt><dd>{current ? `${current.insurer ?? "Insurer not given"}, ${money(current.premiumMinor)} for ${perTerm(current.term)}`
        : "Not entered"}</dd></div>
    </dl>
  </div>;
}

function amounts(line: CoverageLine) {
  const parts = [];
  if (line.perPersonLimitMinor != null) parts.push(`${money(line.perPersonLimitMinor)} per person`);
  if (line.perAccidentLimitMinor != null) parts.push(`${money(line.perAccidentLimitMinor)} per accident`);
  if (line.deductibleMinor != null) parts.push(`${money(line.deductibleMinor)} deductible`);
  return parts.join(", ");
}

type DriverDraft = { ageYears: string; licenceState: string; yearsLicensed: string; atFaultAccidents: string; violations: string };
const emptyDriver = (): DriverDraft => ({ ageYears: "", licenceState: "FL", yearsLicensed: "", atFaultAccidents: "0", violations: "0" });

function useFieldErrors(prefix: string, problem?: Problem) {
  const message = (field: string) => problem?.errors.find(error => error.field === field)?.message;
  const domId = (field: string) => `${prefix}-${field.replace(/[^A-Za-z0-9]/g, "-")}-error`;
  return {
    described: (field: string) => message(field) ? { "aria-invalid": true as const, "aria-describedby": domId(field) } : {},
    errorFor: (field: string) => message(field) && <p className="error field-error" id={domId(field)}>{message(field)}</p>,
    describedBy: (field: string) => message(field) ? domId(field) : undefined,
  };
}

function ApplicationForm({ view, busy, Sub, onCancel, onSave }: { view: InsuranceView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onSave: (input: object) => Promise<void> }) {
  const prefix = useId();
  const application = view.application;
  const fromCase = view.caseVehicle;
  const [drivers, setDrivers] = useState<DriverDraft[]>(application ? application.drivers.map(driver => ({
    ageYears: String(driver.ageYears), licenceState: driver.licenceState, yearsLicensed: String(driver.yearsLicensed),
    atFaultAccidents: String(driver.atFaultAccidents), violations: String(driver.violations) })) : [emptyDriver()]);
  const startVehicle = application?.vehicle ?? null;
  const [vehicle, setVehicle] = useState({
    vin: startVehicle?.vin ?? fromCase?.vin ?? "", modelYear: String(startVehicle?.modelYear ?? fromCase?.modelYear ?? ""),
    make: startVehicle?.make ?? fromCase?.make ?? "", model: startVehicle?.model ?? fromCase?.model ?? "",
    trim: startVehicle?.trim ?? fromCase?.trim ?? "", usage: startVehicle?.usage ?? "", annualMileage:
      startVehicle?.annualMileage == null ? "" : String(startVehicle.annualMileage) });
  const [coverage, setCoverage] = useState<CoverageLine[]>(application?.coverage ?? STARTING_COVERAGE);
  const current = application?.currentPolicy ?? null;
  const [hasCurrent, setHasCurrent] = useState(Boolean(current));
  const [currentCoverage, setCurrentCoverage] = useState(current?.coverage ?? "SAME_AS_CHOSEN");
  const [currentLines, setCurrentLines] = useState<CoverageLine[]>(current?.lines ?? application?.coverage ?? STARTING_COVERAGE);
  const [problem, setProblem] = useState<Problem>();
  const summary = useRef<HTMLParagraphElement>(null);
  const { described, errorFor, describedBy } = useFieldErrors(prefix, problem);
  const caseCarDiffers = Boolean(fromCase && (fromCase.make || fromCase.vin) && application && (fromCase.vin
    ? fromCase.vin !== application.vehicle.vin : fromCase.model !== application.vehicle.model || fromCase.modelYear !== application.vehicle.modelYear));

  const number = (text: string) => text.trim() === "" ? null : whole(text) ?? -1;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const premium = text("currentPremium");
    try {
      await onSave({
        drivers: drivers.map(driver => ({ ageYears: number(driver.ageYears), licenceState: driver.licenceState,
          yearsLicensed: number(driver.yearsLicensed), atFaultAccidents: number(driver.atFaultAccidents), violations: number(driver.violations) })),
        garaging: { zip: text("zip"), state: text("garagingState") },
        vehicle: { vin: vehicle.vin.trim() || null, modelYear: number(vehicle.modelYear), make: vehicle.make, model: vehicle.model,
          trim: vehicle.trim.trim() || null, usage: vehicle.usage || null, annualMileage: number(vehicle.annualMileage) },
        coverage,
        currentPolicy: hasCurrent ? { insurer: text("currentInsurer") || null, premiumMinor: premium ? toMinor(premium) ?? -1 : null,
          term: text("currentTerm") || null, coverage: currentCoverage, lines: currentCoverage === "DIFFERENT" ? currentLines : null } : null,
      });
    } catch (failure) {
      setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message));
      requestAnimationFrame(() => summary.current?.focus());
    }
  }

  const setDriver = (index: number, field: keyof DriverDraft, value: string) =>
    setDrivers(list => list.map((driver, at) => at === index ? { ...driver, [field]: value } : driver));

  return <form className="insurance-form" onSubmit={event => void submit(event)} noValidate aria-label="Insurance application">
    <p className="muted">Only what insurers use to price a quote. No names, dates of birth, licence numbers or Social Security
      numbers are asked for here; an insurer may ask for them on its own site if you continue with it.</p>

    <fieldset className="insurance-group">
      <legend>Drivers</legend>
      {errorFor("drivers")}
      {drivers.map((driver, index) => {
        const field = (name: string) => `drivers[${index}].${name}`;
        return <div key={index} className="insurance-driver" role="group" aria-label={`Driver ${index + 1}`}>
          <Sub className="insurance-driver-title">Driver {index + 1}</Sub>
          <label>Age<input inputMode="numeric" autoComplete="off" value={driver.ageYears}
            onChange={event => setDriver(index, "ageYears", event.target.value)} {...described(field("ageYears"))} /></label>
          {errorFor(field("ageYears"))}
          <label>State that issued the licence<select value={driver.licenceState}
            onChange={event => setDriver(index, "licenceState", event.target.value)} {...described(field("licenceState"))}>
            {STATES.map(state => <option key={state} value={state}>{state}</option>)}
          </select></label>
          {errorFor(field("licenceState"))}
          <label>Years licensed<input inputMode="numeric" autoComplete="off" value={driver.yearsLicensed}
            onChange={event => setDriver(index, "yearsLicensed", event.target.value)} {...described(field("yearsLicensed"))} /></label>
          {errorFor(field("yearsLicensed"))}
          <label>At-fault accidents, last 5 years<input inputMode="numeric" autoComplete="off" value={driver.atFaultAccidents}
            onChange={event => setDriver(index, "atFaultAccidents", event.target.value)} {...described(field("atFaultAccidents"))} /></label>
          {errorFor(field("atFaultAccidents"))}
          <label>Tickets, last 3 years<input inputMode="numeric" autoComplete="off" value={driver.violations}
            onChange={event => setDriver(index, "violations", event.target.value)} {...described(field("violations"))} /></label>
          {errorFor(field("violations"))}
          {drivers.length > 1 && <button type="button" className="btn-secondary"
            onClick={() => setDrivers(list => list.filter((_, at) => at !== index))}>Remove driver {index + 1}</button>}
        </div>;
      })}
      {drivers.length < 6 && <button type="button" className="btn-secondary" onClick={() => setDrivers(list => [...list, emptyDriver()])}>
        Add another driver</button>}
    </fieldset>

    <fieldset className="insurance-group">
      <legend>Where the car is kept overnight</legend>
      <label>ZIP code<input name="zip" inputMode="numeric" autoComplete="postal-code" maxLength={5}
        defaultValue={application?.garaging.zip ?? ""} {...described("garaging.zip")} /></label>
      {errorFor("garaging.zip")}
      <label>State<select name="garagingState" defaultValue={application?.garaging.state ?? "FL"} {...described("garaging.state")}>
        {STATES.map(state => <option key={state} value={state}>{state}</option>)}
      </select></label>
      {errorFor("garaging.state")}
    </fieldset>

    <fieldset className="insurance-group">
      <legend>The car</legend>
      {fromCase && !application && (fromCase.make || fromCase.vin) && <p className="muted">Filled in from this case's car. Change anything that is not right.</p>}
      {caseCarDiffers && <button type="button" className="btn-secondary" onClick={() => setVehicle(value => ({ ...value,
        vin: fromCase?.vin ?? "", modelYear: String(fromCase?.modelYear ?? ""), make: fromCase?.make ?? "", model: fromCase?.model ?? "",
        trim: fromCase?.trim ?? "" }))}>Use this case's car</button>}
      <label>VIN (optional)<input className="insurance-vin" autoCapitalize="characters" autoComplete="off" spellCheck={false} maxLength={17}
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
      <label>Trim (optional)<input autoComplete="off" maxLength={64} value={vehicle.trim}
        onChange={event => setVehicle(value => ({ ...value, trim: event.target.value }))} {...described("vehicle.trim")} /></label>
      {errorFor("vehicle.trim")}
      <fieldset className="insurance-choices" aria-describedby={describedBy("vehicle.usage")}>
        <legend>Mostly used for</legend>
        {USAGES.map(option => <label key={option.value}>
          <input type="radio" name="usage" value={option.value} checked={vehicle.usage === option.value}
            onChange={() => setVehicle(value => ({ ...value, usage: option.value }))} />
          <span>{option.label}</span>
        </label>)}
      </fieldset>
      {errorFor("vehicle.usage")}
      <label>Miles a year (optional)<input inputMode="numeric" autoComplete="off" value={vehicle.annualMileage}
        onChange={event => setVehicle(value => ({ ...value, annualMileage: event.target.value }))} {...described("vehicle.annualMileage")} /></label>
      {errorFor("vehicle.annualMileage")}
    </fieldset>

    <CoverageEditor legend="Coverage you want" guide={view.coverageGuide} lines={coverage} onChange={setCoverage}
      field="coverage" errors={{ errorFor, describedBy }} explain />

    <fieldset className="insurance-group">
      <legend>Your current policy (optional)</legend>
      <label><input type="checkbox" checked={hasCurrent} onChange={event => setHasCurrent(event.target.checked)} />
        I have a car insurance policy now. Entering what I pay shows each quote's yearly difference.</label>
      {hasCurrent && <>
        <label>Current insurer (optional)<input name="currentInsurer" autoComplete="off" maxLength={100}
          defaultValue={current?.insurer ?? ""} {...described("currentPolicy.insurer")} /></label>
        {errorFor("currentPolicy.insurer")}
        <label>Premium for the whole term ($)<input name="currentPremium" inputMode="decimal" autoComplete="off"
          defaultValue={current ? (current.premiumMinor / 100).toFixed(2) : ""} {...described("currentPolicy.premiumMinor")} /></label>
        {errorFor("currentPolicy.premiumMinor")}
        <fieldset className="insurance-choices" aria-describedby={describedBy("currentPolicy.term")}>
          <legend>Policy term</legend>
          {TERMS.map(option => <label key={option.value}>
            <input type="radio" name="currentTerm" value={option.value} defaultChecked={current?.term === option.value} />
            <span>{option.label}</span>
          </label>)}
        </fieldset>
        {errorFor("currentPolicy.term")}
        <fieldset className="insurance-choices" aria-describedby={describedBy("currentPolicy.coverage")}>
          <legend>Is your current coverage the same as the coverage you chose above?</legend>
          {([["SAME_AS_CHOSEN", "Yes, the same"], ["DIFFERENT", "No, it's different"], ["NOT_SURE", "I'm not sure"]] as const).map(([value, label]) =>
            <label key={value}>
              <input type="radio" name="currentCoverage" value={value} checked={currentCoverage === value} onChange={() => setCurrentCoverage(value)} />
              <span>{label}</span>
            </label>)}
        </fieldset>
        {errorFor("currentPolicy.coverage")}
        {currentCoverage === "DIFFERENT" && <CoverageEditor legend="Your current coverage" guide={view.coverageGuide} lines={currentLines}
          onChange={setCurrentLines} field="currentPolicy.lines" errors={{ errorFor, describedBy }} />}
      </>}
    </fieldset>

    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="insurance-actions">
      <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save application"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </form>;
}

function AmountSelect({ label, value, options, onChange, describedBy }: { label: string; value: number | null; options: number[];
  onChange: (value: number) => void; describedBy?: string }) {
  const choices = value != null && !options.includes(value) ? [...options, value].sort((a, b) => a - b) : options;
  return <label>{label}<select value={value ?? ""} aria-describedby={describedBy} onChange={event => onChange(Number(event.target.value))}>
    {value == null && <option value="">Choose</option>}
    {choices.map(option => <option key={option} value={option}>{money(option)}</option>)}
  </select></label>;
}

function CoverageEditor({ legend, guide, lines, onChange, field, errors, explain = false }: {
  legend: string; guide: InsuranceView["coverageGuide"]; lines: CoverageLine[]; onChange: (lines: CoverageLine[]) => void;
  field: string; errors: { errorFor: (field: string) => ReactNode; describedBy: (field: string) => string | undefined }; explain?: boolean;
}) {
  const id = useId();
  function toggle(entry: InsuranceView["coverageGuide"][number], on: boolean) {
    if (!on) { onChange(lines.filter(line => line.type !== entry.type)); return; }
    onChange([...lines, { type: entry.type,
      perPersonLimitMinor: entry.perPersonLimit ? (entry.type === "PERSONAL_INJURY_PROTECTION" ? 1000000 : 10000000) : null,
      perAccidentLimitMinor: entry.perAccidentLimit ? (entry.perPersonLimit ? 30000000 : 1000000) : null,
      deductibleMinor: entry.deductible ? (entry.type === "PERSONAL_INJURY_PROTECTION" ? 0 : 50000) : null }]);
  }
  const update = (type: CoverageType, change: Partial<CoverageLine>) =>
    onChange(lines.map(line => line.type === type ? { ...line, ...change } : line));
  return <fieldset className="insurance-group insurance-coverage" aria-describedby={errors.describedBy(field)}>
    <legend>{legend}</legend>
    {errors.errorFor(field)}
    {guide.map(entry => {
      const line = lines.find(item => item.type === entry.type);
      const lineField = `${field}.${entry.type}`;
      const explanation = `${id}-${entry.type}-explanation`;
      return <div key={entry.type} className="insurance-coverage-line">
        <label><input type="checkbox" checked={Boolean(line)} onChange={event => toggle(entry, event.target.checked)}
          aria-describedby={explain ? explanation : undefined} />{entry.label}</label>
        {explain && <p className="muted insurance-explanation" id={explanation}>{entry.explanation}</p>}
        {line && entry.perPersonLimit && <AmountSelect label={`${entry.label}: limit per person`} value={line.perPersonLimitMinor}
          options={entry.type === "PERSONAL_INJURY_PROTECTION" ? [1000000] : PER_PERSON} describedBy={errors.describedBy(lineField)}
          onChange={value => update(entry.type, { perPersonLimitMinor: value })} />}
        {line && entry.perAccidentLimit && <AmountSelect label={`${entry.label}: limit per accident`} value={line.perAccidentLimitMinor}
          options={PER_ACCIDENT} describedBy={errors.describedBy(lineField)} onChange={value => update(entry.type, { perAccidentLimitMinor: value })} />}
        {line && entry.deductible && <AmountSelect label={`${entry.label}: deductible`} value={line.deductibleMinor}
          options={entry.type === "PERSONAL_INJURY_PROTECTION" ? DEDUCTIBLES.filter(amount => amount <= 100000) : DEDUCTIBLES.filter(amount => amount > 0)}
          describedBy={errors.describedBy(lineField)} onChange={value => update(entry.type, { deductibleMinor: value })} />}
        {errors.errorFor(lineField)}
      </div>;
    })}
  </fieldset>;
}

function ConsentStep({ view, busy, Sub, onCancel, onAgree }: { view: InsuranceView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onAgree: () => void }) {
  const [agreed, setAgreed] = useState(false);
  const heading = useId();
  return <section className="insurance-consent" aria-labelledby={heading}>
    <Sub id={heading}>Before your application is sent</Sub>
    {view.consent.text.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    <p><a href={howOtdogMakesMoneyLink}>How otdog makes money</a></p>
    <label><input type="checkbox" checked={agreed} onChange={event => setAgreed(event.target.checked)} />
      I agree to send my application to {view.consent.partner} as described above.</label>
    <div className="insurance-actions">
      <button type="button" disabled={busy || !agreed} onClick={onAgree}>{busy ? "Getting quotes…" : "Send and get quotes"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </section>;
}

function Results({ view, labels, Sub, busy, onRemove, onAdd }: { view: InsuranceView; labels: Record<CoverageType, string>;
  Sub: "h3" | "h4"; busy: boolean; onRemove: (quoteId: string) => void; onAdd: (input: unknown) => Promise<void> }) {
  const heading = useId();
  const results = view.results;
  const request = results?.request;
  const differentCurrent = results?.sameCoverage.some(quote => quote.yearlyDifference?.currentCoverage === "DIFFERENT_FROM_CHOSEN");
  const notEnteredCurrent = results?.sameCoverage.some(quote => quote.yearlyDifference?.currentCoverage === "NOT_ENTERED");
  return <section className="insurance-results" aria-labelledby={heading}>
    <Sub id={heading}>Your quotes</Sub>
    <p className="insurance-money">Companies shown here may pay otdog. That never changes the order or what otdog says.{" "}
      <a href={howOtdogMakesMoneyLink}>How otdog makes money</a></p>
    {!results && <p className="muted">No quotes yet. Get quotes through the partner, or add a quote you already have.</p>}
    {request && <p className="muted">Requested {day(request.requestedAt)} through {request.partner === "SYNTHETIC_INSURANCE_PARTNER"
      ? "the synthetic test partner" : request.partner}.</p>}
    {request?.notice && <p className="notice">{request.notice}</p>}
    {request?.outdated && <p role="alert">Your application changed after these quotes were requested. Get new quotes to price it as it is now.</p>}
    {results && <>
      <p>Quotes for the coverage you chose, lowest yearly cost first:</p>
      {results.sameCoverage.length === 0 ? <p className="muted">No quote matched the coverage you chose.</p>
        : <ol className="insurance-quote-list" aria-label="Quotes for the coverage you chose">
          {results.sameCoverage.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} />)}
        </ol>}
      {differentCurrent && <p className="muted">Your current policy's coverage is different from the coverage you chose, so each yearly
        difference compares different coverage.</p>}
      {notEnteredCurrent && <p className="muted">You didn't say whether your current coverage matches the coverage you chose, so the
        yearly differences may compare different coverage.</p>}
      {results.differentCoverage.length > 0 && <>
        <p>Quotes for different coverage. These are not ranked against the quotes above, because they price something else:</p>
        <ul className="insurance-quote-list" aria-label="Quotes for different coverage">
          {results.differentCoverage.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} />)}
        </ul>
      </>}
      {results.withoutQuote.length > 0 && <>
        <p>Insurers that did not quote:</p>
        <ul className="insurance-outcomes" aria-label="Insurers that did not quote">
          {results.withoutQuote.map(entry => <li key={entry.insurer}><strong>{entry.insurer}</strong>: {OUTCOMES[entry.outcome]}</li>)}
        </ul>
      </>}
      {results.expired.length > 0 && <>
        <p>Expired quotes. They are not compared:</p>
        <ul className="insurance-quote-list" aria-label="Expired quotes">
          {results.expired.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} expired />)}
        </ul>
      </>}
      <p className="muted">You don't have to take any of these quotes. otdog never buys, accepts or binds a policy.</p>
    </>}
    <OwnQuoteForm view={view} busy={busy} onAdd={onAdd} />
  </section>;
}

function QuoteItem({ quote, labels, busy, onRemove, expired = false }: { quote: InsuranceQuote; labels: Record<CoverageType, string>;
  busy: boolean; onRemove: (quoteId: string) => void; expired?: boolean }) {
  return <li className="insurance-quote">
    <p className="insurance-quote-insurer"><strong>{quote.insurer}</strong>
      {quote.source === "BUYER_ENTERED" && <span className="insurance-tag">Entered by you</span>}</p>
    <p className="insurance-quote-price">{money(quote.premiumMinor)} for {perTerm(quote.term)}
      {quote.term === "SIX_MONTHS" && <span className="muted"> ({money(quote.yearlyPremiumMinor)} a year)</span>}</p>
    {quote.yearlyDifference && <p>{yearlyDifferenceText(quote.yearlyDifference)}</p>}
    {quote.differences.length > 0 && <ul className="insurance-differences" aria-label={`How ${quote.insurer}'s coverage differs`}>
      {quote.differences.map((difference, index) => <li key={index}>{differenceText(difference, labels[difference.type])}</li>)}
    </ul>}
    <p className="muted">{quote.expiresAt ? `${expired ? "Expired" : "Good until"} ${day(quote.expiresAt)}` : "No end date entered"}</p>
    {quote.continueUrl && !expired && <a className="insurance-continue" href={quote.continueUrl} target="_blank" rel="noopener noreferrer">
      Continue with {quote.insurer} on its own site</a>}
    {quote.source === "BUYER_ENTERED" && <button type="button" className="btn-secondary" disabled={busy}
      onClick={() => onRemove(quote.id)}>Remove {quote.insurer}'s quote</button>}
  </li>;
}

function OwnQuoteForm({ view, busy, onAdd }: { view: InsuranceView; busy: boolean; onAdd: (input: unknown) => Promise<void> }) {
  const prefix = useId();
  const [open, setOpen] = useState(false);
  const [same, setSame] = useState(true);
  const [lines, setLines] = useState<CoverageLine[]>(view.application?.coverage ?? STARTING_COVERAGE);
  const [problem, setProblem] = useState<Problem>();
  const { described, errorFor, describedBy } = useFieldErrors(prefix, problem);
  if (!open) return <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>Add a quote I already have</button>;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const text = (name: string) => String(form.get(name) ?? "").trim();
    const premium = text("premium");
    try {
      await onAdd({ insurer: text("insurer"), premiumMinor: premium ? toMinor(premium) ?? -1 : null, term: text("term") || null,
        sameCoverage: same, coverage: same ? null : lines, quotedOn: text("quotedOn") || null, expiresOn: text("expiresOn") || null });
      setProblem(undefined); setOpen(false);
    } catch (failure) { setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message)); }
  }
  return <form className="insurance-form insurance-own" onSubmit={event => void submit(event)} noValidate aria-label="A quote you already have">
    <p className="muted">A quote from an insurer that does not work with the partner, such as your current insurer's renewal. It is
      compared on the same terms as the partner's quotes.</p>
    <label>Insurer<input name="insurer" autoComplete="off" maxLength={100} {...described("insurer")} /></label>
    {errorFor("insurer")}
    <label>Premium for the whole term ($)<input name="premium" inputMode="decimal" autoComplete="off" {...described("premiumMinor")} /></label>
    {errorFor("premiumMinor")}
    <fieldset className="insurance-choices" aria-describedby={describedBy("term")}>
      <legend>Policy term</legend>
      {TERMS.map(option => <label key={option.value}><input type="radio" name="term" value={option.value} /><span>{option.label}</span></label>)}
    </fieldset>
    {errorFor("term")}
    <fieldset className="insurance-choices">
      <legend>Is it for the coverage you chose?</legend>
      <label><input type="radio" name="sameCoverage" checked={same} onChange={() => setSame(true)} /><span>Yes, the same coverage</span></label>
      <label><input type="radio" name="sameCoverage" checked={!same} onChange={() => setSame(false)} /><span>No, different coverage</span></label>
    </fieldset>
    {!same && <CoverageEditor legend="The quote's coverage" guide={view.coverageGuide} lines={lines} onChange={setLines} field="coverage"
      errors={{ errorFor, describedBy }} />}
    <label>Date of the quote (optional)<input name="quotedOn" type="date" {...described("quotedOn")} /></label>
    {errorFor("quotedOn")}
    <label>Good until (optional)<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
    {errorFor("expiresOn")}
    {problem && <p role="alert">{problem.message}</p>}
    <div className="insurance-actions">
      <button type="submit" disabled={busy}>Add quote</button>
      <button type="button" className="btn-secondary" onClick={() => { setOpen(false); setProblem(undefined); }}>Cancel</button>
    </div>
  </form>;
}
