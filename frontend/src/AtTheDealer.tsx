import { ChangeEvent, FormEvent, RefObject, useContext, useEffect, useRef, useState } from "react";
import { AccountSignedInContext } from "./BuyerAccount";
import { DealerFields } from "./DealerFields";
import { DealerDocumentUpload } from "./DealerDocumentUpload";
import type { FreshDeskCase } from "./DeskCheck";
import { SigningNote } from "./SigningNote";
import { toMinor, vehicleName, type Trade, type TradeView } from "./TradeVehicle";
import { TradeValueLinks } from "./TradeValueChecks";
import type { DeskTacticFinding } from "./DeskTactics";
import { ListingCapture, ListingPriceNote, type ListingPriceCheck } from "./ListingCapture";
import { GovernmentFeeNote, type GovernmentFeeCheck } from "./GovernmentFees";
import { DealerAddOnsNote, type DealerAddOnCheck } from "./DealerAddOns";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";
import { localizeReport, type Finding } from "./i18n/findings";

type Credentials = { caseId: string; token: string };
type Route = "AGREED_CAR" | "THIS_CASE";
type TradeAsk = "NONE" | "OWED" | "DETAILS" | "WHETHER";
export type DeskRouting = {
  status: "AWAITING_PAPERWORK" | "READING" | "UNREADABLE" | "READ";
  documentId: string | null; documentType: string | null; vin: string | null;
  route: "AGREED_CAR" | "THIS_CASE" | "FRESH_CASE" | "CHOOSE" | null;
  agreedVin: string | null; caseVin: string | null;
  freshCase: { vin: string; dealerLocation?: string; dealerPostalCode?: string | null; purchaseMethod: string } | null;
  questions: null | {
    trade: { ask: TradeAsk; paperworkShowsTrade: boolean; recorded: Trade | null };
    financing: { ask: boolean; paperworkShowsLoan: boolean; ownAprPercent: number | null; ownTermMonths: number | null };
  };
};
export type DeskFinding = { code: string; summary: string; amountMinor: number | null; deskQuestion: string; tacticCode?: string | null };
export type FirstResult = {
  textFindings?: Record<string, Finding>;
  route: Route; source: "READ_FROM_PAPERWORK" | "TYPED_BY_BUYER";
  outTheDoor: { amountMinor: number | null; agreedMinor: number | null; differenceMinor: number | null };
  lead: DeskFinding | null; findings: DeskFinding[];
  tacticFindings?: DeskTacticFinding[];
  listingPrice?: ListingPriceCheck | null;
  governmentFees?: GovernmentFeeCheck | null;
  addOns?: DealerAddOnCheck | null;
};
type Typed = { vin?: string; sellingPriceMinor?: number; tradeAllowanceMinor?: number; cashDownMinor?: number;
  monthlyPaymentMinor?: number; termMonths?: number; aprPercent?: number; outTheDoorMinor?: number };
type Phase = "start" | "reading" | "route" | "fresh" | "typed" | "questions" | "result";

const PHOTO_TYPES = "image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif";
/** Trade-in conditions offered until the server's own list arrives. */
const CONDITIONS = ["EXCELLENT", "GOOD", "FAIR", "POOR"] as const;
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
  return format.money(Math.abs(minor), { minimumFractionDigits: minor % 100 ? 2 : 0 });
}

/** A refused step. `reason` names a refusal whose words come from the catalogue; otherwise the message is the server's. */
class Refusal extends Error {
  constructor(message: string, readonly field?: string, readonly errors: { field: string; message: string }[] = [],
    readonly reason?: "purchase-required" | "failed") { super(message); }
}
async function send<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const response = await fetch(path, { ...init, headers: {
    ...(init.body ? { "Content-Type": "application/json" } : {}),
    ...(token ? { "Case-Access-Token": token } : {}),
    ...(init.headers as Record<string, string> | undefined) } });
  if (response.ok) return response.json() as Promise<T>;
  let body: { detail?: string; field?: string; errors?: { field: string; message: string }[] } = {};
  try { body = await response.json(); } catch { /* No problem body to show. */ }
  if (response.status === 402) throw new Refusal("purchase-required", undefined, [], "purchase-required");
  const errors = Array.isArray(body.errors) ? body.errors : [];
  if (typeof body.detail !== "string") throw new Refusal("failed", body.field, errors, "failed");
  throw new Refusal(body.detail, body.field, errors);
}

