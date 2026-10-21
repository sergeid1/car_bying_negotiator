import { FormEvent, useEffect, useRef, useState } from "react";
import { TradeValueChecks } from "./TradeValueChecks";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Credentials = { caseId: string; token: string };
type Condition = "EXCELLENT" | "GOOD" | "FAIR" | "POOR";
type Owed = "YES" | "NO" | "NOT_SURE";
type Evidence = { documentId: string; state: "PROCESSING" | "READY" | "FAILED" | "REMOVED"; pageCount: number | null };
export type TradeOffer = { id: string; amountMinor: number; source: string; offerDate: string; expiresOn: string;
  expired: boolean; usedForComparison: boolean; evidence: Evidence; createdAt: string };
/** A value the Buyer looked up themselves (ticket 106); highMinor is null for a single value. Never an offer. */
export type TradeEstimate = { id: string; source: string; lowMinor: number; highMinor: number | null; checkedOn: string;
  old: boolean; createdAt: string };
export type Trade = {
  id: string; revision: number;
  vehicle: { vin: string; decodeOutcome: "DECODED" | "UNDECODABLE"; source: string; decoderVersion: string; decodedAt: string;
    year: string | null; make: string | null; model: string | null; trim: string | null };
  mileage: number; condition: Condition; moneyOwed: Owed | null; lender: string | null; payoffMinor: number | null;
  payoffGoodThrough: string | null; payoffUnknown: boolean; payoffStale: boolean; complete: boolean; changedAt: string;
  offers: TradeOffer[]; bestOfferId: string | null;
  estimates?: TradeEstimate[]; estimateInUseId?: string | null;
};
export type TradeView = {
  holder: "JOURNEY" | "CASE"; trade: Trade | null;
  analysis: { analyzedAt: string | null; outdated: boolean };
  conditions: { value: Condition; label: string; description: string }[];
};
type FieldError = { field: string; message: string };
class Problem extends Error {
  constructor(message: string, readonly errors: FieldError[] = [], readonly status = 0) { super(message); }
}

const OWED: readonly Owed[] = ["YES", "NO", "NOT_SURE"];

