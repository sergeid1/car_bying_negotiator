import { ChangeEvent, FormEvent, RefObject, useContext, useEffect, useRef, useState } from "react";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import type { FreshDeskCase } from "./DeskCheck";
import { toMinor, vehicleName, type Trade, type TradeView } from "./TradeVehicle";
import { TradeValueLinks } from "./TradeValueChecks";
import type { DeskTacticFinding } from "./DeskTactics";

type Credentials = { caseId: string; token: string };
type Route = "AGREED_CAR" | "THIS_CASE";
type TradeAsk = "NONE" | "OWED" | "DETAILS" | "WHETHER";
export type DeskRouting = {
  status: "AWAITING_PAPERWORK" | "READING" | "UNREADABLE" | "READ";
  documentId: string | null; documentType: string | null; vin: string | null;
  route: "AGREED_CAR" | "THIS_CASE" | "FRESH_CASE" | "CHOOSE" | null;
  agreedVin: string | null; caseVin: string | null;
  freshCase: { vin: string; dealerPostalCode: string | null; purchaseMethod: string } | null;
  questions: null | {
    trade: { ask: TradeAsk; paperworkShowsTrade: boolean; recorded: Trade | null };
    financing: { ask: boolean; paperworkShowsLoan: boolean; ownAprPercent: number | null; ownTermMonths: number | null };
  };
};
export type DeskFinding = { code: string; summary: string; amountMinor: number | null; deskQuestion: string; tacticCode?: string | null };
export type FirstResult = {
  route: Route; source: "READ_FROM_PAPERWORK" | "TYPED_BY_BUYER";
  outTheDoor: { amountMinor: number | null; agreedMinor: number | null; differenceMinor: number | null };
  lead: DeskFinding | null; findings: DeskFinding[];
  tacticFindings?: DeskTacticFinding[];
};
type Typed = { vin?: string; sellingPriceMinor?: number; tradeAllowanceMinor?: number; cashDownMinor?: number;
  monthlyPaymentMinor?: number; termMonths?: number; aprPercent?: number; outTheDoorMinor?: number };
type Phase = "start" | "reading" | "route" | "fresh" | "typed" | "questions" | "result";

const PHOTO_TYPES = "image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif";
const CONDITIONS = [
  { value: "EXCELLENT", label: "Excellent" }, { value: "GOOD", label: "Good" },
  { value: "FAIR", label: "Fair" }, { value: "POOR", label: "Poor" }];
/** Marks taken in the browser so upload-to-first-result can be read on a phone through remote debugging. */
export const LATENCY_MARK = "desk-start:photo";
export const LATENCY_MEASURE = "desk-start:first-result";
/** Photo to the paperwork read and routed, which leaves out the time the Buyer spends confirming and answering. */
export const READ_MEASURE = "desk-start:read";

function measure(name: string) {
  try {
    if (!performance.getEntriesByName?.(LATENCY_MARK).length) return undefined;
    performance.clearMeasures?.(name);
    const entry = performance.measure?.(name, LATENCY_MARK);
    return entry ? Math.round(entry.duration) : undefined;
  } catch { return undefined; } // Timing is a measurement aid only.
}

/** Scrolls an element to the top of the screen, clear of the sticky masthead, whose height wraps at narrow widths. */
function reveal(element: HTMLElement | null) {
  if (!element) return;
  const bar = document.querySelector(".masthead")?.getBoundingClientRect().height ?? 0;
  element.style.scrollMarginTop = `${Math.ceil(bar) + 8}px`;
  element.scrollIntoView?.({ block: "start" });
}

function money(minor: number) {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: minor % 100 ? 2 : 0 })
    .format(Math.abs(minor) / 100);
}

class Refusal extends Error {
  constructor(message: string, readonly field?: string, readonly errors: { field: string; message: string }[] = []) { super(message); }
}
async function send<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(path, { ...init, headers: {
    ...(init.body ? { "Content-Type": "application/json" } : {}),
    ...(token ? { "Case-Access-Token": token } : {}),
    ...(init.headers as Record<string, string> | undefined) } });
  if (response.ok) return response.json() as Promise<T>;
  let body: { detail?: string; field?: string; errors?: { field: string; message: string }[] } = {};
  try { body = await response.json(); } catch { /* No problem body to show. */ }
  if (response.status === 402) throw new Refusal("The Desk Check uses this case's report, which needs the one-time purchase first.");
  throw new Refusal(typeof body.detail === "string" ? body.detail : "That did not work. Check your connection and try again.",
    body.field, Array.isArray(body.errors) ? body.errors : []);
}

