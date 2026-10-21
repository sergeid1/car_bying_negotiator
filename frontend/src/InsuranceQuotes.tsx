import { FormEvent, ReactNode, useEffect, useId, useRef, useState } from "react";
import "./InsuranceQuotes.css";
import { howOtdogMakesMoneyLink } from "./HowOtdogMakesMoney";
import { en } from "./i18n/en";
import * as format from "./i18n/format";
import { type Messages, useMessages } from "./i18n/LanguageProvider";
import { fillIn, type Message, type Values } from "./i18n/messages";

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
/** A state's minimum for one coverage; {@code rule} is null when the state's list does not name it. */
type MinimumLine = { type: CoverageType; rule: "REQUIRED" | "INCLUDED_UNLESS_REJECTED" | "NOT_REQUIRED" | null;
  perPersonLimitMinor: number | null; perAccidentLimitMinor: number | null; text: string };
export type StateMinimum = { state: string; name: string; checkedOn: string; reviewOn: string; lines: MinimumLine[] };
export type InsuranceView = {
  holder: "JOURNEY" | "CASE";
  caseVehicle: { vin: string | null; modelYear: number | null; make: string | null; model: string | null; trim: string | null } | null;
  revision: number | null; changedAt: string | null; application: InsuranceApplication | null;
  consent: { version: string; partner: string; synthetic: boolean; text: string[] };
  coverageGuide: { type: CoverageType; label: string; explanation: string; perPersonLimit: boolean; perAccidentLimit: boolean;
    deductible: boolean }[];
  buyerState: string | null;
  stateMinimums: { version: string; states: StateMinimum[] };
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
const USAGES: Usage[] = ["COMMUTE", "PLEASURE", "BUSINESS"];
const TERMS: Term[] = ["SIX_MONTHS", "TWELVE_MONTHS"];
const CURRENT_COVERAGE = ["SAME_AS_CHOSEN", "DIFFERENT", "NOT_SURE"] as const;
type InsuranceMessages = Messages["money"]["insuranceQuotes"];

/** Fills a message outside a component, in the language the provider set. */
function fillText<M extends Message>(message: M, values: Values<M>) {
  return fillIn(format.locale(), message, values);
}
const dollars = (values: number[]) => values.map(value => value * 100);
/** Every pilot state's minimum is a choice; the server's state minimums are the source of those amounts. */
const PER_PERSON = dollars([10000, 25000, 30000, 50000, 100000, 250000, 500000]);
const PER_ACCIDENT = dollars([10000, 20000, 25000, 50000, 60000, 100000, 300000, 500000]);
const DEDUCTIBLES = dollars([0, 250, 500, 1000, 1500, 2000, 2500]);
type Guide = InsuranceView["coverageGuide"][number];

const minimumsOf = (view: InsuranceView, state: string) => view.stateMinimums.states.find(entry => entry.state === state);

/** A newly chosen coverage line, at the state's minimum when the state requires the coverage. */
function newLine(entry: Guide, minimum?: MinimumLine): CoverageLine {
  const required = minimum?.rule === "REQUIRED" ? minimum : undefined;
  return { type: entry.type,
    perPersonLimitMinor: entry.perPersonLimit
      ? required?.perPersonLimitMinor ?? (entry.type === "PERSONAL_INJURY_PROTECTION" ? 1000000 : 10000000) : null,
    perAccidentLimitMinor: entry.perAccidentLimit ? required?.perAccidentLimitMinor ?? (entry.perPersonLimit ? 30000000 : 1000000) : null,
    deductibleMinor: entry.deductible ? (entry.type === "PERSONAL_INJURY_PROTECTION" ? 0 : 50000) : null };
}

/** The coverage a state requires, at its minimums; nothing for a state without listed minimums. */
export function startingCoverage(guide: Guide[], minimums?: StateMinimum): CoverageLine[] {
  if (!minimums) return [];
  return guide.flatMap(entry => {
    const minimum = minimums.lines.find(line => line.type === entry.type);
    return minimum?.rule === "REQUIRED" ? [newLine(entry, minimum)] : [];
  });
}

/** Each required coverage that is missing or below the state's minimum, in plain words. */
export function shortfalls(lines: CoverageLine[], guide: Guide[], minimums?: StateMinimum,
  t: InsuranceMessages = en.money.insuranceQuotes): string[] {
  if (!minimums) return [];
  return minimums.lines.flatMap(minimum => {
    if (minimum.rule !== "REQUIRED") return [];
    const label = guide.find(entry => entry.type === minimum.type)?.label ?? minimum.type;
    const line = lines.find(item => item.type === minimum.type);
    const least = amounts({ ...minimum, deductibleMinor: null }, t);
    const values = { coverage: label, state: minimums.name, amounts: least };
    if (!line) return [fillText(t.shortfallNotChosen, values)];
    const below = (minimum.perPersonLimitMinor != null && (line.perPersonLimitMinor ?? 0) < minimum.perPersonLimitMinor)
      || (minimum.perAccidentLimitMinor != null && (line.perAccidentLimitMinor ?? 0) < minimum.perAccidentLimitMinor);
    return below ? [fillText(t.shortfallLess, values)] : [];
  });
}

function MinimumWarning({ gaps, minimums, live = false }: { gaps: string[]; minimums?: StateMinimum; live?: boolean }) {
  const { m: { money: { insuranceQuotes: t } }, fill } = useMessages();
  const content = gaps.length > 0 && minimums && <>
    <p>{fill(t.belowMinimum, { state: minimums.name })}</p>
    <ul>{gaps.map(gap => <li key={gap}>{gap}</li>)}</ul>
    <p>{t.stillYourChoice}</p>
  </>;
  // The live region stays mounted so a new warning is announced while the Buyer edits.
  return live ? <div className={content ? "notice insurance-minimum-warning" : undefined} role="status">{content}</div>
    : content ? <div className="notice insurance-minimum-warning">{content}</div> : null;
}

function money(minor: number) {
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

function asView(value: unknown, unavailable: string): InsuranceView {
  const v = value as Partial<InsuranceView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !v.consent || !Array.isArray(v.coverageGuide))
    throw new Problem(unavailable);
  return v as InsuranceView;
}

/** One line of a priced coverage that departs from the Buyer's choice, in plain words. */
export function differenceText(difference: Difference, label: string, t: InsuranceMessages = en.money.insuranceQuotes) {
  const priced = difference.pricedMinor == null ? t.none : money(difference.pricedMinor);
  const chosen = difference.chosenMinor == null ? t.none : money(difference.chosenMinor);
  const values = { coverage: label, priced, chosen };
  switch (difference.aspect) {
    case "NOT_IN_QUOTE": return fillText(t.notInQuote, { coverage: label });
    case "NOT_CHOSEN": return fillText(t.notChosen, { coverage: label });
    case "PER_PERSON_LIMIT": return fillText(t.perPersonDifference, values);
    case "PER_ACCIDENT_LIMIT": return fillText(t.perAccidentDifference, values);
    case "DEDUCTIBLE": return fillText(t.deductibleDifference, values);
  }
}

/** The yearly difference as arithmetic only (INS-04): what the quote costs a year against what the Buyer pays now. */
export function yearlyDifferenceText(difference: YearlyDifference, t: InsuranceMessages = en.money.insuranceQuotes) {
  const amount = money(Math.abs(difference.quoteMinusCurrentMinor));
  const now = money(difference.currentYearlyMinor);
  if (difference.quoteMinusCurrentMinor === 0) return fillText(t.yearlySame, { now });
  return fillText(difference.quoteMinusCurrentMinor < 0 ? t.yearlyLess : t.yearlyMore, { amount, now });
}

/**
 * Car insurance quotes from many insurers with one application, through a licensed comparison partner (spec 5.10,
 * INS-01 to INS-04). Opened from a case it uses the case's credential, and a case in a Buying Journey shares the
 * journey's one application; opened from a journey on its own it uses the account session.
 */
export function InsuranceQuotes(props: Access & { level?: 2 | 3 }) {
  const { m: { money: { common: c, insuranceQuotes: t } } } = useMessages();
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
  const headingId = `${id}-insurance-heading`;
  if (!view) return error ? <section className="panel insurance-quotes" aria-labelledby={headingId}>
    <Heading id={headingId}>{t.heading}</Heading><p className="notice">{error}</p></section> : null;

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
      <p className="step">{t.step}</p>
      <Heading id={headingId}>{t.heading}</Heading>
    </div></div>
    <p>{t.intro}</p>
    <p className="muted">{view.holder === "JOURNEY" ? c.holderJourney : c.holderCase}</p>

    {!application && mode !== "editing" && <button type="button" onClick={() => setMode("editing")}>{t.start}</button>}
    {application && mode === "summary" && <>
      <ApplicationSummary application={application} labels={labels} Sub={Sub} />
      <div className="insurance-actions">
        <button type="button" disabled={busy} onClick={() => setMode("consent")}>{view.results?.request ? t.getNewQuotes : t.getQuotes}</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => setMode("editing")}>{c.editApplication}</button>
        {!removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => setRemoving(true)}>{c.removeApplication}</button>}
        {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void act(() => request(path, { method: "DELETE" }))
          .then(done => { if (done) setRemoving(false); })}>{t.removeWithQuotes}</button>}
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
  const { m: { money: { common: c, insuranceQuotes: t } }, fill } = useMessages();
  const v = application.vehicle;
  const car = [v.modelYear, v.make, v.model, v.trim].filter(Boolean).join(" ");
  const current = application.currentPolicy;
  return <div className="insurance-summary">
    <Sub>{c.yourApplication}</Sub>
    <dl className="facts">
      <div><dt>{t.drivers}</dt><dd>{application.drivers.map((driver, index) =>
        <span key={index} className="insurance-line">{fill(t.driverSummary, { number: index + 1, age: String(driver.ageYears),
          years: String(driver.yearsLicensed), state: driver.licenceState, accidents: String(driver.atFaultAccidents),
          tickets: String(driver.violations) })}</span>)}</dd></div>
      <div><dt>{t.whereKept}</dt><dd>{fill(t.garagingValue, { zip: application.garaging.zip, state: application.garaging.state })}</dd></div>
      <div><dt>{c.car}</dt><dd>{v.annualMileage != null ? fill(t.carWithMileage, { car, miles: format.number(v.annualMileage) }) : car}</dd></div>
      <div><dt>{t.coverageChosen}</dt><dd>{application.coverage.map(line =>
        <span key={line.type} className="insurance-line">{fill(t.coverageLine, { coverage: labels[line.type], amounts: amounts(line, t) })}</span>)}</dd></div>
      <div><dt>{t.currentPolicy}</dt><dd>{current ? fill(t.currentPolicyValue, { insurer: current.insurer ?? t.insurerNotGiven,
        premium: money(current.premiumMinor), term: t.terms[current.term] })
        : t.notEntered}</dd></div>
    </dl>
  </div>;
}

