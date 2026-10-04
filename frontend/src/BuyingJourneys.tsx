import { FormEvent, useEffect, useRef, useState } from "react";

type Option = { id: string; label: string };
type Model = Option & { trims: Option[] };
type Make = Option & { models: Model[] };
type Bounds = { earliestYear: number; latestYear: number; maxMileage: number; minPriceCeiling: number;
  maxPriceCeiling: number; minRadiusMiles: number; maxRadiusMiles: number; maxChoices: number };
export type BriefOptions = { reference: { version: string; makes: Make[]; features: Option[] }; bounds: Bounds };
export type ShoppingBrief = {
  journeyId: string; version: number; referenceListVersion: string; yearFrom: number; yearTo: number; make: Option;
  model: Option; trims: Option[]; maxMileage: number; priceCeilingMinor: number; currency: string; postalCode: string;
  jurisdiction: string; radiusMiles: number; mustHaveFeatures: Option[]; niceToHaveFeatures: Option[]; createdAt: string;
};
type JourneyState = "SHOPPING" | "NEGOTIATING" | "AGREED" | "TEST_DRIVE_SCHEDULED" | "CLOSED" | "ABANDONED";
export type Journey = { id: string; state: JourneyState; createdAt: string; updatedAt: string; brief: ShoppingBrief;
  versions: { version: number; createdAt: string }[] };
type JourneySummary = { id: string; state: JourneyState; createdAt: string; updatedAt: string; currentVersion: number;
  yearFrom: number; yearTo: number; make: string; model: string };
type FieldError = { field: string; message: string; reason?: string | null; candidateJurisdictions?: string[] };
type Problem = { status: number; code?: string; detail?: string; errors?: FieldError[] };
type BriefInput = { yearFrom: number | null; yearTo: number | null; makeId: string; modelId: string; trimIds: string[];
  maxMileage: number | null; priceCeiling: number | null; postalCode: string; jurisdiction: string | null;
  radiusMiles: number | null; mustHaveFeatures: string[]; niceToHaveFeatures: string[] };

const STATE_LABELS: Record<JourneyState, string> = {
  SHOPPING: "Shopping", NEGOTIATING: "Negotiating", AGREED: "Price agreed", TEST_DRIVE_SCHEDULED: "Test drive scheduled",
  CLOSED: "Closed", ABANDONED: "Stopped",
};
const STATE_NAMES: Record<string, string> = { "US-NC": "North Carolina", "US-VA": "Virginia", "US-SC": "South Carolina",
  "US-TN": "Tennessee", "US-GA": "Georgia" };
const stateName = (code: string) => STATE_NAMES[code] ?? code.replace(/^US-/, "");
const dollars = (minor: number) => `$${Math.round(minor / 100).toLocaleString("en-US")}`;
const day = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
};
const title = (from: number, to: number, make: string, model: string) =>
  `${from === to ? from : `${from}–${to}`} ${make} ${model}`;