/** The header's camera control: one tap opens the phone's camera on the dealer's paperwork. */
export function DealerCameraEntry({ onPhotos }: { onPhotos: (files: File[]) => void }) {
  function chosen(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;
    performance.clearMarks?.(LATENCY_MARK);
    performance.mark?.(LATENCY_MARK);
    onPhotos(files);
  }
  return <label className="header-camera">
    <input type="file" className="visually-hidden" accept={PHOTO_TYPES} capture="environment" multiple onChange={chosen} />
    <span className="header-camera-icon" aria-hidden="true" />
    I'm at the dealer<span className="visually-hidden">: photograph the paperwork</span>
  </label>;
}

/**
 * From a photo of the desk paperwork to a first result: the VIN read from it decides where it goes,
 * the Buyer confirms that before anything is compared, and only what the paperwork cannot answer is asked.
 */
export function AtTheDealer({ files, open, onCaseOpened, onFreshCase, onDeskCheck, onClose, pollDelayMs }: {
  files: File[]; open?: Credentials; pollDelayMs?: number;
  onCaseOpened: (value: Credentials) => void; onFreshCase: (value: FreshDeskCase) => void;
  onDeskCheck: () => void; onClose: () => void;
}) {
  const signedIn = useContext(AccountSignedInContext);
  const [target, setTarget] = useState<Credentials | undefined>(open);
  const [phase, setPhase] = useState<Phase>(open ? (files.length ? "reading" : "typed") : "start");
  const [routing, setRouting] = useState<DeskRouting>();
  const [documentId, setDocumentId] = useState<string>();
  const [unreadable, setUnreadable] = useState(false);
  const [freshVin, setFreshVin] = useState<string>();
  // The car the Buyer already confirmed, so a fresh case's own paperwork is not confirmed twice.
  const [confirmedVin, setConfirmedVin] = useState<string>();
  const [route, setRoute] = useState<Route>();
  const [typed, setTyped] = useState<Typed>();
  const [ownApr, setOwnApr] = useState<number>();
  const [result, setResult] = useState<FirstResult>();
  const [error, setError] = useState<{ message: string; field?: string; errors?: { field: string; message: string }[] }>();
  const [busy, setBusy] = useState(false);
  const [elapsed, setElapsed] = useState<{ read?: number; result?: number }>({});
  const heading = useRef<HTMLHeadingElement>(null);
  const resultHeading = useRef<HTMLHeadingElement>(null);
  const start = target ? `/api/v1/cases/${encodeURIComponent(target.caseId)}/desk-start` : "";
  // The upload reports from its own polling, so it reads the phase as it is now, not as it was rendered.
  const phaseNow = useRef(phase);
  phaseNow.current = phase;

  useEffect(() => { reveal(heading.current); }, []);
  useEffect(() => {
    // Typing needs to know which cars this case already has, so the Buyer can say which one it is for.
    if (phase === "typed" && target && !routing) void loadRouting().catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, target?.caseId]);
  useEffect(() => {
    if (phase !== "result") return;
    resultHeading.current?.focus({ preventScroll: true });
    reveal(resultHeading.current?.closest("section") ?? null);
  }, [phase]);

  async function step(action: () => Promise<void>) {
    setBusy(true); setError(undefined);
    try { await action(); }
    catch (failure) {
      const refusal = failure instanceof Refusal ? failure : new Refusal("That did not work. Check your connection and try again.");
      setError({ message: refusal.message, field: refusal.field, errors: refusal.errors });
    } finally { setBusy(false); }
  }

  async function startCase(values: FormData, extra: { vin?: string } = {}) {
    const options = await send<{ consentPolicies?: Record<string, string> }>("/api/v1/intake-options").catch(() => ({ consentPolicies: {} }));
    const policies: Record<string, string> = options.consentPolicies ?? {};
    const decision = (granted: boolean, policy?: string) => ({ granted, policyVersion: policy ?? "unknown" });
    const created = await send<{ case: { id: string }; accessToken: string }>("/api/v1/cases", { method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID(), ...(signedIn ? { "Account-Request": "1" } : {}) },
      body: JSON.stringify({
        buyerPostalCode: String(values.get("buyerPostalCode") ?? "").trim(),
        dealerPostalCode: String(values.get("dealerPostalCode") ?? "").trim(),
        purchaseMethod: String(values.get("purchaseMethod") ?? "CASH"),
        consents: { serviceProcessing: decision(true, policies.serviceProcessing),
          humanReview: decision(false, policies.humanReview), anonymizedResearch: decision(false, policies.anonymizedResearch) },
      }) });
    const credentials = { caseId: created.case.id, token: created.accessToken };
    if (extra.vin) await send(`/api/v1/cases/${encodeURIComponent(credentials.caseId)}/vehicle-evidence`, { method: "POST",
      body: JSON.stringify({ vin: extra.vin, manualFacts: null, listingUrl: null, confirmed: true }) }, credentials.token);
    return credentials;
  }

  async function loadRouting(document?: string, credentials = target!) {
    const path = `/api/v1/cases/${encodeURIComponent(credentials.caseId)}/desk-start${document ? `?documentId=${encodeURIComponent(document)}` : ""}`;
    const value = await send<DeskRouting>(path, {}, credentials.token);
    setRouting(value);
    return value;
  }

  async function read(document: string) {
    setDocumentId(document);
    await step(async () => {
      const value = await loadRouting(document);
      const read = measure(READ_MEASURE);
      setElapsed(current => ({ ...current, read }));
      if (value.status === "UNREADABLE") { setUnreadable(true); setPhase("typed"); return; }
      if (value.route === "THIS_CASE" && confirmedVin && value.vin === confirmedVin) return confirm("THIS_CASE", value);
      setPhase("route");
    });
  }

  async function confirm(chosen: Route, value = routing, numbers = typed) {
    // Confirming a car for a case that has none yet records it, so the case is for that car from here on.
    const vin = numbers ? numbers.vin : value?.vin;
    if (chosen === "THIS_CASE" && vin && !value?.caseVin && target)
      await send(`/api/v1/cases/${encodeURIComponent(target.caseId)}/vehicle-evidence`, { method: "POST",
        body: JSON.stringify({ vin, manualFacts: null, listingUrl: null, confirmed: true }) }, target.token);
    setRoute(chosen);
    if (needsQuestions(value, numbers)) setPhase("questions");
    else await showResult(chosen, numbers, ownApr);
  }

  async function showResult(chosen: Route, numbers = typed, apr = ownApr) {
    const value = await send<FirstResult>(`${start}/result`, { method: "POST", body: JSON.stringify({
      route: chosen, documentId: numbers ? undefined : documentId, typed: numbers, ownAprPercent: apr }) }, target!.token);
    setResult(value);
    const shown = measure(LATENCY_MEASURE);
    setElapsed(current => ({ ...current, result: shown }));
    setPhase("result");
  }

  async function startFresh(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const vin = freshVin!;
    await step(async () => {
      const fresh = await startCase(values, { vin });
      if (target) await send(`/api/v1/cases/${encodeURIComponent(fresh.caseId)}/trade/carry-over`, { method: "POST",
        headers: { "Source-Case-Access-Token": target.token }, body: JSON.stringify({ sourceCaseId: target.caseId }) }, fresh.token)
        .catch(() => undefined);
      onFreshCase({ ...fresh, agreed: target ?? fresh, agreedVin: routing?.agreedVin ?? routing?.caseVin ?? null, vin, files: [] });
      setTarget(fresh); setConfirmedVin(vin); setDocumentId(undefined); setRouting(undefined); setUnreadable(false);
      if (typed) {
        const value = await loadRouting(undefined, fresh);
        setRoute("THIS_CASE");
        if (needsQuestions(value, typed)) setPhase("questions");
        else {
          // The fresh case is the target from here; its path is not in state until the next render.
          const result = await send<FirstResult>(`/api/v1/cases/${encodeURIComponent(fresh.caseId)}/desk-start/result`, { method: "POST",
            body: JSON.stringify({ route: "THIS_CASE", typed }) }, fresh.token);
          setResult(result); setPhase("result");
        }
      } else setPhase("reading");
    });
  }

  function needsQuestions(value = routing, numbers = typed) {
    return tradeAsk(value, numbers) !== "NONE" || financingAsk(value, numbers);
  }

  return <section className="stack at-the-dealer" aria-label="At the dealer">
    <div className="panel">
      <div className="section-heading"><div>
        <p className="step">At the dealer</p>
        <h2 id="dealer-start-heading" ref={heading} tabIndex={-1}>Check the paperwork in front of you</h2>
      </div></div>
      <p className="muted">otdog reads the paperwork and shows you what to ask. It never contacts the dealer; you decide what to say.</p>
      {files.length > 0 && phase !== "result" && <p className="muted">{files.length === 1 ? "1 photo" : `${files.length} photos`} ready.</p>}

      {phase === "start" && <StartCase busy={busy} error={error} onSubmit={event => {
        event.preventDefault();
        const values = new FormData(event.currentTarget);
        void step(async () => {
          const created = await startCase(values);
          onCaseOpened(created); setTarget(created);
          setPhase(files.length ? "reading" : "typed");
        });
      }} />}

      {phase === "route" && routing && <RouteChoice routing={routing} busy={busy}
        onConfirm={chosen => void step(() => confirm(chosen))}
        onFresh={vin => { setFreshVin(vin); setPhase("fresh"); }}
        onType={() => setPhase("typed")} />}

      {phase === "fresh" && freshVin && <form onSubmit={startFresh}>
        <h3>Start a separate case for VIN {freshVin}</h3>
        <p className="muted">It gets its own result, including your state's fees and taxes.
          {routing?.agreedVin ? " Your Agreed Price stays as it is." : ""} Your trade-in comes with it.</p>
        <CaseFields dealerPostalCode={routing?.freshCase?.dealerPostalCode ?? undefined} purchaseMethod={routing?.freshCase?.purchaseMethod} />
        <div className="actions">
          <button type="submit" disabled={busy}>{busy ? "Starting…" : "Start the separate case"}</button>
          <button type="button" className="btn-secondary" onClick={() => setPhase(routing ? "route" : "typed")}>Back</button>
        </div>
      </form>}

      {phase === "typed" && <TypedForm unreadable={unreadable} routing={routing} busy={busy} error={error}
        onSubmit={(numbers, other) => {
          setTyped(numbers);
          if (other) { setFreshVin(numbers.vin); setPhase("fresh"); return; }
          void step(async () => {
            const value = routing ?? (target ? await loadRouting() : undefined);
            await confirm(value?.agreedVin ? "AGREED_CAR" : "THIS_CASE", value, numbers);
          });
        }} />}

      {phase === "questions" && target && <Questions credentials={target} ask={tradeAsk(routing, typed)} recorded={routing?.questions?.trade.recorded ?? null}
        financing={financingAsk(routing, typed)} busy={busy} error={error}
        onDone={(apr, saveTrade) => void step(async () => {
          if (saveTrade) await saveTrade();
          setOwnApr(apr);
          await showResult(route!, typed, apr);
        })} />}

      {phase === "reading" && <p className="muted">Reading your paperwork. Most pages take a few seconds.
        {" "}<button type="button" className="btn-link" onClick={() => setPhase("typed")}>Type the numbers instead</button></p>}
      {error && !["start", "typed", "questions"].includes(phase) && <p className="error" role="alert">{error.message}</p>}
      {phase !== "result" && <button type="button" className="btn-secondary" onClick={onClose}>Close</button>}
    </div>

    {phase === "result" && result && <ResultView result={result} headingRef={resultHeading} elapsed={elapsed}
      onDeskCheck={onDeskCheck} onType={() => { setTyped(undefined); setPhase("typed"); }} onClose={onClose} />}

    {/* Kept mounted while it reads, so the photos are sent once through the ordinary intake. */}
    {target && files.length > 0 && !typed && phase !== "fresh" && phase !== "start" && <DealerDocumentUpload key={target.caseId}
      caseId={target.caseId} token={target.token} initialFiles={files} pollDelayMs={pollDelayMs}
      heading={{ id: "dealer-paperwork-heading", step: "At the dealer", title: "Your paperwork" }}
      onExtracted={document => { if (phaseNow.current === "reading") void read(document); }}
      onFailed={() => { setUnreadable(true); setPhase(current => current === "reading" ? "typed" : current);
        void loadRouting().catch(() => undefined); }} />}
  </section>;
}