function amounts(line: CoverageLine, t: InsuranceMessages) {
  const parts = [];
  if (line.perPersonLimitMinor != null) parts.push(fillText(t.perPerson, { amount: money(line.perPersonLimitMinor) }));
  if (line.perAccidentLimitMinor != null) parts.push(fillText(t.perAccident, { amount: money(line.perAccidentLimitMinor) }));
  if (line.deductibleMinor != null) parts.push(fillText(t.deductible, { amount: money(line.deductibleMinor) }));
  return parts.join(", ");
}

type DriverDraft = { ageYears: string; licenceState: string; yearsLicensed: string; atFaultAccidents: string; violations: string };
const emptyDriver = (state: string | null): DriverDraft =>
  ({ ageYears: "", licenceState: state ?? "", yearsLicensed: "", atFaultAccidents: "0", violations: "0" });

function StateSelect({ label, name, value, onChange, described }: { label: string; name?: string; value: string;
  onChange: (state: string) => void; described: object }) {
  const { m: { money: { insuranceQuotes: t } } } = useMessages();
  return <label>{label}<select name={name} value={value} onChange={event => onChange(event.target.value)} {...described}>
    {value === "" && <option value="">{t.chooseState}</option>}
    {STATES.map(state => <option key={state} value={state}>{state}</option>)}
  </select></label>;
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

function ApplicationForm({ view, busy, Sub, onCancel, onSave }: { view: InsuranceView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onSave: (input: object) => Promise<void> }) {
  const { m: { money: { common: c, insuranceQuotes: t } }, fill } = useMessages();
  const prefix = useId();
  const application = view.application;
  const fromCase = view.caseVehicle;
  const [drivers, setDrivers] = useState<DriverDraft[]>(application ? application.drivers.map(driver => ({
    ageYears: String(driver.ageYears), licenceState: driver.licenceState, yearsLicensed: String(driver.yearsLicensed),
    atFaultAccidents: String(driver.atFaultAccidents), violations: String(driver.violations) })) : [emptyDriver(view.buyerState)]);
  const startVehicle = application?.vehicle ?? null;
  const [vehicle, setVehicle] = useState({
    vin: startVehicle?.vin ?? fromCase?.vin ?? "", modelYear: String(startVehicle?.modelYear ?? fromCase?.modelYear ?? ""),
    make: startVehicle?.make ?? fromCase?.make ?? "", model: startVehicle?.model ?? fromCase?.model ?? "",
    trim: startVehicle?.trim ?? fromCase?.trim ?? "", usage: startVehicle?.usage ?? "", annualMileage:
      startVehicle?.annualMileage == null ? "" : String(startVehicle.annualMileage) });
  const [garagingState, setGaragingState] = useState(application?.garaging.state ?? view.buyerState ?? "");
  const [coverage, setCoverage] = useState<CoverageLine[]>(() =>
    application?.coverage ?? startingCoverage(view.coverageGuide, minimumsOf(view, garagingState)));
  // A saved application's coverage is the Buyer's own choice, so a change of state never replaces it silently.
  const [coverageChosen, setCoverageChosen] = useState(Boolean(application));
  const [resetOffered, setResetOffered] = useState(false);
  const current = application?.currentPolicy ?? null;
  const [hasCurrent, setHasCurrent] = useState(Boolean(current));
  const [currentCoverage, setCurrentCoverage] = useState(current?.coverage ?? "SAME_AS_CHOSEN");
  const [currentLines, setCurrentLines] = useState<CoverageLine[]>(current?.lines ?? coverage);
  const minimums = minimumsOf(view, garagingState);
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
        garaging: { zip: text("zip"), state: garagingState },
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
  function changeGaragingState(state: string) {
    setGaragingState(state);
    if (!coverageChosen) { setCoverage(startingCoverage(view.coverageGuide, minimumsOf(view, state))); setResetOffered(false); }
    else setResetOffered(Boolean(minimumsOf(view, state)));
  }

  return <form className="insurance-form" onSubmit={event => void submit(event)} noValidate aria-label={t.formLabel}>
    <p className="muted">{t.formIntro}</p>

    <fieldset className="insurance-group">
      <legend>{t.drivers}</legend>
      {errorFor("drivers")}
      {drivers.map((driver, index) => {
        const field = (name: string) => `drivers[${index}].${name}`;
        return <div key={index} className="insurance-driver" role="group" aria-label={fill(t.driver, { number: index + 1 })}>
          <Sub className="insurance-driver-title">{fill(t.driver, { number: index + 1 })}</Sub>
          <label>{t.age}<input inputMode="numeric" autoComplete="off" value={driver.ageYears}
            onChange={event => setDriver(index, "ageYears", event.target.value)} {...described(field("ageYears"))} /></label>
          {errorFor(field("ageYears"))}
          <StateSelect label={t.licenceState} value={driver.licenceState}
            onChange={state => setDriver(index, "licenceState", state)} described={described(field("licenceState"))} />
          {errorFor(field("licenceState"))}
          <label>{t.yearsLicensed}<input inputMode="numeric" autoComplete="off" value={driver.yearsLicensed}
            onChange={event => setDriver(index, "yearsLicensed", event.target.value)} {...described(field("yearsLicensed"))} /></label>
          {errorFor(field("yearsLicensed"))}
          <label>{t.atFaultAccidents}<input inputMode="numeric" autoComplete="off" value={driver.atFaultAccidents}
            onChange={event => setDriver(index, "atFaultAccidents", event.target.value)} {...described(field("atFaultAccidents"))} /></label>
          {errorFor(field("atFaultAccidents"))}
          <label>{t.tickets}<input inputMode="numeric" autoComplete="off" value={driver.violations}
            onChange={event => setDriver(index, "violations", event.target.value)} {...described(field("violations"))} /></label>
          {errorFor(field("violations"))}
          {drivers.length > 1 && <button type="button" className="btn-secondary"
            onClick={() => setDrivers(list => list.filter((_, at) => at !== index))}>{fill(t.removeDriver, { number: index + 1 })}</button>}
        </div>;
      })}
      {drivers.length < 6 && <button type="button" className="btn-secondary" onClick={() => setDrivers(list => [...list, emptyDriver(view.buyerState)])}>
        {t.addDriver}</button>}
    </fieldset>

    <fieldset className="insurance-group">
      <legend>{t.keptOvernight}</legend>
      <label>{t.zip}<input name="zip" inputMode="numeric" autoComplete="postal-code" maxLength={5}
        defaultValue={application?.garaging.zip ?? ""} {...described("garaging.zip")} /></label>
      {errorFor("garaging.zip")}
      <StateSelect label={t.state} name="garagingState" value={garagingState} onChange={changeGaragingState}
        described={described("garaging.state")} />
      {errorFor("garaging.state")}
      {resetOffered && minimums && <div className="insurance-reset">
        <p className="muted">{t.stateChanged}</p>
        <button type="button" className="btn-secondary" onClick={() => {
          setCoverage(startingCoverage(view.coverageGuide, minimums)); setCoverageChosen(false); setResetOffered(false);
        }}>{fill(t.resetCoverage, { state: minimums.name })}</button>
      </div>}
    </fieldset>

    <fieldset className="insurance-group">
      <legend>{c.theCar}</legend>
      {fromCase && !application && (fromCase.make || fromCase.vin) && <p className="muted">{c.filledFromCase}</p>}
      {caseCarDiffers && <button type="button" className="btn-secondary" onClick={() => setVehicle(value => ({ ...value,
        vin: fromCase?.vin ?? "", modelYear: String(fromCase?.modelYear ?? ""), make: fromCase?.make ?? "", model: fromCase?.model ?? "",
        trim: fromCase?.trim ?? "" }))}>{c.useCaseCar}</button>}
      <label>{c.vinOptional}<input className="insurance-vin" autoCapitalize="characters" autoComplete="off" spellCheck={false} maxLength={17}
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
      <label>{t.trimOptional}<input autoComplete="off" maxLength={64} value={vehicle.trim}
        onChange={event => setVehicle(value => ({ ...value, trim: event.target.value }))} {...described("vehicle.trim")} /></label>
      {errorFor("vehicle.trim")}
      <fieldset className="insurance-choices" aria-describedby={describedBy("vehicle.usage")}>
        <legend>{t.usage}</legend>
        {USAGES.map(option => <label key={option}>
          <input type="radio" name="usage" value={option} checked={vehicle.usage === option}
            onChange={() => setVehicle(value => ({ ...value, usage: option }))} />
          <span>{t.usages[option]}</span>
        </label>)}
      </fieldset>
      {errorFor("vehicle.usage")}
      <label>{t.milesOptional}<input inputMode="numeric" autoComplete="off" value={vehicle.annualMileage}
        onChange={event => setVehicle(value => ({ ...value, annualMileage: event.target.value }))} {...described("vehicle.annualMileage")} /></label>
      {errorFor("vehicle.annualMileage")}
    </fieldset>

    <CoverageEditor legend={t.coverageYouWant} guide={view.coverageGuide} lines={coverage}
      onChange={lines => { setCoverage(lines); setCoverageChosen(true); }} field="coverage" errors={{ errorFor, describedBy }}
      explain={{ state: garagingState, minimums }} />
    <MinimumWarning gaps={shortfalls(coverage, view.coverageGuide, minimums, t)} minimums={minimums} live />

    <fieldset className="insurance-group">
      <legend>{t.currentPolicyOptional}</legend>
      <label><input type="checkbox" checked={hasCurrent} onChange={event => setHasCurrent(event.target.checked)} />
        {t.hasCurrentPolicy}</label>
      {hasCurrent && <>
        <label>{t.currentInsurerOptional}<input name="currentInsurer" autoComplete="off" maxLength={100}
          defaultValue={current?.insurer ?? ""} {...described("currentPolicy.insurer")} /></label>
        {errorFor("currentPolicy.insurer")}
        <label>{t.premiumWholeTerm}<input name="currentPremium" inputMode="decimal" autoComplete="off"
          defaultValue={current ? (current.premiumMinor / 100).toFixed(2) : ""} {...described("currentPolicy.premiumMinor")} /></label>
        {errorFor("currentPolicy.premiumMinor")}
        <fieldset className="insurance-choices" aria-describedby={describedBy("currentPolicy.term")}>
          <legend>{t.policyTerm}</legend>
          {TERMS.map(option => <label key={option}>
            <input type="radio" name="currentTerm" value={option} defaultChecked={current?.term === option} />
            <span>{t.terms[option]}</span>
          </label>)}
        </fieldset>
        {errorFor("currentPolicy.term")}
        <fieldset className="insurance-choices" aria-describedby={describedBy("currentPolicy.coverage")}>
          <legend>{t.currentSameQuestion}</legend>
          {CURRENT_COVERAGE.map(value =>
            <label key={value}>
              <input type="radio" name="currentCoverage" value={value} checked={currentCoverage === value} onChange={() => setCurrentCoverage(value)} />
              <span>{t.currentCoverage[value]}</span>
            </label>)}
        </fieldset>
        {errorFor("currentPolicy.coverage")}
        {currentCoverage === "DIFFERENT" && <CoverageEditor legend={t.yourCurrentCoverage} guide={view.coverageGuide} lines={currentLines}
          onChange={setCurrentLines} field="currentPolicy.lines" errors={{ errorFor, describedBy }} />}
      </>}
    </fieldset>

    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="insurance-actions">
      <button type="submit" disabled={busy}>{busy ? c.saving : c.saveApplication}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>{c.cancel}</button>
    </div>
  </form>;
}

function AmountSelect({ label, value, options, onChange, describedBy }: { label: string; value: number | null; options: number[];
  onChange: (value: number) => void; describedBy?: string }) {
  const { m: { money: { insuranceQuotes: t } } } = useMessages();
  const choices = value != null && !options.includes(value) ? [...options, value].sort((a, b) => a - b) : options;
  return <label>{label}<select value={value ?? ""} aria-describedby={describedBy} onChange={event => onChange(Number(event.target.value))}>
    {value == null && <option value="">{t.choose}</option>}
    {choices.map(option => <option key={option} value={option}>{money(option)}</option>)}
  </select></label>;
}

/** With {@code explain}, each coverage is explained with what the garaging state requires of it. */
function CoverageEditor({ legend, guide, lines, onChange, field, errors, explain }: {
  legend: string; guide: Guide[]; lines: CoverageLine[]; onChange: (lines: CoverageLine[]) => void;
  field: string; errors: { errorFor: (field: string) => ReactNode; describedBy: (field: string) => string | undefined };
  explain?: { state: string; minimums?: StateMinimum };
}) {
  const { m: { money: { insuranceQuotes: t } }, fill } = useMessages();
  const id = useId();
  const minimums = explain?.minimums;
  function toggle(entry: Guide, on: boolean) {
    if (!on) { onChange(lines.filter(line => line.type !== entry.type)); return; }
    onChange([...lines, newLine(entry, minimums?.lines.find(line => line.type === entry.type))]);
  }
  const update = (type: CoverageType, change: Partial<CoverageLine>) =>
    onChange(lines.map(line => line.type === type ? { ...line, ...change } : line));
  return <fieldset className="insurance-group insurance-coverage" aria-describedby={errors.describedBy(field)}>
    <legend>{legend}</legend>
    {errors.errorFor(field)}
    {explain && explain.state !== "" && !minimums && <p className="muted">{fill(t.minimumsNotListed, { state: explain.state })}</p>}
    {guide.map(entry => {
      const line = lines.find(item => item.type === entry.type);
      const lineField = `${field}.${entry.type}`;
      const explanation = `${id}-${entry.type}-explanation`;
      const minimum = minimums?.lines.find(item => item.type === entry.type);
      return <div key={entry.type} className="insurance-coverage-line">
        <label><input type="checkbox" checked={Boolean(line)} onChange={event => toggle(entry, event.target.checked)}
          aria-describedby={explain ? [explanation, minimum && `${explanation}-minimum`].filter(Boolean).join(" ") : undefined} />
          {entry.label}</label>
        {explain && <p className="muted insurance-explanation" id={explanation}>{entry.explanation}</p>}
        {explain && minimum && <p className="insurance-explanation insurance-minimum" id={`${explanation}-minimum`}>{minimum.text}</p>}
        {line && entry.perPersonLimit && <AmountSelect label={fill(t.limitPerPerson, { coverage: entry.label })} value={line.perPersonLimitMinor}
          options={entry.type === "PERSONAL_INJURY_PROTECTION" ? [1000000] : PER_PERSON} describedBy={errors.describedBy(lineField)}
          onChange={value => update(entry.type, { perPersonLimitMinor: value })} />}
        {line && entry.perAccidentLimit && <AmountSelect label={fill(t.limitPerAccident, { coverage: entry.label })} value={line.perAccidentLimitMinor}
          options={PER_ACCIDENT} describedBy={errors.describedBy(lineField)} onChange={value => update(entry.type, { perAccidentLimitMinor: value })} />}
        {line && entry.deductible && <AmountSelect label={fill(t.deductibleLabel, { coverage: entry.label })} value={line.deductibleMinor}
          options={entry.type === "PERSONAL_INJURY_PROTECTION" ? DEDUCTIBLES.filter(amount => amount <= 100000) : DEDUCTIBLES.filter(amount => amount > 0)}
          describedBy={errors.describedBy(lineField)} onChange={value => update(entry.type, { deductibleMinor: value })} />}
        {errors.errorFor(lineField)}
      </div>;
    })}
  </fieldset>;
}

function ConsentStep({ view, busy, Sub, onCancel, onAgree }: { view: InsuranceView; busy: boolean; Sub: "h3" | "h4";
  onCancel: () => void; onAgree: () => void }) {
  const { m: { money: { common: c, insuranceQuotes: t } }, fill } = useMessages();
  const [agreed, setAgreed] = useState(false);
  const heading = useId();
  return <section className="insurance-consent" aria-labelledby={heading}>
    <Sub id={heading}>{t.beforeSent}</Sub>
    {view.application && <MinimumWarning minimums={minimumsOf(view, view.application.garaging.state)}
      gaps={shortfalls(view.application.coverage, view.coverageGuide, minimumsOf(view, view.application.garaging.state), t)} />}
    {view.consent.text.map((paragraph, index) => <p key={index}>{paragraph}</p>)}
    <p><a href={howOtdogMakesMoneyLink}>{c.moneyLink}</a></p>
    <label><input type="checkbox" checked={agreed} onChange={event => setAgreed(event.target.checked)} />
      {fill(t.agree, { partner: view.consent.partner })}</label>
    <div className="insurance-actions">
      <button type="button" disabled={busy || !agreed} onClick={onAgree}>{busy ? t.gettingQuotes : t.sendAndGet}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>{c.cancel}</button>
    </div>
  </section>;
}

function Results({ view, labels, Sub, busy, onRemove, onAdd }: { view: InsuranceView; labels: Record<CoverageType, string>;
  Sub: "h3" | "h4"; busy: boolean; onRemove: (quoteId: string) => void; onAdd: (input: unknown) => Promise<void> }) {
  const { m: { money: { common: c, insuranceQuotes: t } }, fill } = useMessages();
  const heading = useId();
  const results = view.results;
  const request = results?.request;
  const differentCurrent = results?.sameCoverage.some(quote => quote.yearlyDifference?.currentCoverage === "DIFFERENT_FROM_CHOSEN");
  const notEnteredCurrent = results?.sameCoverage.some(quote => quote.yearlyDifference?.currentCoverage === "NOT_ENTERED");
  return <section className="insurance-results" aria-labelledby={heading}>
    <Sub id={heading}>{t.yourQuotes}</Sub>
    <p className="insurance-money">{c.companiesMayPay}{" "}
      <a href={howOtdogMakesMoneyLink}>{c.moneyLink}</a></p>
    {!results && <p className="muted">{t.noQuotes}</p>}
    {request && <p className="muted">{request.partner === "SYNTHETIC_INSURANCE_PARTNER"
      ? fill(c.requestedSynthetic, { date: day(request.requestedAt) })
      : fill(c.requested, { date: day(request.requestedAt), partner: request.partner })}</p>}
    {request?.notice && <p className="notice">{request.notice}</p>}
    {request?.outdated && <p role="alert">{t.outdated}</p>}
    {results && <>
      <p>{t.sameCoverageIntro}</p>
      {results.sameCoverage.length === 0 ? <p className="muted">{t.noSameCoverage}</p>
        : <ol className="insurance-quote-list" aria-label={t.sameCoverageList}>
          {results.sameCoverage.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} />)}
        </ol>}
      {differentCurrent && <p className="muted">{t.currentDifferent}</p>}
      {notEnteredCurrent && <p className="muted">{t.currentNotEntered}</p>}
      {results.differentCoverage.length > 0 && <>
        <p>{t.differentCoverageIntro}</p>
        <ul className="insurance-quote-list" aria-label={t.differentCoverageList}>
          {results.differentCoverage.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} />)}
        </ul>
      </>}
      {results.withoutQuote.length > 0 && <>
        <p>{t.withoutQuoteIntro}</p>
        <ul className="insurance-outcomes" aria-label={t.withoutQuoteList}>
          {results.withoutQuote.map(entry => <li key={entry.insurer}><strong>{entry.insurer}</strong>: {t.outcomes[entry.outcome]}</li>)}
        </ul>
      </>}
      {results.expired.length > 0 && <>
        <p>{t.expiredIntro}</p>
        <ul className="insurance-quote-list" aria-label={t.expiredList}>
          {results.expired.map(quote => <QuoteItem key={quote.id} quote={quote} labels={labels} busy={busy} onRemove={onRemove} expired />)}
        </ul>
      </>}
      <p className="muted">{t.noObligation}</p>
    </>}
    <OwnQuoteForm view={view} busy={busy} onAdd={onAdd} />
  </section>;
}