/** The header's camera control: one tap opens the phone's camera on the dealer's paperwork. */
export function DealerCameraEntry({ onPhotos }: { onPhotos: (files: File[]) => void }) {
  const { m: { dealer: { atTheDealer: t } } } = useMessages();
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
    {t.cameraEntry}<span className="visually-hidden">{t.cameraEntryHidden}</span>
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
  const { m: { dealer: { atTheDealer: t, common } }, fill, language } = useMessages();
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
      const refusal = failure instanceof Refusal ? failure : new Refusal("failed", undefined, [], "failed");
      const message = refusal.reason === "purchase-required" ? common.purchaseRequired : refusal.reason === "failed" ? t.failed : refusal.message;
      setError({ message, field: refusal.field, errors: refusal.errors });
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
        language,
        dealerCity: String(values.get("dealerCity") ?? "").trim(),
        dealerState: String(values.get("dealerState") ?? ""),
        dealerName: String(values.get("dealerName") ?? "").trim() || undefined,
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

  return <section className="stack at-the-dealer" aria-label={t.label}>
    <div className="panel">
      <div className="section-heading"><div>
        <p className="step">{t.step}</p>
        <h2 id="dealer-start-heading" ref={heading} tabIndex={-1}>{t.heading}</h2>
      </div></div>
      <p className="muted">{t.intro}</p>
      {files.length > 0 && phase !== "result" && <p className="muted">{fill(t.photosReady, { count: files.length })}</p>}

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
        <h3>{fill(t.freshHeading, { vin: freshVin })}</h3>
        <p className="muted">{routing?.agreedVin ? t.freshBodyAgreed : t.freshBody}</p>
        <CaseFields dealerLocation={routing?.freshCase?.dealerLocation ?? routing?.freshCase?.dealerPostalCode ?? undefined} purchaseMethod={routing?.freshCase?.purchaseMethod} />
        <div className="actions">
          <button type="submit" disabled={busy}>{busy ? common.starting : t.startSeparate}</button>
          <button type="button" className="btn-secondary" onClick={() => setPhase(routing ? "route" : "typed")}>{t.back}</button>
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

      {phase === "reading" && <p className="muted">{t.reading}
        {" "}<button type="button" className="btn-link" onClick={() => setPhase("typed")}>{common.typeInstead}</button></p>}
      {error && !["start", "typed", "questions"].includes(phase) && <p className="error" role="alert">{error.message}</p>}
      {phase !== "result" && <button type="button" className="btn-secondary" onClick={onClose}>{common.close}</button>}
    </div>

    {phase === "result" && result && <ResultView result={result} headingRef={resultHeading} elapsed={elapsed}
      onDeskCheck={onDeskCheck} onType={() => { setTyped(undefined); setPhase("typed"); }} onClose={onClose} />}
    {/* The dealer's ad, saved by the Buyer, sets the advertised price beside this paperwork. */}
    {phase === "result" && result && target && <ListingCapture key={`listing-${target.caseId}`} caseId={target.caseId}
      token={target.token} compact pollDelayMs={pollDelayMs}
      onConfirmed={() => void step(() => showResult(route!, typed, ownApr))} />}

    {/* Kept mounted while it reads, so the photos are sent once through the ordinary intake. */}
    {target && files.length > 0 && !typed && phase !== "fresh" && phase !== "start" && <DealerDocumentUpload key={target.caseId}
      caseId={target.caseId} token={target.token} initialFiles={files} pollDelayMs={pollDelayMs}
      heading={{ id: "dealer-paperwork-heading", step: t.paperworkStep, title: t.paperworkTitle }}
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

function CaseFields({ dealerLocation, purchaseMethod }: { dealerLocation?: string; purchaseMethod?: string }) {
  const { m: { dealer: { atTheDealer: t } } } = useMessages();
  const [buyerPostalCode, setBuyerPostalCode] = useState("");
  return <>
    <label>{t.zip}<input name="buyerPostalCode" inputMode="numeric" autoComplete="postal-code" required
      onChange={event => setBuyerPostalCode(event.target.value)} /></label>
    <DealerFields buyerPostalCode={buyerPostalCode} initialLocation={dealerLocation} />
    <label>{t.paying}<select name="purchaseMethod" defaultValue={purchaseMethod ?? "CASH"}>
      <option value="CASH">{t.cash}</option><option value="FINANCE">{t.dealerFinancing}</option></select></label>
  </>;
}

function StartCase({ busy, error, onSubmit }: { busy: boolean; error?: { message: string };
  onSubmit: (event: FormEvent<HTMLFormElement>) => void }) {
  const { m: { dealer: { atTheDealer: t, common } } } = useMessages();
  const signedIn = useContext(AccountSignedInContext);
  return <form onSubmit={onSubmit}>
    {signedIn && <p className="notice">{t.journeyNotice}</p>}
    <p>{t.zipNote}</p>
    <CaseFields />
    <p className="muted">{t.consent}</p>
    {error && <p className="error" role="alert">{error.message}</p>}
    <button type="submit" disabled={busy}>{busy ? common.starting : t.readMyPaperwork}</button>
  </form>;
}

function RouteChoice({ routing, busy, onConfirm, onFresh, onType }: { routing: DeskRouting; busy: boolean;
  onConfirm: (route: Route) => void; onFresh: (vin: string) => void; onType: () => void }) {
  const { m: { dealer: { atTheDealer: { route: t }, common } }, fill, fillNodes } = useMessages();
  const [otherVin, setOtherVin] = useState("");
  const known = routing.agreedVin ?? routing.caseVin;
  const vin = <strong className="vin">{routing.vin}</strong>;
  const notThisCar = routing.agreedVin ? (known ? t.notAgreedCarKnown : t.notAgreedCar) : known ? t.notCaseCarKnown : t.notCaseCar;
  return <div className="dealer-route" role="group" aria-labelledby="dealer-route-heading">
    <h3 id="dealer-route-heading">{t.heading}</h3>
    {routing.route === "AGREED_CAR" && <>
      <p>{fillNodes(t.agreedCar, { vin })}</p>
      <button type="button" disabled={busy} onClick={() => onConfirm("AGREED_CAR")}>{t.checkAgreed}</button>
    </>}
    {routing.route === "THIS_CASE" && <>
      <p>{routing.vin ? fillNodes(routing.caseVin ? t.thisCaseCar : t.shows, { vin }) : t.noVin}</p>
      <button type="button" disabled={busy} onClick={() => onConfirm("THIS_CASE")}>
        {routing.vin && !routing.caseVin ? t.yesCheck : t.checkPaperwork}</button>
    </>}
    {routing.route === "FRESH_CASE" && routing.freshCase && <>
      <p role="alert">{fillNodes(notThisCar, { vin, known: <span className="vin">{known}</span> })}</p>
      <button type="button" disabled={busy} onClick={() => onFresh(routing.vin!)}>{t.startSeparateForCar}</button>
    </>}
    {routing.route === "CHOOSE" && <>
      <p>{t.noVin}</p>
      <button type="button" disabled={busy} onClick={() => onConfirm(routing.agreedVin ? "AGREED_CAR" : "THIS_CASE")}>
        {fill(routing.agreedVin ? t.forAgreedCar : t.forCaseCar, { vin: known ?? "" })}</button>
      <label>{t.otherVin}<input value={otherVin} autoComplete="off" onChange={event => setOtherVin(event.target.value)} /></label>
      <button type="button" className="btn-secondary" disabled={otherVin.trim().length !== 17}
        onClick={() => onFresh(otherVin.trim().toUpperCase())}>{t.startSeparateForVin}</button>
    </>}
    {routing.route === "AGREED_CAR" || routing.route === "THIS_CASE"
      ? <p className="muted">{t.nothingCompared}</p> : null}
    <button type="button" className="btn-link" onClick={onType}>{common.typeInstead}</button>
  </div>;
}

function TypedForm({ unreadable, routing, busy, error, onSubmit }: { unreadable: boolean; routing?: DeskRouting; busy: boolean;
  error?: { message: string; field?: string }; onSubmit: (numbers: Typed, other: boolean) => void }) {
  const { m: { dealer: { atTheDealer: { typed: t }, common } } } = useMessages();
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
      if (minor === null) { setProblem({ field: name, message: t.amountError }); return false; }
      (numbers as Record<string, number>)[name] = minor;
      return true;
    };
    setProblem(undefined);
    for (const name of ["sellingPriceMinor", "tradeAllowanceMinor", "cashDownMinor", "monthlyPaymentMinor", "outTheDoorMinor"] as const)
      if (!amount(name)) return;
    const term = String(values.get("termMonths") ?? "").trim(), apr = String(values.get("aprPercent") ?? "").trim();
    if (term) { if (!/^\d{1,3}$/.test(term)) return setProblem({ field: "termMonths", message: t.termError }); numbers.termMonths = Number(term); }
    if (apr) { if (!/^\d{1,2}(\.\d{1,3})?$/.test(apr)) return setProblem({ field: "aprPercent", message: t.aprError }); numbers.aprPercent = Number(apr); }
    const vin = String(values.get("vin") ?? "").trim().toUpperCase();
    if (vin) numbers.vin = vin;
    if (Object.keys(numbers).filter(key => key !== "vin").length === 0)
      return setProblem({ field: "sellingPriceMinor", message: t.atLeastOne });
    const other = known ? which === "OTHER" : false;
    if (other && !vin) return setProblem({ field: "vin", message: t.vinNeeded });
    onSubmit(numbers, other);
  }
  const shown = problem ?? error;
  const described = (field: string) => (shown?.field === field ? { "aria-describedby": `dealer-${field}-error`, "aria-invalid": true } : {});
  return <form onSubmit={submit} className="dealer-typed" noValidate>
    <h3>{unreadable ? t.unreadableHeading : t.heading}</h3>
    <p className="muted">{t.help}</p>
    {known && <fieldset><legend>{t.whichCar}</legend><div className="segmented">
      <label><input type="radio" name="which" checked={which === "KNOWN"} onChange={() => setWhich("KNOWN")} />
        {routing?.agreedVin ? t.agreedCar : t.caseCar}</label>
      <label><input type="radio" name="which" checked={which === "OTHER"} onChange={() => setWhich("OTHER")} />{t.anotherCar}</label>
    </div></fieldset>}
    <div className="dealer-typed-grid">
      <label>{t.sellingPrice}<input name="sellingPriceMinor" inputMode="decimal" {...described("sellingPriceMinor")} /></label>
      <FieldError error={shown} field="sellingPriceMinor" />
      <label>{t.tradeAllowance}<input name="tradeAllowanceMinor" inputMode="decimal" {...described("tradeAllowanceMinor")} /></label>
      <FieldError error={shown} field="tradeAllowanceMinor" />
      <label>{t.downPayment}<input name="cashDownMinor" inputMode="decimal" {...described("cashDownMinor")} /></label>
      <FieldError error={shown} field="cashDownMinor" />
      <label>{t.monthlyPayment}<input name="monthlyPaymentMinor" inputMode="decimal" {...described("monthlyPaymentMinor")} /></label>
      <FieldError error={shown} field="monthlyPaymentMinor" />
      <label>{t.term}<input name="termMonths" inputMode="numeric" {...described("termMonths")} /></label>
      <FieldError error={shown} field="termMonths" />
      <label>{t.apr}<input name="aprPercent" inputMode="decimal" {...described("aprPercent")} /></label>
      <FieldError error={shown} field="aprPercent" />
      <label>{t.outTheDoor}<input name="outTheDoorMinor" inputMode="decimal" {...described("outTheDoorMinor")} /></label>
      <FieldError error={shown} field="outTheDoorMinor" />
      <label>{known && which === "KNOWN" ? t.vinOptional : common.vin}<input name="vin" autoComplete="off" {...described("vin")} /></label>
      <FieldError error={shown} field="vin" />
    </div>
    {shown && !["sellingPriceMinor", "tradeAllowanceMinor", "cashDownMinor", "monthlyPaymentMinor", "termMonths", "aprPercent",
      "outTheDoorMinor", "vin"].includes(shown.field ?? "") && <p className="error" role="alert">{shown.message}</p>}
    <button type="submit" disabled={busy}>{busy ? common.checking : t.check}</button>
  </form>;
}