function tradeAsk(routing?: DeskRouting, typed?: Typed): TradeAsk {
  const ask = routing?.questions?.trade.ask ?? "WHETHER";
  return ask === "WHETHER" && typed?.tradeAllowanceMinor ? "DETAILS" : ask;
}
function financingAsk(routing?: DeskRouting, typed?: Typed) {
  const financing = routing?.questions?.financing;
  if (financing?.ownAprPercent != null) return false;
  if (typed) return Boolean(typed.aprPercent || typed.monthlyPaymentMinor || typed.termMonths);
  return financing?.ask ?? false;
}

function FieldError({ error, field }: { error?: { field?: string; message: string }; field: string }) {
  return error?.field === field ? <p className="error" role="alert" id={`dealer-${field}-error`}>{error.message}</p> : null;
}

function CaseFields({ dealerPostalCode, purchaseMethod }: { dealerPostalCode?: string; purchaseMethod?: string }) {
  return <>
    <label>Your ZIP code<input name="buyerPostalCode" inputMode="numeric" autoComplete="postal-code" required /></label>
    <label>Dealer ZIP code<input name="dealerPostalCode" inputMode="numeric" required defaultValue={dealerPostalCode} /></label>
    <label>How you are paying<select name="purchaseMethod" defaultValue={purchaseMethod ?? "CASH"}>
      <option value="CASH">Cash</option><option value="FINANCE">Dealer financing</option></select></label>
  </>;
}