function QuoteItem({ quote, labels, busy, onRemove, expired = false }: { quote: InsuranceQuote; labels: Record<CoverageType, string>;
  busy: boolean; onRemove: (quoteId: string) => void; expired?: boolean }) {
  const { m: { money: { common: c, insuranceQuotes: t } }, fill } = useMessages();
  return <li className="insurance-quote">
    <p className="insurance-quote-insurer"><strong>{quote.insurer}</strong>
      {quote.source === "BUYER_ENTERED" && <span className="insurance-tag">{c.enteredByYou}</span>}</p>
    <p className="insurance-quote-price">{fill(t.premiumForTerm, { premium: money(quote.premiumMinor), term: t.terms[quote.term] })}
      {quote.term === "SIX_MONTHS" && <span className="muted"> {fill(t.yearlyInParentheses, { amount: money(quote.yearlyPremiumMinor) })}</span>}</p>
    {quote.yearlyDifference && <p>{yearlyDifferenceText(quote.yearlyDifference, t)}</p>}
    {quote.differences.length > 0 && <ul className="insurance-differences" aria-label={fill(t.coverageDiffers, { insurer: quote.insurer })}>
      {quote.differences.map((difference, index) => <li key={index}>{differenceText(difference, labels[difference.type], t)}</li>)}
    </ul>}
    <p className="muted">{quote.expiresAt ? fill(expired ? c.expiredDate : c.goodUntilDate, { date: day(quote.expiresAt) }) : c.noEndDate}</p>
    {quote.continueUrl && !expired && <a className="insurance-continue" href={quote.continueUrl} target="_blank" rel="noopener noreferrer">
      {fill(c.continueOnSite, { name: quote.insurer })}</a>}
    {quote.source === "BUYER_ENTERED" && <button type="button" className="btn-secondary" disabled={busy}
      onClick={() => onRemove(quote.id)}>{fill(t.removeQuote, { insurer: quote.insurer })}</button>}
  </li>;
}

