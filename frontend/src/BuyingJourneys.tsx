import { FormEvent, ReactNode, useEffect, useRef, useState } from "react";
import { JourneyCandidates, JourneyCaseCredentials } from "./JourneyCandidates";
import { QuoteComparison } from "./QuoteComparison";
import { InsuranceQuotes } from "./InsuranceQuotes";
import { LoanPreApprovals } from "./LoanPreApprovals";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

type Option = { id: string; label: string };
type Model = Option & { trims: Option[] };
type Make = Option & { models: Model[] };
type Bounds = { earliestYear: number; latestYear: number; maxMileage: number; minPriceCeiling: number;
  maxPriceCeiling: number; minRadiusMiles: number; maxRadiusMiles: number; maxChoices: number;
  maxReferenceVehicles?: number };
export type BriefOptions = { reference: { version: string; makes: Make[]; features: Option[] }; bounds: Bounds };
export type ShoppingBrief = {
  journeyId: string; version: number; referenceListVersion: string; yearFrom: number; yearTo: number; make: Option;
  model: Option; trims: Option[]; maxMileage: number; priceCeilingMinor: number; currency: string; postalCode: string;
  jurisdiction: string; radiusMiles: number; mustHaveFeatures: Option[]; niceToHaveFeatures: Option[]; createdAt: string;
};
type JourneyState = "SHOPPING" | "NEGOTIATING" | "AGREED" | "TEST_DRIVE_SCHEDULED" | "CLOSED" | "ABANDONED";
export type ReferenceAttribute = { attribute: string; label: string; value: string; source: string; decoderVersion: string;
  relevant: boolean };
export type ReferenceVehicle = { referenceId: string; vin: string; outcome: "DECODED" | "UNDECODABLE"; source: string;
  decoderVersion: string; decodedAt: string; attributes: ReferenceAttribute[] };
export type Journey = { id: string; state: JourneyState; createdAt: string; updatedAt: string; brief: ShoppingBrief;
  versions: { version: number; createdAt: string }[]; referenceVehicles?: ReferenceVehicle[] };
type JourneySummary = { id: string; state: JourneyState; createdAt: string; updatedAt: string; currentVersion: number;
  yearFrom: number; yearTo: number; make: string; model: string };
type FieldError = { field: string; message: string; reason?: string | null; candidateJurisdictions?: string[] };
type Problem = { status: number; code?: string; detail?: string; errors?: FieldError[] };
type BriefInput = { yearFrom: number | null; yearTo: number | null; makeId: string; modelId: string; trimIds: string[];
  maxMileage: number | null; priceCeiling: number | null; postalCode: string; jurisdiction: string | null;
  radiusMiles: number | null; mustHaveFeatures: string[]; niceToHaveFeatures: string[] };

type JourneyText = Messages["content"]["buyingJourneys"];
type Fill = ReturnType<typeof useMessages>["fill"];

const stateName = (t: JourneyText, code: string) => (t.stateNames as Record<string, string>)[code] ?? code.replace(/^US-/, "");
const dollars = (minor: number) => format.money(minor, { maximumFractionDigits: 0 });
const day = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : format.date(date, { month: "short", day: "numeric", year: "numeric" });
};
const title = (t: JourneyText, fill: Fill, from: number, to: number, make: string, model: string) =>
  fill(t.vehicleTitle, { years: from === to ? String(from) : fill(t.yearRange, { from: String(from), to: String(to) }), make, model });

async function call<T>(path: string, init: RequestInit & { key?: string } = {}): Promise<T> {
  const { key, ...rest } = init;
  const response = await fetch(path, { ...rest, headers: {
    ...(["POST", "PUT", "DELETE"].includes(rest.method ?? "GET") ? { "Account-Request": "1" } : {}),
    ...(rest.body ? { "Content-Type": "application/json" } : {}),
    ...(key ? { "Idempotency-Key": key } : {}),
  } });
  const text = await response.text();
  let body: unknown;
  try { body = text ? JSON.parse(text) : undefined; } catch { body = undefined; }
  if (!response.ok) throw { ...(body as object), status: response.status } as Problem;
  return body as T;
}