function money(minor: number) {
  return format.money(minor);
}
function day(iso: string) {
  const [year, month, date] = iso.split("-").map(Number);
  return format.date(Date.UTC(year, month - 1, date), { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
}
/** Dollars as typed, to whole cents; null when it is not an amount. */
export function toMinor(text: string) {
  const cleaned = text.replace(/[$,\s]/g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(Number(cleaned) * 100);
}
/** The car's year, make, model and trim; `unidentified` when the decoder named none of them. */
export function vehicleName(trade: Trade, unidentified: string) {
  const v = trade.vehicle;
  const name = [v.year, v.make, v.model, v.trim].filter(Boolean).join(" ");
  return name || unidentified;
}

function asView(value: unknown, unavailable: string): TradeView {
  const v = value as Partial<TradeView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !Array.isArray(v.conditions) || !v.analysis)
    throw new Problem(unavailable);
  return v as TradeView;
}

/**
 * The car the Buyer is trading in, its payoff, and written trade offers the Buyer got themselves.
 * A case in a Buying Journey shares the journey's one trade-in. {@code carryFrom} is the earlier case
 * when this case was started at the Desk Check for another car, so the trade carries into it.
 */
export function TradeVehicle({ caseId, token, carryFrom }: Credentials & { carryFrom?: Credentials }) {
  const { m: { dealer: { tradeVehicle: t } }, fill } = useMessages();
  const [view, setView] = useState<TradeView>();
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/trade`;

  async function request(url: string, init: RequestInit = {}) {
    const response = await fetch(url, { ...init, headers: { "Case-Access-Token": token, ...(init.headers ?? {}) } });
    if (response.status === 204) throw new Problem(t.unavailable, [], 204);
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Problem(body?.detail ?? t.saveFailed,
      Array.isArray(body?.errors) ? body.errors : [], response.status);
    return asView(body, t.unavailable);
  }

  useEffect(() => {
    let current = true;
    const load = carryFrom
      ? request(`${path}/carry-over`, { method: "POST", headers: { "Content-Type": "application/json",
        "Source-Case-Access-Token": carryFrom.token }, body: JSON.stringify({ sourceCaseId: carryFrom.caseId }) })
        .catch(() => request(path))
      : request(path);
    load.then(value => { if (current) setView(value); }).catch(failure => {
      // A case this panel cannot reach shows nothing rather than a second error beside the case's own.
      if (current && !(failure instanceof Problem && [204, 404].includes(failure.status))) setError((failure as Error).message);
    });
    return () => { current = false; };
    // The case, its credential and the case it was started from define this trade-in.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, token, carryFrom?.caseId]);

  if (!view) return error ? <section className="panel trade-vehicle" aria-labelledby="trade-heading">
    <h2 id="trade-heading">{t.heading}</h2><p role="alert">{error}</p></section> : null;
  const trade = view.trade;

  async function remove() {
    setBusy(true); setError(undefined);
    try { setView(await request(path, { method: "DELETE" })); setRemoving(false); setEditing(false); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  return <section className="panel trade-vehicle" aria-labelledby="trade-heading">
    <div className="section-heading"><div>
      <p className="step">{t.step}</p>
      <h2 id="trade-heading">{t.heading}</h2>
    </div></div>
    <p>{t.intro}</p>
    <p className="muted">{view.holder === "JOURNEY" ? t.journeyHolder : t.caseHolder}</p>
    {trade && view.analysis.outdated && view.analysis.analyzedAt && <p role="alert">{fill(t.outdated,
      { date: format.date(view.analysis.analyzedAt, { month: "short", day: "numeric", year: "numeric" }) })}</p>}
    {!trade && !editing && <button type="button" onClick={() => setEditing(true)}>{t.addCar}</button>}
    {trade && !editing && <TradeSummary trade={trade} view={view} />}
    {trade && !editing && <div className="trade-actions">
      <button type="button" className="btn-secondary" onClick={() => setEditing(true)}>{t.edit}</button>
      {!removing && <button type="button" className="btn-secondary" onClick={() => setRemoving(true)}>{t.notTrading}</button>}
      {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove()}>{t.removeAll}</button>}
    </div>}
    {editing && <TradeForm view={view} busy={busy} onCancel={() => setEditing(false)} onSave={async input => {
      setBusy(true); setError(undefined);
      try {
        setView(await request(path, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) }));
        setEditing(false);
      } finally { setBusy(false); }
    }} />}
    {error && <p role="alert">{error}</p>}
    {trade && <TradeValueChecks trade={trade} path={path} request={request} onView={setView} />}
    {trade && <TradeOffers trade={trade} path={path} token={token} request={request} onView={setView} />}
  </section>;
}

/** The Buyer's answer to "Do you still owe money on it?", in the catalogue's words. */
function owedLabel(common: ReturnType<typeof useMessages>["m"]["dealer"]["common"], owed: Owed) {
  return owed === "YES" ? common.yes : owed === "NO" ? common.no : common.notSure;
}

function TradeSummary({ trade, view }: { trade: Trade; view: TradeView }) {
  const { m: { dealer: { tradeVehicle: t, common } }, fill } = useMessages();
  const condition = view.conditions.find(option => option.value === trade.condition);
  return <>
    <dl className="facts trade-facts">
      <div><dt>{t.car}</dt><dd>{vehicleName(trade, t.unidentified)}</dd></div>
      <div><dt>{common.vin}</dt><dd className="vin">{trade.vehicle.vin}</dd></div>
      <div><dt>{common.mileage}</dt><dd>{fill(t.mileageValue, { miles: format.number(trade.mileage) })}</dd></div>
      <div><dt>{common.condition}</dt><dd>{condition?.label ?? trade.condition}</dd></div>
      <div><dt>{t.moneyOwed}</dt><dd>{trade.moneyOwed === null ? t.notAnswered : owedLabel(common, trade.moneyOwed)}</dd></div>
      {trade.moneyOwed === "YES" && <div><dt>{common.lender}</dt><dd>{trade.lender}</dd></div>}
      {trade.moneyOwed !== "NO" && trade.moneyOwed !== null && <div><dt>{t.payoff}</dt><dd>{trade.payoffMinor !== null && trade.payoffGoodThrough
        ? fill(t.payoffValue, { amount: money(trade.payoffMinor), date: day(trade.payoffGoodThrough) }) : t.unknown}</dd></div>}
    </dl>
    {trade.vehicle.decodeOutcome === "UNDECODABLE" && <p className="notice">{t.undecodable}</p>}
    {!trade.complete && <p className="notice">{t.incomplete}</p>}
    {trade.payoffStale && trade.payoffGoodThrough && <p role="alert">{fill(t.stale,
      { date: day(trade.payoffGoodThrough), lender: trade.lender ?? "" })}</p>}
    {trade.payoffUnknown && <p className="notice">{t.payoffUnknown}</p>}
  </>;
}

type Input = { basedOnRevision: number | null; vin: string; mileage: number | null; condition: Condition | null; moneyOwed: Owed | null;
  lender: string | null; payoffMinor: number | null; payoffGoodThrough: string | null; payoffUnknown: boolean };

function TradeForm({ view, busy, onSave, onCancel }: { view: TradeView; busy: boolean; onCancel: () => void;
  onSave: (input: Input) => Promise<void> }) {
  const { m: { dealer: { tradeVehicle: t, common } } } = useMessages();
  const trade = view.trade;
  const [owed, setOwed] = useState<Owed | null>(trade?.moneyOwed ?? null);
  const [unknown, setUnknown] = useState(Boolean(trade && trade.moneyOwed === "YES" && trade.payoffMinor === null));
  const [problem, setProblem] = useState<Problem>();
  const summary = useRef<HTMLParagraphElement>(null);
  const fieldError = (field: string) => problem?.errors.find(error => error.field === field)?.message;
  const described = (field: string) => fieldError(field) ? { "aria-invalid": true as const, "aria-describedby": `trade-${field}-error` } : {};
  const errorFor = (field: string) => fieldError(field) && <p className="error field-error" id={`trade-${field}-error`}>{fieldError(field)}</p>;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const text = (name: string) => String(values.get(name) ?? "").trim();
    const mileage = text("mileage").replace(/[,\s]/g, "");
    const payoff = text("payoff");
    const payoffMinor = payoff ? toMinor(payoff) : null;
    if (owed === "YES" && !unknown && payoff && payoffMinor === null) {
      setProblem(new Problem(t.checkHighlighted, [{ field: "payoffMinor", message: t.payoffError }]));
      return;
    }
    setProblem(undefined);
    try {
      await onSave({ basedOnRevision: trade?.revision ?? null, vin: text("vin"),
        mileage: /^\d+$/.test(mileage) ? Number(mileage) : null,
        condition: (values.get("condition") as Condition | null) ?? null, moneyOwed: owed,
        lender: owed === "YES" ? text("lender") || null : null,
        payoffMinor: owed === "YES" && !unknown ? payoffMinor : null,
        payoffGoodThrough: owed === "YES" && !unknown ? text("goodThrough") || null : null,
        payoffUnknown: owed === "YES" && unknown });
    } catch (failure) {
      setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message));
      requestAnimationFrame(() => summary.current?.focus());
    }
  }

  return <form className="trade-form" onSubmit={event => void submit(event)} noValidate aria-label={t.form}>
    <label>{common.vin}<input name="vin" defaultValue={trade?.vehicle.vin ?? ""} autoCapitalize="characters" autoComplete="off"
      spellCheck={false} maxLength={17} {...described("vin")} /></label>
    {errorFor("vin")}
    <label>{common.mileage}<input name="mileage" inputMode="numeric" autoComplete="off" defaultValue={trade ? String(trade.mileage) : ""}
      {...described("mileage")} /></label>
    {errorFor("mileage")}
    <fieldset className="trade-choices" aria-describedby={fieldError("condition") ? "trade-condition-error" : undefined}>
      <legend>{common.condition}</legend>
      {view.conditions.map(option => <label key={option.value}>
        <input type="radio" name="condition" value={option.value} defaultChecked={trade?.condition === option.value} />
        <span><strong>{option.label}</strong><span className="trade-choice-detail">{option.description}</span></span>
      </label>)}
    </fieldset>
    {errorFor("condition")}
    <fieldset className="trade-choices trade-owed" aria-describedby={fieldError("moneyOwed") ? "trade-moneyOwed-error" : undefined}>
      <legend>{common.owedQuestion}</legend>
      {OWED.map(option => <label key={option}>
        <input type="radio" name="owed" value={option} checked={owed === option} onChange={() => setOwed(option)} />
        <span><strong>{owedLabel(common, option)}</strong></span>
      </label>)}
    </fieldset>
    {errorFor("moneyOwed")}
    {owed === "YES" && <>
      <p className="muted trade-hint">{t.payoffHint}</p>
      <label>{common.lender}<input name="lender" autoComplete="off" maxLength={120} defaultValue={trade?.lender ?? ""} {...described("lender")} /></label>
      {errorFor("lender")}
      <label><input type="checkbox" checked={unknown} onChange={event => setUnknown(event.target.checked)} />{common.payoffUnknownYet}</label>
      {!unknown && <>
        <label>{t.payoffAmount}<input name="payoff" inputMode="decimal" autoComplete="off"
          defaultValue={trade?.payoffMinor ? (trade.payoffMinor / 100).toFixed(2) : ""} {...described("payoffMinor")} /></label>
        {errorFor("payoffMinor")}
        <label>{common.goodThrough}<input name="goodThrough" type="date" defaultValue={trade?.payoffGoodThrough ?? ""}
          {...described("payoffGoodThrough")} /></label>
        {errorFor("payoffGoodThrough")}
      </>}
    </>}
    {owed === "NOT_SURE" && <p className="muted trade-hint">{t.notSureHint}</p>}
    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="trade-actions">
      <button type="submit" disabled={busy}>{busy ? t.saving : t.save}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>{common.cancel}</button>
    </div>
  </form>;
}

function TradeOffers({ trade, path, token, request, onView }: { trade: Trade; path: string; token: string;
  request: (url: string, init?: RequestInit) => Promise<TradeView>; onView: (view: TradeView) => void }) {
  const { m: { dealer: { tradeVehicle: { offers: t }, common } }, fill } = useMessages();
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem>();
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [shown, setShown] = useState<{ id: string; url: string }>();
  const fileInput = useRef<HTMLInputElement>(null);
  const fieldError = (field: string) => problem?.errors.find(error => error.field === field)?.message;
  const described = (field: string) => fieldError(field) ? { "aria-invalid": true as const, "aria-describedby": `offer-${field}-error` } : {};
  const errorFor = (field: string) => fieldError(field) && <p className="error field-error" id={`offer-${field}-error`}>{fieldError(field)}</p>;
  useEffect(() => () => { if (shown) URL.revokeObjectURL(shown.url); }, [shown]);
  const processing = trade.offers.some(offer => offer.evidence.state === "PROCESSING");
  useEffect(() => {
    if (!processing) return;
    const timer = window.setTimeout(() => { request(path).then(onView).catch(() => undefined); }, 1500);
    return () => window.clearTimeout(timer);
    // Poll while any evidence is still being processed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [processing, trade]);

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const amount = toMinor(String(values.get("amount") ?? ""));
    const body = new FormData();
    body.set("amountMinor", amount === null ? "" : String(amount));
    for (const name of ["source", "offerDate", "expiresOn"]) body.set(name, String(values.get(name) ?? "").trim());
    const file = fileInput.current?.files?.[0];
    if (file && file.size > 0) body.set("file", file);
    setBusy(true); setProblem(undefined);
    try {
      onView(await request(`${path}/offers`, { method: "POST", headers: { "Idempotency-Key": key }, body }));
      setAdding(false); setKey(crypto.randomUUID());
    } catch (failure) { setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message)); }
    finally { setBusy(false); }
  }
  async function remove(offer: TradeOffer) {
    setBusy(true); setProblem(undefined);
    try { onView(await request(`${path}/offers/${encodeURIComponent(offer.id)}`, { method: "DELETE" })); }
    catch (failure) { setProblem(failure instanceof Problem ? failure : new Problem((failure as Error).message)); }
    finally { setBusy(false); }
  }
  async function show(offer: TradeOffer) {
    if (typeof URL.createObjectURL !== "function") return;
    const response = await fetch(`${path}/offers/${encodeURIComponent(offer.id)}/pages/1/image`, { headers: { "Case-Access-Token": token } });
    if (!response.ok) { setProblem(new Problem(t.pageFailed)); return; }
    setShown({ id: offer.id, url: URL.createObjectURL(await response.blob()) });
  }

  return <section className="trade-offers" aria-labelledby="trade-offers-heading">
    <h3 id="trade-offers-heading">{t.heading}</h3>
    <p className="muted">{t.intro}</p>
    {trade.offers.length > 0 && <ul className="trade-offer-list" aria-label={t.list}>
      {trade.offers.map(offer => <li key={offer.id}>
        <p className="trade-offer-amount">{fill(t.amountFrom, { amount: money(offer.amountMinor), source: offer.source })}</p>
        <p className="muted">{fill(offer.expired ? t.datedExpired : t.datedExpires, { date: day(offer.offerDate), expires: day(offer.expiresOn) })}</p>
        <p className="desk-check-meta">
          {offer.expired ? <span className="badge badge-warn">{t.expired}</span>
            : offer.id === trade.bestOfferId ? <span className="cash-badge">{t.best}</span> : null}
          <span>{t.evidence[offer.evidence.state === "READY" || offer.evidence.state === "PROCESSING" || offer.evidence.state === "FAILED"
            ? offer.evidence.state : "REMOVED"]}</span>
        </p>
        <div className="trade-actions">
          {offer.evidence.state === "READY" && shown?.id !== offer.id && <button type="button" className="btn-secondary"
            onClick={() => void show(offer)}>{t.show}</button>}
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove(offer)}>
            {fill(t.remove, { source: offer.source })}</button>
        </div>
        {shown?.id === offer.id && <img className="trade-offer-page" src={shown.url} alt={fill(t.pageAlt, { source: offer.source })} />}
      </li>)}
    </ul>}
    {!adding && <button type="button" className="btn-secondary" onClick={() => setAdding(true)}>{t.add}</button>}
    {adding && <form className="trade-form" onSubmit={event => void add(event)} noValidate aria-label={t.form}>
      <label>{t.amount}<input name="amount" inputMode="decimal" autoComplete="off" {...described("amountMinor")} /></label>
      {errorFor("amountMinor")}
      <label>{t.who}<input name="source" autoComplete="off" maxLength={120} {...described("source")} /></label>
      {errorFor("source")}
      <label>{t.offerDate}<input name="offerDate" type="date" {...described("offerDate")} /></label>
      {errorFor("offerDate")}
      <label>{t.expires}<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
      {errorFor("expiresOn")}
      <label>{t.file}<input name="file" type="file" ref={fileInput} accept="application/pdf,image/jpeg,image/png,image/heic"
        {...described("file")} /></label>
      {errorFor("file")}
      <div className="trade-actions">
        <button type="submit" disabled={busy}>{busy ? common.adding : t.addOffer}</button>
        <button type="button" className="btn-secondary" onClick={() => { setAdding(false); setProblem(undefined); }}>{common.cancel}</button>
      </div>
    </form>}
    {problem && <p role="alert">{problem.message}</p>}
  </section>;
}