function OwnQuoteForm({ view, busy, onAdd }: { view: InsuranceView; busy: boolean; onAdd: (input: unknown) => Promise<void> }) {
  const { m: { money: { common: c, insuranceQuotes: t } } } = useMessages();
  const prefix = useId();
  const [open, setOpen] = useState(false);
  const [same, setSame] = useState(true);
  const [lines, setLines] = useState<CoverageLine[]>(view.application?.coverage ?? []);
  const [problem, setProblem] = useState<Problem>();
  const { described, errorFor, describedBy } = useFieldErrors(prefix, problem);
  if (!open) return <button type="button" className="btn-secondary" onClick={() => setOpen(true)}>{t.addOwn}</button>;
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
  return <form className="insurance-form insurance-own" onSubmit={event => void submit(event)} noValidate aria-label={t.ownFormLabel}>
    <p className="muted">{t.ownIntro}</p>
    <label>{t.insurer}<input name="insurer" autoComplete="off" maxLength={100} {...described("insurer")} /></label>
    {errorFor("insurer")}
    <label>{t.premiumWholeTerm}<input name="premium" inputMode="decimal" autoComplete="off" {...described("premiumMinor")} /></label>
    {errorFor("premiumMinor")}
    <fieldset className="insurance-choices" aria-describedby={describedBy("term")}>
      <legend>{t.policyTerm}</legend>
      {TERMS.map(option => <label key={option}><input type="radio" name="term" value={option} /><span>{t.terms[option]}</span></label>)}
    </fieldset>
    {errorFor("term")}
    <fieldset className="insurance-choices">
      <legend>{t.sameCoverageQuestion}</legend>
      <label><input type="radio" name="sameCoverage" checked={same} onChange={() => setSame(true)} /><span>{t.sameCoverageYes}</span></label>
      <label><input type="radio" name="sameCoverage" checked={!same} onChange={() => setSame(false)} /><span>{t.sameCoverageNo}</span></label>
    </fieldset>
    {!same && <CoverageEditor legend={t.quoteCoverage} guide={view.coverageGuide} lines={lines} onChange={setLines} field="coverage"
      errors={{ errorFor, describedBy }} />}
    <label>{t.quoteDateOptional}<input name="quotedOn" type="date" {...described("quotedOn")} /></label>
    {errorFor("quotedOn")}
    <label>{c.goodUntilOptional}<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
    {errorFor("expiresOn")}
    {problem && <p role="alert">{problem.message}</p>}
    <div className="insurance-actions">
      <button type="submit" disabled={busy}>{t.addQuote}</button>
      <button type="button" className="btn-secondary" onClick={() => { setOpen(false); setProblem(undefined); }}>{c.cancel}</button>
    </div>
  </form>;
}