function StartCase({ busy, error, onSubmit }: { busy: boolean; error?: { message: string };
  onSubmit: (event: FormEvent<HTMLFormElement>) => void }) {
  const signedIn = useContext(AccountSignedInContext);
  return <form onSubmit={onSubmit}>
    {signedIn && <p className="notice">Is this for a car in your journey? Open its Buying Case from the journey first, then
      tap “I'm at the dealer”, and the paperwork is checked against its Agreed Price and your trade-in.</p>}
    <p>Two ZIP codes set your state's taxes and fees. No account is needed, and the paperwork answers the rest.</p>
    <CaseFields />
    <p className="muted">Starting means otdog may process what you enter and photograph to produce your result.</p>
    {error && <p className="error" role="alert">{error.message}</p>}
    <button type="submit" disabled={busy}>{busy ? "Starting…" : "Read my paperwork"}</button>
  </form>;
}

function RouteChoice({ routing, busy, onConfirm, onFresh, onType }: { routing: DeskRouting; busy: boolean;
  onConfirm: (route: Route) => void; onFresh: (vin: string) => void; onType: () => void }) {
  const [otherVin, setOtherVin] = useState("");
  const known = routing.agreedVin ?? routing.caseVin;
  return <div className="dealer-route" role="group" aria-labelledby="dealer-route-heading">
    <h3 id="dealer-route-heading">Which car is this paperwork for?</h3>
    {routing.route === "AGREED_CAR" && <>
      <p>It shows VIN <strong className="vin">{routing.vin}</strong>, the car in your Agreed Price.</p>
      <button type="button" disabled={busy} onClick={() => onConfirm("AGREED_CAR")}>Check it against my Agreed Price</button>
    </>}
    {routing.route === "THIS_CASE" && <>
      <p>{routing.vin ? <>It shows VIN <strong className="vin">{routing.vin}</strong>{routing.caseVin ? ", the car in this case." : "."}</>
        : "No VIN could be read on it."}</p>
      <button type="button" disabled={busy} onClick={() => onConfirm("THIS_CASE")}>
        {routing.vin && !routing.caseVin ? "Yes, check this car" : "Check this paperwork"}</button>
    </>}
    {routing.route === "FRESH_CASE" && routing.freshCase && <>
      <p role="alert">It shows VIN <strong className="vin">{routing.vin}</strong>, not {routing.agreedVin ? "your agreed car" : "this case's car"}
        {known ? <> (VIN <span className="vin">{known}</span>)</> : null}. Nothing on it is compared with {routing.agreedVin ? "your Agreed Price" : "that car"}.</p>
      <button type="button" disabled={busy} onClick={() => onFresh(routing.vin!)}>Start a separate case for this car</button>
    </>}
    {routing.route === "CHOOSE" && <>
      <p>No VIN could be read on it.</p>
      <button type="button" disabled={busy} onClick={() => onConfirm(routing.agreedVin ? "AGREED_CAR" : "THIS_CASE")}>
        It is for {routing.agreedVin ? "my agreed car" : "this case's car"}, VIN {known}</button>
      <label>Or type the other car's VIN<input value={otherVin} autoComplete="off" onChange={event => setOtherVin(event.target.value)} /></label>
      <button type="button" className="btn-secondary" disabled={otherVin.trim().length !== 17}
        onClick={() => onFresh(otherVin.trim().toUpperCase())}>Start a separate case for that VIN</button>
    </>}
    {routing.route === "AGREED_CAR" || routing.route === "THIS_CASE"
      ? <p className="muted">Nothing is compared until you choose.</p> : null}
    <button type="button" className="btn-link" onClick={onType}>Type the numbers instead</button>
  </div>;
}

function TypedForm({ unreadable, routing, busy, error, onSubmit }: { unreadable: boolean; routing?: DeskRouting; busy: boolean;
  error?: { message: string; field?: string }; onSubmit: (numbers: Typed, other: boolean) => void }) {
  const known = routing?.agreedVin ?? routing?.caseVin ?? null;
  const [which, setWhich] = useState<"KNOWN" | "OTHER">(routing?.route === "FRESH_CASE" ? "OTHER" : "KNOWN");
  const [problem, setProblem] = useState<{ field: string; message: string }>();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const numbers: Typed = {};
    const amount = (name: keyof Typed) => {
      const text = String(values.get(name) ?? "").trim();
      if (!text) return true;
      const minor = toMinor(text);
      if (minor === null) { setProblem({ field: name, message: "Enter a dollar amount, like 25,000 or 489.50." }); return false; }
      (numbers as Record<string, number>)[name] = minor;
      return true;
    };
    setProblem(undefined);
    for (const name of ["sellingPriceMinor", "tradeAllowanceMinor", "cashDownMinor", "monthlyPaymentMinor", "outTheDoorMinor"] as const)
      if (!amount(name)) return;
    const term = String(values.get("termMonths") ?? "").trim(), apr = String(values.get("aprPercent") ?? "").trim();
    if (term) { if (!/^\d{1,3}$/.test(term)) return setProblem({ field: "termMonths", message: "Enter the term in whole months, like 72." }); numbers.termMonths = Number(term); }
    if (apr) { if (!/^\d{1,2}(\.\d{1,3})?$/.test(apr)) return setProblem({ field: "aprPercent", message: "Enter the APR as a number, like 6.9." }); numbers.aprPercent = Number(apr); }
    const vin = String(values.get("vin") ?? "").trim().toUpperCase();
    if (vin) numbers.vin = vin;
    if (Object.keys(numbers).filter(key => key !== "vin").length === 0)
      return setProblem({ field: "sellingPriceMinor", message: "Type at least one number from the worksheet." });
    const other = known ? which === "OTHER" : false;
    if (other && !vin) return setProblem({ field: "vin", message: "Type the VIN of the car on this paperwork to start a separate case for it." });
    onSubmit(numbers, other);
  }
  const shown = problem ?? error;
  const described = (field: string) => (shown?.field === field ? { "aria-describedby": `dealer-${field}-error`, "aria-invalid": true } : {});
  return <form onSubmit={submit} className="dealer-typed" noValidate>
    <h3>{unreadable ? "This photo could not be read. Type the numbers instead" : "Type the worksheet's numbers"}</h3>
    <p className="muted">Fill in what the four-square or buyer's order shows and leave the rest blank. These numbers are compared, not saved.</p>
    {known && <fieldset><legend>Which car is it for?</legend><div className="segmented">
      <label><input type="radio" name="which" checked={which === "KNOWN"} onChange={() => setWhich("KNOWN")} />
        {routing?.agreedVin ? "My agreed car" : "This case's car"}</label>
      <label><input type="radio" name="which" checked={which === "OTHER"} onChange={() => setWhich("OTHER")} />Another car</label>
    </div></fieldset>}
    <div className="dealer-typed-grid">
      <label>Selling price<input name="sellingPriceMinor" inputMode="decimal" {...described("sellingPriceMinor")} /></label>
      <FieldError error={shown} field="sellingPriceMinor" />
      <label>Trade-in allowance<input name="tradeAllowanceMinor" inputMode="decimal" {...described("tradeAllowanceMinor")} /></label>
      <FieldError error={shown} field="tradeAllowanceMinor" />
      <label>Down payment<input name="cashDownMinor" inputMode="decimal" {...described("cashDownMinor")} /></label>
      <FieldError error={shown} field="cashDownMinor" />
      <label>Monthly payment<input name="monthlyPaymentMinor" inputMode="decimal" {...described("monthlyPaymentMinor")} /></label>
      <FieldError error={shown} field="monthlyPaymentMinor" />
      <label>Term (months)<input name="termMonths" inputMode="numeric" {...described("termMonths")} /></label>
      <FieldError error={shown} field="termMonths" />
      <label>APR (%)<input name="aprPercent" inputMode="decimal" {...described("aprPercent")} /></label>
      <FieldError error={shown} field="aprPercent" />
      <label>Out-the-door total, if shown<input name="outTheDoorMinor" inputMode="decimal" {...described("outTheDoorMinor")} /></label>
      <FieldError error={shown} field="outTheDoorMinor" />
      <label>VIN{known && which === "KNOWN" ? " (optional)" : ""}<input name="vin" autoComplete="off" {...described("vin")} /></label>
      <FieldError error={shown} field="vin" />
    </div>
    {shown && !["sellingPriceMinor", "tradeAllowanceMinor", "cashDownMinor", "monthlyPaymentMinor", "termMonths", "aprPercent",
      "outTheDoorMinor", "vin"].includes(shown.field ?? "") && <p className="error" role="alert">{shown.message}</p>}
    <button type="submit" disabled={busy}>{busy ? "Checking…" : "Check these numbers"}</button>
  </form>;
}