async function call<T>(path: string, session: string, init: RequestInit & { key?: string } = {}): Promise<T> {
  const { key, ...rest } = init;
  const response = await fetch(path, { ...rest, headers: {
    "Account-Session-Token": session,
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
export function BuyingJourneys({ session, onSessionEnded }: { session: string; onSessionEnded: () => void }) {
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
    Promise.all([call<BriefOptions>("/api/v1/journeys/brief-options", session), call<JourneySummary[]>("/api/v1/journeys", session)])
      .then(([loadedOptions, loadedJourneys]) => {
        if (!current) return;
        if (!Array.isArray(loadedJourneys) || !loadedOptions?.reference) throw { status: 0 };
        setOptions(loadedOptions); setJourneys(loadedJourneys); setError("");
      })
      .catch((failure: Problem) => {
        if (!current) return;
        if (failure?.status === 401) onSessionEnded(); else setError("Your Buying Journeys could not be loaded. Retry safely.");
      });
    return () => { current = false; };
  }, [session, loads]);

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
  const refreshList = async () => setJourneys(await call<JourneySummary[]>("/api/v1/journeys", session));

  let body;
  if (!options || !journeys) {
    body = error ? <button className="btn-secondary" onClick={() => setLoads(loads + 1)}>Retry loading journeys</button>
      : <p role="status">Loading your Buying Journeys…</p>;
  } else if (view.mode === "new" || view.mode === "edit") {
    const journey = view.mode === "edit" ? view.journey : undefined;
    body = <BriefForm key={journey ? `${journey.id}-${journey.brief.version}` : "new"} options={options}
      initial={journey?.brief} busy={busy} problem={problem}
      submitLabel={journey ? "Save as a new brief version" : "Save brief and start journey"}
      onCancel={() => show(journey ? { mode: "open", journey, shown: journey.brief } : { mode: "list" })}
      onSubmit={input => run(async () => {
        if (!journey) {
          const created = await call<Journey>("/api/v1/journeys", session, { method: "POST", key: createKey.current, body: JSON.stringify(input) });
          createKey.current = crypto.randomUUID();
          await refreshList();
          show({ mode: "open", journey: created, shown: created.brief }, "Buying Journey started with brief version 1. No inventory has been searched yet.");
          return;
        }
        try {
          const saved = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(journey.id)}/briefs`, session, {
            method: "POST", body: JSON.stringify({ basedOnVersion: journey.brief.version, brief: input }) });
          await refreshList();
          show({ mode: "open", journey: saved, shown: saved.brief }, saved.brief.version === journey.brief.version
            ? "Nothing changed, so no new version was saved."
            : `Saved as brief version ${saved.brief.version}. Earlier versions remain readable.`);
        } catch (thrown) {
          if ((thrown as Problem)?.status !== 409) throw thrown;
          const latest = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(journey.id)}`, session);
          show({ mode: "open", journey: latest, shown: latest.brief });
          setError((thrown as Problem).detail ?? "This Shopping Brief changed since you opened it. Review the latest version and edit again.");
        }
      }, "The brief could not be saved. Retry safely.")} />;
  } else if (view.mode === "open") {
    const { journey, shown } = view;
    body = <JourneyDetail journey={journey} shown={shown} busy={busy}
      onBack={() => show({ mode: "list" })}
      onEdit={() => show({ mode: "edit", journey })}
      onVersion={version => run(async () => {
        const brief = version === journey.brief.version ? journey.brief
          : await call<ShoppingBrief>(`/api/v1/journeys/${encodeURIComponent(journey.id)}/briefs/${version}`, session);
        setView({ mode: "open", journey, shown: brief });
      }, "That brief version could not be loaded. Retry safely.")}
      onDelete={() => run(async () => {
        await call(`/api/v1/journeys/${encodeURIComponent(journey.id)}`, session, { method: "DELETE" });
        await refreshList();
        show({ mode: "list" }, "Buying Journey deleted with every version of its Shopping Brief.");
      }, "The journey could not be deleted. Retry safely.")} />;
  } else {
    body = <>
      <p>Describe the car you want in a Shopping Brief. Each saved edit becomes a new version and earlier versions stay
        readable. No inventory is searched yet, and nothing here is shared with a dealer.</p>
      {journeys.length === 0 ? <p className="muted">No Buying Journeys yet.</p>
        : <ul className="journey-list" aria-label="Your Buying Journeys">
          {journeys.map(j => <li key={j.id}>
            <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
              const journey = await call<Journey>(`/api/v1/journeys/${encodeURIComponent(j.id)}`, session);
              show({ mode: "open", journey, shown: journey.brief });
            }, "That journey could not be opened. Retry safely.")}>
              Open {title(j.yearFrom, j.yearTo, j.make, j.model)}
            </button>
            <span className="muted">{STATE_LABELS[j.state]} · Brief version {j.currentVersion}{day(j.createdAt) && ` · Started ${day(j.createdAt)}`}</span>
          </li>)}
        </ul>}
      <button disabled={busy} onClick={() => show({ mode: "new" })}>Start a Buying Journey</button>
    </>;
  }

  return <section className="panel journeys" aria-labelledby="journeys-heading">
    <h2 id="journeys-heading" ref={heading} tabIndex={-1}>Buying Journeys</h2>
    {body}
    {message && <p role="status">{message}</p>}
    {error && <p role="alert">{error}</p>}
  </section>;
}

