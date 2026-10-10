import { FormEvent, useEffect, useRef, useState } from "react";

type Credentials = { caseId: string; token: string };
type Condition = "EXCELLENT" | "GOOD" | "FAIR" | "POOR";
type Owed = "YES" | "NO" | "NOT_SURE";
type Evidence = { documentId: string; state: "PROCESSING" | "READY" | "FAILED" | "REMOVED"; pageCount: number | null };
export type TradeOffer = { id: string; amountMinor: number; source: string; offerDate: string; expiresOn: string;
  expired: boolean; usedForComparison: boolean; evidence: Evidence; createdAt: string };
export type Trade = {
  id: string; revision: number;
  vehicle: { vin: string; decodeOutcome: "DECODED" | "UNDECODABLE"; source: string; decoderVersion: string; decodedAt: string;
    year: string | null; make: string | null; model: string | null; trim: string | null };
  mileage: number; condition: Condition; moneyOwed: Owed | null; lender: string | null; payoffMinor: number | null;
  payoffGoodThrough: string | null; payoffUnknown: boolean; payoffStale: boolean; complete: boolean; changedAt: string;
  offers: TradeOffer[]; bestOfferId: string | null;
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

const OWED: { value: Owed; label: string }[] = [
  { value: "YES", label: "Yes" }, { value: "NO", label: "No" }, { value: "NOT_SURE", label: "I'm not sure" }];

function money(minor: number) {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(minor / 100);
}
function day(iso: string) {
  const [year, month, date] = iso.split("-").map(Number);
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" })
    .format(new Date(Date.UTC(year, month - 1, date)));
}
/** Dollars as typed, to whole cents; null when it is not an amount. */
export function toMinor(text: string) {
  const cleaned = text.replace(/[$,\s]/g, "");
  if (!/^\d+(\.\d{1,2})?$/.test(cleaned)) return null;
  return Math.round(Number(cleaned) * 100);
}
export function vehicleName(trade: Trade) {
  const v = trade.vehicle;
  const name = [v.year, v.make, v.model, v.trim].filter(Boolean).join(" ");
  return name || "VIN not identified by the decoder";
}

function asView(value: unknown): TradeView {
  const v = value as Partial<TradeView> | null;
  if (!v || (v.holder !== "JOURNEY" && v.holder !== "CASE") || !Array.isArray(v.conditions) || !v.analysis)
    throw new Problem("Your trade-in is unavailable. Try refreshing.");
  return v as TradeView;
}

/**
 * The car the Buyer is trading in, its payoff, and written trade offers the Buyer got themselves.
 * A case in a Buying Journey shares the journey's one trade-in. {@code carryFrom} is the earlier case
 * when this case was started at the Desk Check for another car, so the trade carries into it.
 */
export function TradeVehicle({ caseId, token, carryFrom }: Credentials & { carryFrom?: Credentials }) {
  const [view, setView] = useState<TradeView>();
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [busy, setBusy] = useState(false);
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/trade`;

  async function request(url: string, init: RequestInit = {}) {
    const response = await fetch(url, { ...init, headers: { "Case-Access-Token": token, ...(init.headers ?? {}) } });
    if (response.status === 204) throw new Problem("Your trade-in is unavailable. Try refreshing.", [], 204);
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new Problem(body?.detail ?? "Your trade-in could not be saved. Try again.",
      Array.isArray(body?.errors) ? body.errors : [], response.status);
    return asView(body);
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
    <h2 id="trade-heading">Your trade-in</h2><p role="alert">{error}</p></section> : null;
  const trade = view.trade;

  async function remove() {
    setBusy(true); setError(undefined);
    try { setView(await request(path, { method: "DELETE" })); setRemoving(false); setEditing(false); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(false); }
  }

  return <section className="panel trade-vehicle" aria-labelledby="trade-heading">
    <div className="section-heading"><div>
      <p className="step">Trade-in</p>
      <h2 id="trade-heading">Your trade-in</h2>
    </div></div>
    <p>If you are trading a car in, record it once. It is used only to check your own deal. otdog does not buy cars,
      does not ask anyone for trade offers, and does not send your trade-in details to a dealer or to anyone else.</p>
    <p className="muted">{view.holder === "JOURNEY"
      ? "This trade-in belongs to your Buying Journey, so every case in the journey uses the same one."
      : "This case holds its own trade-in."}</p>
    {trade && view.analysis.outdated && view.analysis.analyzedAt && <p role="alert">Your trade-in changed after this case was
      analyzed on {new Date(view.analysis.analyzedAt).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" })},
      so that analysis is outdated. Analyze the deal again to use the trade-in as it is now.</p>}
    {!trade && !editing && <button type="button" onClick={() => setEditing(true)}>Add the car I'm trading in</button>}
    {trade && !editing && <TradeSummary trade={trade} view={view} />}
    {trade && !editing && <div className="trade-actions">
      <button type="button" className="btn-secondary" onClick={() => setEditing(true)}>Edit trade-in</button>
      {!removing && <button type="button" className="btn-secondary" onClick={() => setRemoving(true)}>I'm not trading it in</button>}
      {removing && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove()}>Remove the trade-in and its offers</button>}
    </div>}
    {editing && <TradeForm view={view} busy={busy} onCancel={() => setEditing(false)} onSave={async input => {
      setBusy(true); setError(undefined);
      try {
        setView(await request(path, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input) }));
        setEditing(false);
      } finally { setBusy(false); }
    }} />}
    {error && <p role="alert">{error}</p>}
    {trade && <TradeOffers trade={trade} path={path} token={token} request={request} onView={setView} />}
  </section>;
}

function TradeSummary({ trade, view }: { trade: Trade; view: TradeView }) {
  const condition = view.conditions.find(option => option.value === trade.condition);
  return <>
    <dl className="facts trade-facts">
      <div><dt>Car</dt><dd>{vehicleName(trade)}</dd></div>
      <div><dt>VIN</dt><dd className="vin">{trade.vehicle.vin}</dd></div>
      <div><dt>Mileage</dt><dd>{trade.mileage.toLocaleString("en-US")} mi</dd></div>
      <div><dt>Condition</dt><dd>{condition?.label ?? trade.condition}</dd></div>
      <div><dt>Money owed</dt><dd>{trade.moneyOwed === null ? "Not answered yet"
        : OWED.find(option => option.value === trade.moneyOwed)!.label}</dd></div>
      {trade.moneyOwed === "YES" && <div><dt>Lender</dt><dd>{trade.lender}</dd></div>}
      {trade.moneyOwed !== "NO" && trade.moneyOwed !== null && <div><dt>Payoff</dt><dd>{trade.payoffMinor !== null && trade.payoffGoodThrough
        ? `${money(trade.payoffMinor)}, good through ${day(trade.payoffGoodThrough)}` : "Unknown"}</dd></div>}
    </dl>
    {trade.vehicle.decodeOutcome === "UNDECODABLE" && <p className="notice">The VIN decoder did not identify this VIN, so no
      year, make, model or trim is shown. Check the VIN against the car or its title.</p>}
    {!trade.complete && <p className="notice">Answer “Do you still owe money on it?” to complete your trade-in.</p>}
    {trade.payoffStale && trade.payoffGoodThrough && <p role="alert">Stale payoff: the good-through date, {day(trade.payoffGoodThrough)},
      has passed. Ask {trade.lender} for a new payoff quote and update it here.</p>}
    {trade.payoffUnknown && <p className="notice">The payoff is unknown, not zero. Ask your lender for a payoff quote; it states
      the amount and the date it is good through.</p>}
  </>;
}

type Input = { basedOnRevision: number | null; vin: string; mileage: number | null; condition: Condition | null; moneyOwed: Owed | null;
  lender: string | null; payoffMinor: number | null; payoffGoodThrough: string | null; payoffUnknown: boolean };

function TradeForm({ view, busy, onSave, onCancel }: { view: TradeView; busy: boolean; onCancel: () => void;
  onSave: (input: Input) => Promise<void> }) {
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
      setProblem(new Problem("Check the highlighted trade-in details.",
        [{ field: "payoffMinor", message: "Enter the payoff amount in dollars and cents, such as 18250.00." }]));
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

  return <form className="trade-form" onSubmit={event => void submit(event)} noValidate aria-label="Trade-in details">
    <label>VIN<input name="vin" defaultValue={trade?.vehicle.vin ?? ""} autoCapitalize="characters" autoComplete="off"
      spellCheck={false} maxLength={17} {...described("vin")} /></label>
    {errorFor("vin")}
    <label>Mileage<input name="mileage" inputMode="numeric" autoComplete="off" defaultValue={trade ? String(trade.mileage) : ""}
      {...described("mileage")} /></label>
    {errorFor("mileage")}
    <fieldset className="trade-choices" aria-describedby={fieldError("condition") ? "trade-condition-error" : undefined}>
      <legend>Condition</legend>
      {view.conditions.map(option => <label key={option.value}>
        <input type="radio" name="condition" value={option.value} defaultChecked={trade?.condition === option.value} />
        <span><strong>{option.label}</strong><span className="trade-choice-detail">{option.description}</span></span>
      </label>)}
    </fieldset>
    {errorFor("condition")}
    <fieldset className="trade-choices trade-owed" aria-describedby={fieldError("moneyOwed") ? "trade-moneyOwed-error" : undefined}>
      <legend>Do you still owe money on it?</legend>
      {OWED.map(option => <label key={option.value}>
        <input type="radio" name="owed" value={option.value} checked={owed === option.value} onChange={() => setOwed(option.value)} />
        <span><strong>{option.label}</strong></span>
      </label>)}
    </fieldset>
    {errorFor("moneyOwed")}
    {owed === "YES" && <>
      <p className="muted trade-hint">Ask your lender for a payoff quote. It states the amount and the date it is good through;
        the amount changes as interest adds up, so a quote from the last few days is best.</p>
      <label>Lender<input name="lender" autoComplete="off" maxLength={120} defaultValue={trade?.lender ?? ""} {...described("lender")} /></label>
      {errorFor("lender")}
      <label><input type="checkbox" checked={unknown} onChange={event => setUnknown(event.target.checked)} />I don't know the payoff yet</label>
      {!unknown && <>
        <label>Payoff amount ($)<input name="payoff" inputMode="decimal" autoComplete="off"
          defaultValue={trade?.payoffMinor ? (trade.payoffMinor / 100).toFixed(2) : ""} {...described("payoffMinor")} /></label>
        {errorFor("payoffMinor")}
        <label>Good through<input name="goodThrough" type="date" defaultValue={trade?.payoffGoodThrough ?? ""}
          {...described("payoffGoodThrough")} /></label>
        {errorFor("payoffGoodThrough")}
      </>}
    </>}
    {owed === "NOT_SURE" && <p className="muted trade-hint">Check your loan statement or ask the lender. Until then the payoff
      stays unknown, not zero.</p>}
    {problem && <p role="alert" tabIndex={-1} ref={summary}>{problem.message}</p>}
    <div className="trade-actions">
      <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save trade-in"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </form>;
}

function TradeOffers({ trade, path, token, request, onView }: { trade: Trade; path: string; token: string;
  request: (url: string, init?: RequestInit) => Promise<TradeView>; onView: (view: TradeView) => void }) {
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
    if (!response.ok) { setProblem(new Problem("The offer's page could not be shown. Try again.")); return; }
    setShown({ id: offer.id, url: URL.createObjectURL(await response.blob()) });
  }

  return <section className="trade-offers" aria-labelledby="trade-offers-heading">
    <h3 id="trade-offers-heading">Written trade offers you got</h3>
    <p className="muted">Add an offer you got in writing yourself, such as from a car-buying service or another dealer, with a
      photo or PDF of it. otdog never requests, arranges, sells or refers trade offers. Only an unexpired offer is used to
      compare a dealer's trade allowance.</p>
    {trade.offers.length > 0 && <ul className="trade-offer-list" aria-label="Your written trade offers">
      {trade.offers.map(offer => <li key={offer.id}>
        <p className="trade-offer-amount">{money(offer.amountMinor)} from {offer.source}</p>
        <p className="muted">Dated {day(offer.offerDate)}. {offer.expired ? "Expired" : "Expires"} {day(offer.expiresOn)}.</p>
        <p className="desk-check-meta">
          {offer.expired ? <span className="badge badge-warn">Expired: not used for comparison</span>
            : offer.id === trade.bestOfferId ? <span className="cash-badge">Best unexpired offer</span> : null}
          <span>{offer.evidence.state === "READY" ? "Offer page stored." : offer.evidence.state === "PROCESSING"
            ? "Checking the offer page…" : offer.evidence.state === "FAILED" ? "The offer page could not be processed."
            : "The offer page was removed under document retention."}</span>
        </p>
        <div className="trade-actions">
          {offer.evidence.state === "READY" && shown?.id !== offer.id && <button type="button" className="btn-secondary"
            onClick={() => void show(offer)}>Show the offer page</button>}
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove(offer)}>
            Remove offer from {offer.source}</button>
        </div>
        {shown?.id === offer.id && <img className="trade-offer-page" src={shown.url} alt={`Page 1 of the written offer from ${offer.source}`} />}
      </li>)}
    </ul>}
    {!adding && <button type="button" className="btn-secondary" onClick={() => setAdding(true)}>Add a written offer</button>}
    {adding && <form className="trade-form" onSubmit={event => void add(event)} noValidate aria-label="Written trade offer">
      <label>Amount on the offer ($)<input name="amount" inputMode="decimal" autoComplete="off" {...described("amountMinor")} /></label>
      {errorFor("amountMinor")}
      <label>Who made the offer<input name="source" autoComplete="off" maxLength={120} {...described("source")} /></label>
      {errorFor("source")}
      <label>Offer date<input name="offerDate" type="date" {...described("offerDate")} /></label>
      {errorFor("offerDate")}
      <label>Expires<input name="expiresOn" type="date" {...described("expiresOn")} /></label>
      {errorFor("expiresOn")}
      <label>Photo or PDF of the offer<input name="file" type="file" ref={fileInput} accept="application/pdf,image/jpeg,image/png,image/heic"
        {...described("file")} /></label>
      {errorFor("file")}
      <div className="trade-actions">
        <button type="submit" disabled={busy}>{busy ? "Adding…" : "Add written offer"}</button>
        <button type="button" className="btn-secondary" onClick={() => { setAdding(false); setProblem(undefined); }}>Cancel</button>
      </div>
    </form>}
    {problem && <p role="alert">{problem.message}</p>}
  </section>;
}