function Questions({ credentials, ask, recorded, financing, busy, error, onDone }: {
  credentials: Credentials; ask: TradeAsk; recorded: Trade | null; financing: boolean; busy: boolean;
  error?: { message: string; field?: string; errors?: { field: string; message: string }[] };
  onDone: (ownApr: number | undefined, saveTrade?: () => Promise<void>) => void;
}) {
  const { m: { dealer: { atTheDealer: { questions: t }, tradeVehicle, common } }, fill } = useMessages();
  const [trading, setTrading] = useState<"YES" | "NO" | undefined>(ask === "DETAILS" || ask === "OWED" ? "YES" : undefined);
  const [owed, setOwed] = useState<string>("");
  const [unknownPayoff, setUnknownPayoff] = useState(false);
  const [hasOwn, setHasOwn] = useState<"YES" | "NO">();
  const [typedVin, setTypedVin] = useState("");
  const [typedMileage, setTypedMileage] = useState("");
  const [conditions, setConditions] = useState<{ value: string; label: string }[]>(
    () => CONDITIONS.map(value => ({ value, label: t.conditions[value] })));
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
      if (!/^\d{1,2}(\.\d{1,3})?$/.test(text)) return setProblem({ field: "ownApr", message: t.ownAprError });
      apr = Number(text);
    }
    const saving = ask === "OWED" || (ask !== "NONE" && trading === "YES");
    let save: (() => Promise<void>) | undefined;
    if (saving) {
      const payoff = String(values.get("payoff") ?? "").trim();
      const payoffMinor = owed === "YES" && !unknownPayoff && payoff ? toMinor(payoff) : null;
      if (owed === "YES" && !unknownPayoff && payoff && payoffMinor === null)
        return setProblem({ field: "payoffMinor", message: t.payoffError });
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
  const carName = (trade: Trade) => vehicleName(trade, tradeVehicle.unidentified);
  const owedChoice = <fieldset><legend>{common.owedQuestion}</legend><div className="segmented">
    {[["YES", common.yes], ["NO", common.no], ["NOT_SURE", common.notSure]].map(([value, label]) =>
      <label key={value}><input type="radio" name="owed" value={value} required checked={owed === value} onChange={() => setOwed(value)} />{label}</label>)}
  </div>{field("moneyOwed")}</fieldset>;
  return <form onSubmit={submit} className="dealer-questions">
    <h3>{t.heading}</h3>
    {ask === "NONE" && recorded && <p className="muted">{recorded.payoffMinor != null
      ? fill(t.usingTradePayoff, { car: carName(recorded), payoff: money(recorded.payoffMinor) })
      : fill(t.usingTrade, { car: carName(recorded) })}{recorded.payoffStale ? ` ${t.stalePayoff}` : ""}</p>}
    {ask === "WHETHER" && <fieldset><legend>{t.trading}</legend><div className="segmented">
      <label><input type="radio" name="trading" required checked={trading === "YES"} onChange={() => setTrading("YES")} />{common.yes}</label>
      <label><input type="radio" name="trading" required checked={trading === "NO"} onChange={() => setTrading("NO")} />{common.no}</label>
    </div></fieldset>}
    {ask === "DETAILS" && <p>{t.details}</p>}
    {ask === "OWED" && recorded && <p>{fill(t.yourTrade, { car: carName(recorded) })}</p>}
    {(ask === "DETAILS" || ask === "WHETHER") && trading === "YES" && <>
      <label>{t.tradeVin}<input name="tradeVin" autoComplete="off" required value={typedVin}
        onChange={event => setTypedVin(event.target.value)} /></label>{field("vin")}
      <label>{common.mileage}<input name="mileage" inputMode="numeric" required value={typedMileage}
        onChange={event => setTypedMileage(event.target.value)} /></label>{field("mileage")}
      <label>{common.condition}<select name="condition" required defaultValue="">
        <option value="" disabled>{t.chooseOne}</option>
        {conditions.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>{field("condition")}
    </>}
    {(ask === "OWED" || ((ask === "DETAILS" || ask === "WHETHER") && trading === "YES")) && <>
      {owedChoice}
      {owed === "YES" && <>
        <label>{common.lender}<input name="lender" required /></label>{field("lender")}
        <label><input type="checkbox" checked={unknownPayoff} onChange={event => setUnknownPayoff(event.target.checked)} />{common.payoffUnknownYet}</label>
        {!unknownPayoff && <>
          <label>{t.payoffAmount}<input name="payoff" inputMode="decimal" required /></label>{field("payoffMinor")}
          <label>{common.goodThrough}<input name="goodThrough" type="date" required /></label>{field("payoffGoodThrough")}
        </>}
      </>}
    </>}
    {(recorded && ask !== "WHETHER" && ask !== "DETAILS" ? <TradeValueLinks headingLevel={4} vin={recorded.vehicle.vin} mileage={recorded.mileage} />
      : (ask === "DETAILS" || ask === "WHETHER") && trading === "YES" ? <TradeValueLinks headingLevel={4} vin={typedVin.trim() || null}
        mileage={/^\d+$/.test(typedMileage.replace(/[,\s]/g, "")) ? Number(typedMileage.replace(/[,\s]/g, "")) : null} /> : null)}
    {financing && <fieldset><legend>{t.financing}</legend>
      <div className="segmented">
        <label><input type="radio" name="hasOwn" required checked={hasOwn === "YES"} onChange={() => setHasOwn("YES")} />{common.yes}</label>
        <label><input type="radio" name="hasOwn" required checked={hasOwn === "NO"} onChange={() => setHasOwn("NO")} />{common.no}</label>
      </div>
      {hasOwn === "YES" && <><label>{t.ownApr}<input name="ownApr" inputMode="decimal" required /></label>{field("ownApr")}</>}
    </fieldset>}
    {error && !error.errors?.length && <p className="error" role="alert">{error.message}</p>}
    <button type="submit" disabled={busy}>{busy ? common.checking : t.showResult}</button>
  </form>;
}

function ResultView({ result, headingRef, elapsed, onDeskCheck, onType, onClose }: {
  result: FirstResult; headingRef: RefObject<HTMLHeadingElement | null>; elapsed: { read?: number; result?: number };
  onDeskCheck: () => void; onType: () => void; onClose: () => void;
}) {
  const { m: { dealer: { atTheDealer: { result: t }, common } }, fill, language } = useMessages();
  result = localizeReport(result, language);
  const total = result.outTheDoor;
  // The lead is the first finding; the rest stay one tap away.
  const more = result.findings.slice(1);
  return <section className="panel dealer-result" aria-labelledby="dealer-result-heading" data-read-ms={elapsed.read} data-first-result-ms={elapsed.result}>
    <p className="step">{result.source === "TYPED_BY_BUYER" ? t.typedStep : t.readStep}</p>
    <h2 id="dealer-result-heading" ref={headingRef} tabIndex={-1}>
      {total.amountMinor !== null ? fill(t.outTheDoor, { amount: money(total.amountMinor) }) : t.noTotal}</h2>
    {total.differenceMinor !== null && total.agreedMinor !== null && <p className="dealer-result-delta">
      {total.differenceMinor === 0 ? fill(t.same, { agreed: money(total.agreedMinor) })
        : fill(total.differenceMinor > 0 ? t.more : t.less, { amount: money(total.differenceMinor), agreed: money(total.agreedMinor) })}</p>}
    {result.lead ? <div className="dealer-lead">
      <p className="dealer-lead-summary">{result.lead.summary}</p>
      <p className="dealer-question"><span className="dealer-question-label">{t.askAtDesk}</span>
        <q>{result.lead.deskQuestion}</q></p>
      {result.tacticFindings?.some(tactic => (tactic.code === result.lead?.code || tactic.tradeFindingCode === result.lead?.code) && tactic.questionReview === "PENDING_OPERATOR_REVIEW")
        && <p className="muted">{common.draftAwaitsReview}</p>}
    </div> : <p>{t.nothingStoodOut}</p>}
    {result.listingPrice && <ListingPriceNote check={result.listingPrice} title={common.listingTitle}
      question={!result.findings.some(finding => finding.code.startsWith("LISTING_"))} />}
    {/* Its questions are already among the findings to ask about. */}
    {result.governmentFees && <GovernmentFeeNote check={result.governmentFees} question={false} />}
    {/* Every add-on and extra dealer charge, named; added charges are also among the findings to ask about. */}
    {result.addOns && <DealerAddOnsNote check={result.addOns}
      question={!result.findings.some(finding => finding.code === "ADD_ON" || finding.code === "ADDED_CHARGE")} />}
    {more.length > 0 && <details>
      <summary>{fill(t.moreToAsk, { count: more.length })}</summary>
      <ul className="dealer-more">{more.map((finding, index) => <li key={`${finding.code}-${index}`}>
        <p>{finding.summary}</p><p className="muted"><q>{finding.deskQuestion}</q></p>
        {result.tacticFindings?.some(tactic => (tactic.code === finding.code || tactic.tradeFindingCode === finding.code) && tactic.questionReview === "PENDING_OPERATOR_REVIEW")
          && <p className="muted">{common.draftAwaitsReview}</p>}
      </li>)}</ul>
    </details>}
    <SigningNote />
    <div className="actions">
      {result.route === "AGREED_CAR" && <button type="button" onClick={onDeskCheck}>{t.openDeskCheck}</button>}
      {result.route === "THIS_CASE" && result.source === "READ_FROM_PAPERWORK" && <button type="button" onClick={() => {
        onClose();
        window.setTimeout(() => { const target = document.getElementById("verify-heading"); target?.scrollIntoView?.({ block: "start" }); target?.focus(); });
      }}>{t.checkTerms}</button>}
      {result.source === "READ_FROM_PAPERWORK" && <button type="button" className="btn-secondary" onClick={onType}>{common.typeInstead}</button>}
      <button type="button" className="btn-secondary" onClick={onClose}>{common.close}</button>
    </div>
  </section>;
}