function Questions({ credentials, ask, recorded, financing, busy, error, onDone }: {
  credentials: Credentials; ask: TradeAsk; recorded: Trade | null; financing: boolean; busy: boolean;
  error?: { message: string; field?: string; errors?: { field: string; message: string }[] };
  onDone: (ownApr: number | undefined, saveTrade?: () => Promise<void>) => void;
}) {
  const [trading, setTrading] = useState<"YES" | "NO" | undefined>(ask === "DETAILS" || ask === "OWED" ? "YES" : undefined);
  const [owed, setOwed] = useState<string>("");
  const [unknownPayoff, setUnknownPayoff] = useState(false);
  const [hasOwn, setHasOwn] = useState<"YES" | "NO">();
  const [typedVin, setTypedVin] = useState("");
  const [typedMileage, setTypedMileage] = useState("");
  const [conditions, setConditions] = useState(CONDITIONS);
  const [problem, setProblem] = useState<{ field: string; message: string }>();
  useEffect(() => {
    if (ask !== "DETAILS" && ask !== "WHETHER") return;
    fetch(`/api/v1/cases/${encodeURIComponent(credentials.caseId)}/trade`, { headers: { "Case-Access-Token": credentials.token } })
      .then(response => (response.ok ? response.json() : undefined))
      .then((view?: TradeView) => { if (view?.conditions?.length) setConditions(view.conditions.map(({ value, label }) => ({ value, label }))); })
      .catch(() => undefined);
  }, [ask, credentials.caseId, credentials.token]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setProblem(undefined);
    const values = new FormData(event.currentTarget);
    let apr: number | undefined;
    if (financing && hasOwn === "YES") {
      const text = String(values.get("ownApr") ?? "").trim();
      if (!/^\d{1,2}(\.\d{1,3})?$/.test(text)) return setProblem({ field: "ownApr", message: "Enter your lender's APR as a number, like 5.9." });
      apr = Number(text);
    }
    const saving = ask === "OWED" || (ask !== "NONE" && trading === "YES");
    let save: (() => Promise<void>) | undefined;
    if (saving) {
      const payoff = String(values.get("payoff") ?? "").trim();
      const payoffMinor = owed === "YES" && !unknownPayoff && payoff ? toMinor(payoff) : null;
      if (owed === "YES" && !unknownPayoff && payoff && payoffMinor === null)
        return setProblem({ field: "payoffMinor", message: "Enter the payoff as a dollar amount, like 12,400." });
      const body = ask === "OWED" && recorded
        ? { basedOnRevision: recorded.revision, vin: recorded.vehicle.vin, mileage: recorded.mileage, condition: recorded.condition }
        : { vin: String(values.get("tradeVin") ?? "").trim(), mileage: Number(String(values.get("mileage") ?? "").replace(/[,\s]/g, "")) || null,
          condition: String(values.get("condition") ?? "") || null };
      save = async () => {
        await send(`/api/v1/cases/${encodeURIComponent(credentials.caseId)}/trade`, { method: "PUT", body: JSON.stringify({ ...body,
          moneyOwed: owed || null, lender: owed === "YES" ? String(values.get("lender") ?? "") : null, payoffMinor,
          payoffGoodThrough: owed === "YES" && !unknownPayoff ? String(values.get("goodThrough") ?? "") || null : null,
          payoffUnknown: owed === "YES" ? unknownPayoff : null }) }, credentials.token);
      };
    }
    onDone(apr, save);
  }
  const fieldErrors = [...(error?.errors ?? []), ...(problem ? [problem] : [])];
  const errorFor = (field: string) => fieldErrors.find(item => item.field === field)?.message;
  const field = (name: string) => errorFor(name) ? <p className="error" role="alert">{errorFor(name)}</p> : null;
  const owedChoice = <fieldset><legend>Do you still owe money on it?</legend><div className="segmented">
    {[["YES", "Yes"], ["NO", "No"], ["NOT_SURE", "I'm not sure"]].map(([value, label]) =>
      <label key={value}><input type="radio" name="owed" value={value} required checked={owed === value} onChange={() => setOwed(value)} />{label}</label>)}
  </div>{field("moneyOwed")}</fieldset>;
  return <form onSubmit={submit} className="dealer-questions">
    <h3>What the paperwork cannot tell us</h3>
    {ask === "NONE" && recorded && <p className="muted">Using your trade-in: {vehicleName(recorded)}
      {recorded.payoffMinor != null ? `, payoff ${money(recorded.payoffMinor)}` : ""}.{recorded.payoffStale ? " Its payoff is past its good-through date; ask your lender for a new one." : ""}</p>}
    {ask === "WHETHER" && <fieldset><legend>Are you trading a car in?</legend><div className="segmented">
      <label><input type="radio" name="trading" required checked={trading === "YES"} onChange={() => setTrading("YES")} />Yes</label>
      <label><input type="radio" name="trading" required checked={trading === "NO"} onChange={() => setTrading("NO")} />No</label>
    </div></fieldset>}
    {ask === "DETAILS" && <p>This paperwork shows a trade-in. Tell us about the car, once; it stays with your journey.</p>}
    {ask === "OWED" && recorded && <p>Your trade-in: {vehicleName(recorded)}.</p>}
    {(ask === "DETAILS" || ask === "WHETHER") && trading === "YES" && <>
      <label>Trade-in VIN<input name="tradeVin" autoComplete="off" required value={typedVin}
        onChange={event => setTypedVin(event.target.value)} /></label>{field("vin")}
      <label>Mileage<input name="mileage" inputMode="numeric" required value={typedMileage}
        onChange={event => setTypedMileage(event.target.value)} /></label>{field("mileage")}
      <label>Condition<select name="condition" required defaultValue="">
        <option value="" disabled>Choose one</option>
        {conditions.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>{field("condition")}
    </>}
    {(ask === "OWED" || ((ask === "DETAILS" || ask === "WHETHER") && trading === "YES")) && <>
      {owedChoice}
      {owed === "YES" && <>
        <label>Lender<input name="lender" required /></label>{field("lender")}
        <label><input type="checkbox" checked={unknownPayoff} onChange={event => setUnknownPayoff(event.target.checked)} />I don't know the payoff yet</label>
        {!unknownPayoff && <>
          <label>Payoff amount<input name="payoff" inputMode="decimal" required /></label>{field("payoffMinor")}
          <label>Good through<input name="goodThrough" type="date" required /></label>{field("payoffGoodThrough")}
        </>}
      </>}
    </>}
    {(recorded && ask !== "WHETHER" && ask !== "DETAILS" ? <TradeValueLinks headingLevel={4} vin={recorded.vehicle.vin} mileage={recorded.mileage} />
      : (ask === "DETAILS" || ask === "WHETHER") && trading === "YES" ? <TradeValueLinks headingLevel={4} vin={typedVin.trim() || null}
        mileage={/^\d+$/.test(typedMileage.replace(/[,\s]/g, "")) ? Number(typedMileage.replace(/[,\s]/g, "")) : null} /> : null)}
    {financing && <fieldset><legend>The worksheet shows a loan. Do you have your own loan offer from a bank or credit union?</legend>
      <div className="segmented">
        <label><input type="radio" name="hasOwn" required checked={hasOwn === "YES"} onChange={() => setHasOwn("YES")} />Yes</label>
        <label><input type="radio" name="hasOwn" required checked={hasOwn === "NO"} onChange={() => setHasOwn("NO")} />No</label>
      </div>
      {hasOwn === "YES" && <><label>Its APR (%)<input name="ownApr" inputMode="decimal" required /></label>{field("ownApr")}</>}
    </fieldset>}
    {error && !error.errors?.length && <p className="error" role="alert">{error.message}</p>}
    <button type="submit" disabled={busy}>{busy ? "Checking…" : "Show my result"}</button>
  </form>;
}

function ResultView({ result, headingRef, elapsed, onDeskCheck, onType, onClose }: {
  result: FirstResult; headingRef: RefObject<HTMLHeadingElement | null>; elapsed: { read?: number; result?: number };
  onDeskCheck: () => void; onType: () => void; onClose: () => void;
}) {
  const total = result.outTheDoor;
  // The lead is the first finding; the rest stay one tap away.
  const more = result.findings.slice(1);
  return <section className="panel dealer-result" aria-labelledby="dealer-result-heading" data-read-ms={elapsed.read} data-first-result-ms={elapsed.result}>
    <p className="step">First look · {result.source === "TYPED_BY_BUYER" ? "as you typed it" : "as read, not verified"}</p>
    <h2 id="dealer-result-heading" ref={headingRef} tabIndex={-1}>
      {total.amountMinor !== null ? `Out the door: ${money(total.amountMinor)}` : "No out-the-door total shown"}</h2>
    {total.differenceMinor !== null && total.agreedMinor !== null && <p className="dealer-result-delta">
      {total.differenceMinor === 0 ? `The same as your Agreed Price of ${money(total.agreedMinor)}.`
        : `${money(total.differenceMinor)} ${total.differenceMinor > 0 ? "more" : "less"} than your Agreed Price of ${money(total.agreedMinor)}.`}</p>}
    {result.lead ? <div className="dealer-lead">
      <p className="dealer-lead-summary">{result.lead.summary}</p>
      <p className="dealer-question"><span className="dealer-question-label">Ask at the desk</span>
        <q>{result.lead.deskQuestion}</q></p>
      {result.tacticFindings?.some(tactic => (tactic.code === result.lead?.code || tactic.tradeFindingCode === result.lead?.code) && tactic.questionReview === "PENDING_OPERATOR_REVIEW")
        && <p className="muted">Draft question wording awaits operator review.</p>}
    </div> : <p>Nothing in these terms stood out. Check them against the paperwork for the full result.</p>}
    {more.length > 0 && <details>
      <summary>{more.length} more to ask about</summary>
      <ul className="dealer-more">{more.map((finding, index) => <li key={`${finding.code}-${index}`}>
        <p>{finding.summary}</p><p className="muted"><q>{finding.deskQuestion}</q></p>
        {result.tacticFindings?.some(tactic => (tactic.code === finding.code || tactic.tradeFindingCode === finding.code) && tactic.questionReview === "PENDING_OPERATOR_REVIEW")
          && <p className="muted">Draft question wording awaits operator review.</p>}
      </li>)}</ul>
    </details>}
    <div className="actions">
      {result.route === "AGREED_CAR" && <button type="button" onClick={onDeskCheck}>Open the full Desk Check</button>}
      {result.route === "THIS_CASE" && result.source === "READ_FROM_PAPERWORK" && <button type="button" onClick={() => {
        onClose();
        window.setTimeout(() => { const target = document.getElementById("verify-heading"); target?.scrollIntoView?.({ block: "start" }); target?.focus(); });
      }}>Check the terms against the paperwork</button>}
      {result.source === "READ_FROM_PAPERWORK" && <button type="button" className="btn-secondary" onClick={onType}>Type the numbers instead</button>}
      <button type="button" className="btn-secondary" onClick={onClose}>Close</button>
    </div>
  </section>;
}