function JourneyDetail({ journey, shown, busy, onBack, onEdit, onVersion, onDelete }: {
  journey: Journey; shown: ShoppingBrief; busy: boolean; onBack: () => void; onEdit: () => void;
  onVersion: (version: number) => void; onDelete: () => void;
}) {
  const [confirmed, setConfirmed] = useState(false);
  const current = shown.version === journey.brief.version;
  const list = (values: Option[], none: string) => values.length ? values.map(v => v.label).join(", ") : none;
  return <>
    <h3>{title(shown.yearFrom, shown.yearTo, shown.make.label, shown.model.label)}</h3>
    <p>Journey state: <strong>{STATE_LABELS[journey.state]}</strong></p>
    <label>Brief version
      <select value={shown.version} disabled={busy} onChange={event => onVersion(Number(event.target.value))}>
        {journey.versions.map(v => <option key={v.version} value={v.version}>
          Version {v.version}{v.version === journey.brief.version ? " (current)" : ""}{day(v.createdAt) && `, saved ${day(v.createdAt)}`}
        </option>)}
      </select>
    </label>
    {!current && <p className="notice">You are reading an earlier version. It cannot be changed; editing starts from the current version.</p>}
    <dl className="facts" aria-label={`Shopping Brief details, version ${shown.version}`}>
      <div><dt>Model years</dt><dd>{shown.yearFrom === shown.yearTo ? shown.yearFrom : `${shown.yearFrom} to ${shown.yearTo}`}</dd></div>
      <div><dt>Make and model</dt><dd>{shown.make.label} {shown.model.label}</dd></div>
      <div><dt>Trims</dt><dd>{list(shown.trims, "Any trim")}</dd></div>
      <div><dt>Maximum mileage</dt><dd>{shown.maxMileage.toLocaleString("en-US")} miles</dd></div>
      <div><dt>Price ceiling</dt><dd>{dollars(shown.priceCeilingMinor)}</dd></div>
      <div><dt>Search area</dt><dd>{shown.radiusMiles} miles from {shown.postalCode} ({stateName(shown.jurisdiction)})</dd></div>
      <div><dt>Must-have features</dt><dd>{list(shown.mustHaveFeatures, "None")}</dd></div>
      <div><dt>Nice-to-have features</dt><dd>{list(shown.niceToHaveFeatures, "None")}</dd></div>
    </dl>
    <p className="muted">Choices from vehicle reference list {shown.referenceListVersion}.</p>
    <div className="actions">
      <button disabled={busy} onClick={onEdit}>Edit brief</button>
      <button className="btn-ghost" disabled={busy} onClick={onBack}>Back to journeys</button>
    </div>
    <h3>Delete this journey</h3>
    <p>Deleting removes this Buying Journey and every version of its Shopping Brief at once. This cannot be undone.</p>
    <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />I want to permanently delete this Buying Journey and every version of its Shopping Brief.</label>
    <button className="btn-danger" disabled={busy || !confirmed} onClick={onDelete}>Delete Buying Journey</button>
  </>;
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

  return <form className="brief-form" aria-label="Shopping Brief" noValidate onSubmit={submit}>
    <h3>{initial ? `Edit Shopping Brief (now version ${initial.version})` : "New Shopping Brief"}</h3>
    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.detail ?? "Some fields need a correction."}</p>}
    <label>Find a make or model (optional)
      <input type="search" value={lookup} maxLength={60} autoComplete="off" aria-describedby="brief-lookup-help"
        onChange={event => setLookup(event.target.value)} />
    </label>
    <p className="muted" id="brief-lookup-help">Typing only helps you find an entry in the list below; what you type is not saved.</p>
    {matches.length > 0 && <ul className="journey-matches" aria-label="Matching makes and models">
      {matches.map(({ make: m, model: mo }) => <li key={mo.id}>
        <button type="button" className="btn-secondary" onClick={() => {
          setMakeId(m.id); setModelId(mo.id); setTrimIds([]); setLookup("");
        }}>Choose {m.label} {mo.label}</button>
      </li>)}
    </ul>}
    {terms.length > 0 && matches.length === 0 && <p className="muted">No make or model in the list matches. Choose from the lists below.</p>}

    <label>Make
      <select value={makeId} {...field("makeId")} onChange={event => { setMakeId(event.target.value); setModelId(""); setTrimIds([]); }}>
        <option value="">Choose a make</option>
        {makes.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
      </select>
    </label>
    {fieldError("makeId")}
    <label>Model
      <select value={modelId} disabled={!make} {...field("modelId")} onChange={event => { setModelId(event.target.value); setTrimIds([]); }}>
        <option value="">{make ? "Choose a model" : "Choose a make first"}</option>
        {make?.models.map(m => <option key={m.id} value={m.id}>{m.label}</option>)}
      </select>
    </label>
    {fieldError("modelId")}
    {model && <fieldset className="journey-choices" {...group("trimIds")}>
      <legend>Trims (choose none to accept any trim)</legend>
      {model.trims.map(t => <label key={t.id}><input type="checkbox" checked={trimIds.includes(t.id)}
        onChange={event => setTrimIds(toggle(trimIds, t.id, event.target.checked))} />{t.label}</label>)}
    </fieldset>}
    {fieldError("trimIds")}

    <label>Earliest model year
      <input inputMode="numeric" value={yearFrom} {...field("yearFrom")} onChange={event => setYearFrom(event.target.value)} />
    </label>
    {fieldError("yearFrom")}
    <label>Latest model year
      <input inputMode="numeric" value={yearTo} {...field("yearTo")} onChange={event => setYearTo(event.target.value)} />
    </label>
    {fieldError("yearTo")}
    <p className="muted">Model years {bounds.earliestYear} to {bounds.latestYear}.</p>
    <label>Maximum mileage (miles)
      <input inputMode="numeric" value={maxMileage} {...field("maxMileage")} onChange={event => setMaxMileage(event.target.value)} />
    </label>
    {fieldError("maxMileage")}
    <label>Price ceiling (whole dollars)
      <input inputMode="numeric" value={priceCeiling} {...field("priceCeiling")} onChange={event => setPriceCeiling(event.target.value)} />
    </label>
    {fieldError("priceCeiling")}
    <label>Your ZIP code
      <input inputMode="numeric" autoComplete="postal-code" maxLength={5} value={postalCode} {...field("postalCode")}
        onChange={event => { setPostalCode(event.target.value); setCrossingStates([]); setJurisdiction(""); }} />
    </label>
    {fieldError("postalCode")}
    {crossingStates.length > 0 && <label>Your state
      <select value={jurisdiction} {...field("jurisdiction")} onChange={event => setJurisdiction(event.target.value)}>
        <option value="">Choose your state</option>
        {crossingStates.map(code => <option key={code} value={code}>{stateName(code)}</option>)}
      </select>
    </label>}
    {fieldError("jurisdiction")}
    <label>Search radius (miles from your ZIP code)
      <input inputMode="numeric" value={radiusMiles} {...field("radiusMiles")} onChange={event => setRadiusMiles(event.target.value)} />
    </label>
    {fieldError("radiusMiles")}

    <fieldset className="journey-choices" {...group("mustHaveFeatures")}>
      <legend>Must-have features</legend>
      {features.map(f => <label key={f.id}><input type="checkbox" checked={must.includes(f.id)} onChange={event => {
        setMust(toggle(must, f.id, event.target.checked));
        if (event.target.checked) setNice(nice.filter(v => v !== f.id));
      }} />{f.label}</label>)}
    </fieldset>
    {fieldError("mustHaveFeatures")}
    <fieldset className="journey-choices" {...group("niceToHaveFeatures")}>
      <legend>Nice-to-have features</legend>
      {features.map(f => <label key={f.id}><input type="checkbox" checked={nice.includes(f.id)} onChange={event => {
        setNice(toggle(nice, f.id, event.target.checked));
        if (event.target.checked) setMust(must.filter(v => v !== f.id));
      }} />{f.label}</label>)}
    </fieldset>
    {fieldError("niceToHaveFeatures")}

    <div className="actions">
      <button disabled={busy}>{submitLabel}</button>
      <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
    </div>
  </form>;
}