type View = { mode: "list" } | { mode: "new" } | { mode: "open"; journey: Journey; shown: ShoppingBrief }
  | { mode: "edit"; journey: Journey };

/** A signed-in Buyer's Buying Journeys: each starts with a Shopping Brief, and every saved edit is a new version. */
export function BuyingJourneys({ onSessionEnded, onCaseOpened }: { onSessionEnded: () => void;
  onCaseOpened?: (value: JourneyCaseCredentials, disclosure?: string) => void }) {
  const { m: { content: { buyingJourneys: t } }, fill } = useMessages();
  const [options, setOptions] = useState<BriefOptions>();
  const [journeys, setJourneys] = useState<JourneySummary[]>();
  const [view, setView] = useState<View>({ mode: "list" });
  const [problem, setProblem] = useState<Problem>();
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loads, setLoads] = useState(0);
  const createKey = useRef(crypto.randomUUID());
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    let current = true;
    Promise.all([call<BriefOptions>("/api/v1/journeys/brief-options"), call<JourneySummary[]>("/api/v1/journeys")])
      .then(([loadedOptions, loadedJourneys]) => {
        if (!current) return;
        if (!Array.isArray(loadedJourneys) || !loadedOptions?.reference) throw { status: 0 };
        setOptions(loadedOptions); setJourneys(loadedJourneys); setError("");
      })
      .catch((failure: Problem) => {
        if (!current) return;
        if (failure?.status === 401) onSessionEnded(); else setError(t.loadFailed);
      });
    return () => { current = false; };
  }, [loads]);

  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true); setError(""); setMessage(""); setProblem(undefined);
    try { await action(); }
    catch (thrown) {
      const outcome = thrown as Problem;
      if (outcome?.status === 401) onSessionEnded();
      else if (outcome?.errors?.length) setProblem(outcome);
      else setError(outcome?.detail ?? failure);
    } finally { setBusy(false); }
  }
  function show(next: View, notice = "") {
    setView(next); setProblem(undefined); setError(""); setMessage(notice);
    window.setTimeout(() => heading.current?.focus(), 0);
  }
  const refreshList = async () => setJourneys(await call<JourneySummary[]>("/api/v1/journeys"));

  let body;
  if (!options || !journeys) {
    body = error ? <button className="btn-secondary" onClick={() => setLoads(loads + 1)}>{t.retryLoading}</button>
      : <p role="status">{t.loading}</p>;
  } else if (view.mode === "new" || view.mode === "edit") {
    const journey = view.mode === "edit" ? view.journey : undefined;
    body = <BriefForm key={journey ? `${journey.id}-${journey.brief.version}` : "new"} options={options}
      initial={journey?.brief} busy={busy} problem={problem}
      submitLabel={journey ? t.saveAsNewVersion : t.saveAndStart}
      onCancel={() => show(journey ? { mode: "open", journey, shown: journey.brief } : { mode: "list" })}
      onSubmit={input => run(async () => {
        if (!journey) {
          const created = await call<Journey>("/api/v1/journeys", { method: "POST", key: createKey.current, body: JSON.stringify(input) });
          createKey.current = crypto.randomUUID();
          await refreshList();
          show({ mode: "open", journey: created, shown: created.brief }, t.started);
          return;
        }
        try {
          const saved = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(journey.id)}/briefs`, {
            method: "POST", body: JSON.stringify({ basedOnVersion: journey.brief.version, brief: input }) });
          await refreshList();
          show({ mode: "open", journey: saved, shown: saved.brief }, saved.brief.version === journey.brief.version
            ? t.nothingChanged
            : fill(t.savedVersion, { version: String(saved.brief.version) }));
        } catch (thrown) {
          if ((thrown as Problem)?.status !== 409) throw thrown;
          const latest = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(journey.id)}`);
          show({ mode: "open", journey: latest, shown: latest.brief });
          setError((thrown as Problem).detail ?? t.changedSince);
        }
      }, t.saveFailed)} />;
  } else if (view.mode === "open") {
    const { journey, shown } = view;
    body = <JourneyDetail journey={journey} shown={shown} busy={busy}
      references={<ReferenceVehicles key={journey.id} journey={journey} shownVersion={shown.version}
        maxVehicles={options.bounds.maxReferenceVehicles ?? 5} onSessionEnded={onSessionEnded}
        onSaved={async saved => {
          setView({ mode: "open", journey: saved, shown: saved.brief });
          await refreshList().catch(() => undefined);
        }} />}
      candidates={<><JourneyCandidates key={`${journey.id}-${journey.brief.version}`} journeyId={journey.id}
        briefVersion={journey.brief.version} postalCode={journey.brief.postalCode} onSessionEnded={onSessionEnded}
        onCaseOpened={onCaseOpened} request={(path, init) => call(path, init)} />
        <QuoteComparison key={journey.id} journeyId={journey.id} onSessionEnded={onSessionEnded} /></>}
      insurance={<InsuranceQuotes key={`insurance-${journey.id}`} journeyId={journey.id} onSessionEnded={onSessionEnded} level={3} />}
      loans={<LoanPreApprovals key={`loan-${journey.id}`} journeyId={journey.id} onSessionEnded={onSessionEnded} level={3} />}
      onBack={() => show({ mode: "list" })}
      onEdit={() => show({ mode: "edit", journey })}
      onVersion={version => run(async () => {
        const brief = version === journey.brief.version ? journey.brief
          : await call<ShoppingBrief>(`/api/v1/journeys/${encodeURIComponent(journey.id)}/briefs/${version}`);
        setView({ mode: "open", journey, shown: brief });
      }, t.versionFailed)}
      onDelete={() => run(async () => {
        await call(`/api/v1/journeys/${encodeURIComponent(journey.id)}`, { method: "DELETE" });
        await refreshList();
        show({ mode: "list" }, t.deleted);
      }, t.deleteFailed)} />;
  } else {
    body = <>
      <p>{t.intro}</p>
      {journeys.length === 0 ? <p className="muted">{t.none}</p>
        : <ul className="journey-list" aria-label={t.listLabel}>
          {journeys.map(j => <li key={j.id}>
            <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
              const journey = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(j.id)}`);
              show({ mode: "open", journey, shown: journey.brief });
            }, t.openFailed)}>
              {fill(t.open, { title: title(t, fill, j.yearFrom, j.yearTo, j.make, j.model) })}
            </button>
            <span className="muted">{day(j.createdAt)
              ? fill(t.summaryStarted, { state: t.states[j.state], version: String(j.currentVersion), date: day(j.createdAt) })
              : fill(t.summary, { state: t.states[j.state], version: String(j.currentVersion) })}</span>
          </li>)}
        </ul>}
      <button disabled={busy} onClick={() => show({ mode: "new" })}>{t.start}</button>
    </>;
  }

  return <section className="panel journeys" aria-labelledby="journeys-heading">
    <h2 id="journeys-heading" ref={heading} tabIndex={-1}>{t.heading}</h2>
    {body}
    {message && <p role="status">{message}</p>}
    {error && <p role="alert">{error}</p>}
  </section>;
}

function JourneyDetail({ journey, shown, busy, references, candidates, insurance, loans, onBack, onEdit, onVersion, onDelete }: {
  journey: Journey; shown: ShoppingBrief; busy: boolean; references: ReactNode; candidates: ReactNode; insurance?: ReactNode;
  loans?: ReactNode;
  onBack: () => void; onEdit: () => void;
  onVersion: (version: number) => void; onDelete: () => void;
}) {
  const { m: { content: { buyingJourneys: journeyText } }, fill, fillNodes } = useMessages();
  const t = journeyText.detail;
  const [confirmed, setConfirmed] = useState(false);
  const current = shown.version === journey.brief.version;
  const list = (values: Option[], none: string) => values.length ? values.map(v => v.label).join(", ") : none;
  return <>
    <h3>{title(journeyText, fill, shown.yearFrom, shown.yearTo, shown.make.label, shown.model.label)}</h3>
    <p>{fillNodes(t.state, { state: <strong>{journeyText.states[journey.state]}</strong> })}</p>
    <label>{t.versionLabel}
      <select value={shown.version} disabled={busy} onChange={event => onVersion(Number(event.target.value))}>
        {journey.versions.map(v => <option key={v.version} value={v.version}>
          {versionName(t, fill, v.version, v.version === journey.brief.version, day(v.createdAt))}
        </option>)}
      </select>
    </label>
    {!current && <p className="notice">{t.earlier}</p>}
    <dl className="facts" aria-label={fill(t.detailsLabel, { version: String(shown.version) })}>
      <div><dt>{t.modelYears}</dt><dd>{shown.yearFrom === shown.yearTo ? shown.yearFrom
        : fill(t.yearsRange, { from: String(shown.yearFrom), to: String(shown.yearTo) })}</dd></div>
      <div><dt>{t.makeAndModel}</dt><dd>{fill(t.makeAndModelValue, { make: shown.make.label, model: shown.model.label })}</dd></div>
      <div><dt>{t.trims}</dt><dd>{list(shown.trims, t.anyTrim)}</dd></div>
      <div><dt>{t.maxMileage}</dt><dd>{fill(t.miles, { miles: format.number(shown.maxMileage) })}</dd></div>
      <div><dt>{t.priceCeiling}</dt><dd>{dollars(shown.priceCeilingMinor)}</dd></div>
      <div><dt>{t.searchArea}</dt><dd>{fill(t.searchAreaValue, { radius: String(shown.radiusMiles), postalCode: shown.postalCode,
        state: stateName(journeyText, shown.jurisdiction) })}</dd></div>
      <div><dt>{t.mustHave}</dt><dd>{list(shown.mustHaveFeatures, t.none)}</dd></div>
      <div><dt>{t.niceToHave}</dt><dd>{list(shown.niceToHaveFeatures, t.none)}</dd></div>
    </dl>
    <p className="muted">{fill(t.referenceList, { version: shown.referenceListVersion })}</p>
    <div className="actions">
      <button disabled={busy} onClick={onEdit}>{t.edit}</button>
      <button className="btn-ghost" disabled={busy} onClick={onBack}>{t.back}</button>
    </div>
    {references}
    {current ? candidates : <p className="muted">{t.currentOnly}</p>}
    {insurance}
    {loans}
    <h3>{t.deleteHeading}</h3>
    <p>{t.deleteText}</p>
    <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{t.deleteConfirm}</label>
    <button className="btn-danger" disabled={busy || !confirmed} onClick={onDelete}>{t.delete}</button>
  </>;
}

/** A brief version as the version picker names it. */
function versionName(t: JourneyText["detail"], fill: Fill, version: number, current: boolean, saved: string) {
  const values = { version: String(version) };
  if (saved) return fill(current ? t.versionCurrentSaved : t.versionSaved, { ...values, date: saved });
  return fill(current ? t.versionCurrent : t.version, values);
}

const vehicleName = (t: JourneyText["referenceCard"], fill: Fill, vehicle: ReferenceVehicle, position: number) => {
  const value = (attribute: string) => vehicle.attributes.find(a => a.attribute === attribute)?.value;
  const named = ["MODEL_YEAR", "MAKE", "MODEL", "TRIM"].map(value).filter(Boolean).join(" ");
  return named ? fill(t.namedName, { position: String(position), named }) : fill(t.name, { position: String(position) });
};

/**
 * VINs of cars the Buyer likes. Each is checksum-checked and decoded by the server; the Buyer marks
 * which decoded details matter, and only those refine matching. Every change saves a new brief version.
 */
function ReferenceVehicles({ journey, shownVersion, maxVehicles, onSaved, onSessionEnded }: {
  journey: Journey; shownVersion: number; maxVehicles: number;
  onSaved: (journey: Journey) => Promise<void>; onSessionEnded: () => void;
}) {
  const { m: { content: { buyingJourneys: { references: t } } }, fill } = useMessages();
  const current = shownVersion === journey.brief.version;
  const [earlier, setEarlier] = useState<ReferenceVehicle[]>();
  const [vin, setVin] = useState("");
  const [vinError, setVinError] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const vinInput = useRef<HTMLInputElement>(null);
  const base = `/api/v1/journeys/${encodeURIComponent(journey.id)}`;

  useEffect(() => {
    setEarlier(undefined); setError("");
    if (current) return;
    let live = true;
    call<ReferenceVehicle[]>(`${base}/briefs/${shownVersion}/reference-vehicles`)
      .then(loaded => { if (live) setEarlier(Array.isArray(loaded) ? loaded : []); })
      .catch((failure: Problem) => {
        if (!live) return;
        if (failure?.status === 401) onSessionEnded();
        else setError(t.loadFailed);
      });
    return () => { live = false; };
  }, [current, shownVersion, journey.id]);

  const vehicles = current ? journey.referenceVehicles ?? [] : earlier;
  const full = (vehicles?.length ?? 0) >= maxVehicles;

  async function save(action: () => Promise<Journey>, notice: (saved: Journey) => string, failure: string) {
    setBusy(true); setError(""); setMessage(""); setVinError("");
    try {
      const saved = await action();
      await onSaved(saved);
      setMessage(notice(saved));
      return true;
    } catch (thrown) {
      const outcome = thrown as Problem;
      const vinProblem = outcome?.errors?.find(e => e.field === "vin");
      if (outcome?.status === 401) onSessionEnded();
      else if (vinProblem) {
        setVinError(vinProblem.message);
        window.setTimeout(() => vinInput.current?.focus(), 0);
      } else if (outcome?.status === 409) {
        const latest = await call<Journey>(base).catch(() => undefined);
        if (latest) await onSaved(latest);
        setError(outcome.detail ?? t.changedSince);
      } else setError(outcome?.detail ?? failure);
      return false;
    } finally { setBusy(false); }
  }

  async function add(event: FormEvent) {
    event.preventDefault();
    const added = await save(() => call<Journey>(`${base}/reference-vehicles`, { method: "POST",
      body: JSON.stringify({ basedOnVersion: journey.brief.version, vin: vin.trim() }) }), saved => {
      if (saved.brief.version === journey.brief.version) return t.alreadyThere;
      const last = saved.referenceVehicles?.at(-1);
      return fill(last?.outcome === "UNDECODABLE" ? t.addedUndecodable : t.added, { version: String(saved.brief.version) });
    }, t.addFailed);
    if (added) setVin("");
  }

  return <section className="reference-vehicles" aria-labelledby="reference-vehicles-heading">
    <h3 id="reference-vehicles-heading">{t.heading}</h3>
    <p>{fill(t.intro, { max: String(maxVehicles) })}</p>
    {!current && <p className="muted">{fill(t.readOnly, { version: String(shownVersion) })}</p>}
    {vehicles === undefined ? !error && <p role="status">{t.loading}</p>
      : vehicles.length === 0 ? <p className="muted">{t.none}</p>
      : <ol className="reference-list" aria-label={fill(t.listLabel, { version: String(shownVersion) })}>
        {vehicles.map((vehicle, index) => <li key={`${vehicle.referenceId}-${shownVersion}`}>
          <ReferenceVehicleCard vehicle={vehicle} position={index + 1} editable={current} busy={busy}
            onRelevance={relevant => save(() => call<Journey>(
              `${base}/reference-vehicles/${encodeURIComponent(vehicle.referenceId)}/relevance`, { method: "PUT",
                body: JSON.stringify({ basedOnVersion: journey.brief.version, relevantAttributes: relevant }) }),
              saved => saved.brief.version === journey.brief.version ? t.nothingChanged
                : fill(t.savedRelevance, { version: String(saved.brief.version) }),
              t.relevanceFailed)}
            onRemove={() => save(() => call<Journey>(`${base}/reference-vehicles/${encodeURIComponent(vehicle.referenceId)}`
              + `?basedOnVersion=${journey.brief.version}`, { method: "DELETE" }),
              saved => fill(t.removed, { version: String(saved.brief.version) }),
              t.removeFailed)} />
        </li>)}
      </ol>}
    {current && (full
      ? <p className="notice">{fill(t.full, { max: String(maxVehicles) })}</p>
      : <form className="reference-add" aria-label={t.addLabel} noValidate onSubmit={add}>
        <label>{t.vinLabel}
          <input ref={vinInput} value={vin} maxLength={17} autoComplete="off" spellCheck={false} autoCapitalize="characters"
            aria-describedby={vinError ? "reference-vin-help reference-vin-error" : "reference-vin-help"}
            {...(vinError ? { "aria-invalid": true } : {})} onChange={event => { setVin(event.target.value); setVinError(""); }} />
        </label>
        <p className="muted" id="reference-vin-help">{fill(t.vinHelp, { added: String(vehicles?.length ?? 0), max: String(maxVehicles) })}</p>
        {vinError && <p className="error field-error" id="reference-vin-error">{vinError}</p>}
        <button disabled={busy || vin.trim() === ""}>{t.addButton}</button>
      </form>)}
    {message && <p role="status">{message}</p>}
    {error && <p role="alert">{error}</p>}
  </section>;
}

function ReferenceVehicleCard({ vehicle, position, editable, busy, onRelevance, onRemove }: {
  vehicle: ReferenceVehicle; position: number; editable: boolean; busy: boolean;
  onRelevance: (relevant: string[]) => void; onRemove: () => void;
}) {
  const { m: { content: { buyingJourneys: { referenceCard: t } } }, fill, fillNodes } = useMessages();
  const marked = vehicle.attributes.filter(a => a.relevant).map(a => a.attribute);
  const [relevant, setRelevant] = useState<string[]>(marked);
  const changed = relevant.length !== marked.length || relevant.some(a => !marked.includes(a));
  const name = vehicleName(t, fill, vehicle, position);
  const provenance = (attribute: ReferenceAttribute) => fill(t.provenance, { source: attribute.source, version: attribute.decoderVersion });
  const at = { position: String(position) };
  return <article className="reference-vehicle" aria-label={name}>
    <h4>{name}</h4>
    <p className="muted">{day(vehicle.decodedAt)
      ? fillNodes(t.vinDecoded, { vin: <span className="vin">{vehicle.vin}</span>, date: day(vehicle.decodedAt) })
      : fillNodes(t.vin, { vin: <span className="vin">{vehicle.vin}</span> })}</p>
    {vehicle.outcome === "UNDECODABLE" || vehicle.attributes.length === 0
      ? <p className="notice">{fill(t.undecodable, { source: vehicle.source })}</p>
      : editable
        ? <fieldset className="journey-choices reference-attributes">
          <legend>{fill(t.legend, at)}</legend>
          {vehicle.attributes.map(a => <label key={a.attribute}>
            <input type="checkbox" checked={relevant.includes(a.attribute)} disabled={busy}
              onChange={event => setRelevant(event.target.checked ? [...relevant, a.attribute] : relevant.filter(v => v !== a.attribute))} />
            <span><strong>{fill(t.attributeLabel, { label: a.label })}</strong> {a.value} <span className="muted reference-source">{provenance(a)}</span></span>
          </label>)}
        </fieldset>
        : <dl className="facts" aria-label={fill(t.detailsLabel, at)}>
          {vehicle.attributes.map(a => <div key={a.attribute}>
            <dt>{a.label}</dt>
            <dd>{a.value} · {a.relevant ? t.matters : t.doesNotMatter} <span className="muted reference-source">{provenance(a)}</span></dd>
          </div>)}
        </dl>}
    {editable && <div className="actions">
      {vehicle.outcome === "DECODED" && vehicle.attributes.length > 0 &&
        <button className="btn-secondary" disabled={busy || !changed} onClick={() => onRelevance(relevant)}>
          {fill(t.saveRelevance, at)}</button>}
      <button className="btn-ghost" disabled={busy} onClick={onRemove}>{fill(t.remove, at)}</button>
    </div>}
  </article>;
}

const whole = (value: string) => {
  if (value.trim() === "") return null;
  const number = Number(value);
  return Number.isInteger(number) ? number : null;
};

function BriefForm({ options, initial, busy, problem, submitLabel, onCancel, onSubmit }: {
  options: BriefOptions; initial?: ShoppingBrief; busy: boolean; problem?: Problem; submitLabel: string;
  onCancel: () => void; onSubmit: (input: BriefInput) => void;
}) {
  const { m: { content: { buyingJourneys: journeyText } }, fill } = useMessages();
  const t = journeyText.briefForm;
  const { makes, features } = options.reference;
  const bounds = options.bounds;
  const [yearFrom, setYearFrom] = useState(initial ? String(initial.yearFrom) : "");
  const [yearTo, setYearTo] = useState(initial ? String(initial.yearTo) : "");
  const [makeId, setMakeId] = useState(initial?.make.id ?? "");
  const [modelId, setModelId] = useState(initial?.model.id ?? "");
  const [trimIds, setTrimIds] = useState<string[]>(initial?.trims.map(t => t.id) ?? []);
  const [maxMileage, setMaxMileage] = useState(initial ? String(initial.maxMileage) : "");
  const [priceCeiling, setPriceCeiling] = useState(initial ? String(Math.round(initial.priceCeilingMinor / 100)) : "");
  const [postalCode, setPostalCode] = useState(initial?.postalCode ?? "");
  const [jurisdiction, setJurisdiction] = useState(initial?.jurisdiction ?? "");
  const [radiusMiles, setRadiusMiles] = useState(initial ? String(initial.radiusMiles) : "");
  const [must, setMust] = useState<string[]>(initial?.mustHaveFeatures.map(f => f.id) ?? []);
  const [nice, setNice] = useState<string[]>(initial?.niceToHaveFeatures.map(f => f.id) ?? []);
  const [lookup, setLookup] = useState("");
  const summary = useRef<HTMLParagraphElement>(null);

  const make = makes.find(m => m.id === makeId);
  const model = make?.models.find(m => m.id === modelId);
  const errors = problem?.errors ?? [];
  const errorFor = (field: string) => errors.find(e => e.field === field);
  const crossing = errors.find(e => e.reason === "POSTAL_CODE_CROSSES_STATE_LINE")?.candidateJurisdictions;
  const [crossingStates, setCrossingStates] = useState<string[]>([]);
  useEffect(() => { if (crossing?.length) setCrossingStates(crossing); }, [problem]);
  useEffect(() => { if (problem) summary.current?.focus(); }, [problem]);

  const terms = lookup.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const matches = terms.length === 0 ? [] : makes.flatMap(m => m.models.map(mo => ({ make: m, model: mo })))
    .filter(({ make: m, model: mo }) => terms.every(term => `${m.label} ${mo.label}`.toLowerCase().includes(term))).slice(0, 6);

  const field = (name: string) => errorFor(name) ? { "aria-invalid": true, "aria-describedby": `brief-${name}-error` } : {};
  // A group has no invalid state of its own; its error is announced as its description.
  const group = (name: string) => errorFor(name) ? { "aria-describedby": `brief-${name}-error` } : {};
  const fieldError = (name: string) => {
    const problemHere = errorFor(name);
    return problemHere && <p className="error field-error" id={`brief-${name}-error`}>{problemHere.message}</p>;
  };
  const toggle = (values: string[], id: string, checked: boolean) => checked ? [...values, id] : values.filter(v => v !== id);

  function submit(event: FormEvent) {
    event.preventDefault();
    onSubmit({
      yearFrom: whole(yearFrom), yearTo: whole(yearTo), makeId, modelId, trimIds, maxMileage: whole(maxMileage),
      priceCeiling: whole(priceCeiling), postalCode: postalCode.trim(), jurisdiction: crossingStates.length ? jurisdiction || null : null,
      radiusMiles: whole(radiusMiles), mustHaveFeatures: must, niceToHaveFeatures: nice,
    });
  }

  return <form className="brief-form" aria-label={t.label} noValidate onSubmit={submit}>
    <h3>{initial ? fill(t.editHeading, { version: String(initial.version) }) : t.newHeading}</h3>
    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.detail ?? t.needsCorrection}</p>}
    <label>{t.lookup}
      <input type="search" value={lookup} maxLength={60} autoComplete="off" aria-describedby="brief-lookup-help"
        onChange={event => setLookup(event.target.value)} />
    </label>
    <p className="muted" id="brief-lookup-help">{t.lookupHelp}</p>
    {matches.length > 0 && <ul className="journey-matches" aria-label={t.matchesLabel}>
      {matches.map(({ make: m, model: mo }) => <li key={mo.id}>
        <button type="button" className="btn-secondary" onClick={() => {
          setMakeId(m.id); setModelId(mo.id); setTrimIds([]); setLookup("");
        }}>{fill(t.choose, { make: m.label, model: mo.label })}</button>
      </li>)}
    </ul>}
    {terms.length > 0 && matches.length === 0 && <p className="muted">{t.noMatch}</p>}

    <label>{t.make}
      <select value={makeId} {...field("makeId")} onChange={event => { setMakeId(event.target.value); setModelId(""); setTrimIds([]); }}>
        <option value="">{t.chooseMake}</option>
        {makes.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
      </select>
    </label>
    {fieldError("makeId")}
    <label>{t.model}
      <select value={modelId} disabled={!make} {...field("modelId")} onChange={event => { setModelId(event.target.value); setTrimIds([]); }}>
        <option value="">{make ? t.chooseModel : t.chooseMakeFirst}</option>
        {make?.models.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
      </select>
    </label>
    {fieldError("modelId")}
    {model && <fieldset className="journey-choices" {...group("trimIds")}>
      <legend>{t.trims}</legend>
      {model.trims.map(trim => <label key={trim.id}><input type="checkbox" checked={trimIds.includes(trim.id)}
        onChange={event => setTrimIds(toggle(trimIds, trim.id, event.target.checked))} />{trim.label}</label>)}
    </fieldset>}
    {fieldError("trimIds")}

    <label>{t.earliestYear}
      <input inputMode="numeric" value={yearFrom} {...field("yearFrom")} onChange={event => setYearFrom(event.target.value)} />
    </label>
    {fieldError("yearFrom")}
    <label>{t.latestYear}
      <input inputMode="numeric" value={yearTo} {...field("yearTo")} onChange={event => setYearTo(event.target.value)} />
    </label>
    {fieldError("yearTo")}
    <p className="muted">{fill(t.yearBounds, { from: String(bounds.earliestYear), to: String(bounds.latestYear) })}</p>
    <label>{t.maxMileage}
      <input inputMode="numeric" value={maxMileage} {...field("maxMileage")} onChange={event => setMaxMileage(event.target.value)} />
    </label>
    {fieldError("maxMileage")}
    <label>{t.priceCeiling}
      <input inputMode="numeric" value={priceCeiling} {...field("priceCeiling")} onChange={event => setPriceCeiling(event.target.value)} />
    </label>
    {fieldError("priceCeiling")}
    <label>{t.postalCode}
      <input inputMode="numeric" autoComplete="postal-code" maxLength={5} value={postalCode} {...field("postalCode")}
        onChange={event => { setPostalCode(event.target.value); setCrossingStates([]); setJurisdiction(""); }} />
    </label>
    {fieldError("postalCode")}
    {crossingStates.length > 0 && <label>{t.state}
      <select value={jurisdiction} {...field("jurisdiction")} onChange={event => setJurisdiction(event.target.value)}>
        <option value="">{t.chooseState}</option>
        {crossingStates.map(code => <option key={code} value={code}>{stateName(journeyText, code)}</option>)}
      </select>
    </label>}
    {fieldError("jurisdiction")}
    <label>{t.radius}
      <input inputMode="numeric" value={radiusMiles} {...field("radiusMiles")} onChange={event => setRadiusMiles(event.target.value)} />
    </label>
    {fieldError("radiusMiles")}

    <fieldset className="journey-choices" {...group("mustHaveFeatures")}>
      <legend>{t.mustHave}</legend>
      {features.map(f => <label key={f.id}><input type="checkbox" checked={must.includes(f.id)} onChange={event => {
        setMust(toggle(must, f.id, event.target.checked));
        if (event.target.checked) setNice(nice.filter(v => v !== f.id));
      }} />{f.label}</label>)}
    </fieldset>
    {fieldError("mustHaveFeatures")}
    <fieldset className="journey-choices" {...group("niceToHaveFeatures")}>
      <legend>{t.niceToHave}</legend>
      {features.map(f => <label key={f.id}><input type="checkbox" checked={nice.includes(f.id)} onChange={event => {
        setNice(toggle(nice, f.id, event.target.checked));
        if (event.target.checked) setMust(must.filter(v => v !== f.id));
      }} />{f.label}</label>)}
    </fieldset>
    {fieldError("niceToHaveFeatures")}

    <div className="actions">
      <button disabled={busy}>{submitLabel}</button>
      <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>{t.cancel}</button>
    </div>
  </form>;
}
