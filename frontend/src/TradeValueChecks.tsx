import { FormEvent, useId, useState } from "react";
import { toMinor, type Trade, type TradeEstimate, type TradeView } from "./TradeVehicle";
import * as format from "./i18n/format";

/**
 * Free checks the Buyer runs themselves on other sites (ticket 106). These are the sites' public pages with
 * nothing added: no referral or tracking parameters. otdog never queries or fetches these sites.
 */
export const VALUE_CHECKS = [
  { name: "Carfax", href: "https://www.carfax.com/value/", label: "Carfax value lookup",
    gives: "An estimate of what the car is worth, from its VIN or license plate. It is not an offer.",
    time: "About 2 minutes." },
  { name: "CarMax", href: "https://www.carmax.com/sell-my-car", label: "CarMax instant offer",
    gives: "A written offer to buy the car, good for a set number of days shown on the offer.",
    time: "About 10 to 15 minutes, with questions about the car's condition." },
] as const;

function money(minor: number) {
  return format.money(minor);
}
function day(iso: string) {
  const [year, month, date] = iso.split("-").map(Number);
  return format.date(Date.UTC(year, month - 1, date), { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
}
function today() {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}
/** "$14,000 to $16,000", or "$15,000" for a single value. */
export function estimateAmount(estimate: Pick<TradeEstimate, "lowMinor" | "highMinor">) {
  return estimate.highMinor === null ? money(estimate.lowMinor) : `${money(estimate.lowMinor)} to ${money(estimate.highMinor)}`;
}
/** A written offer typed into the estimate form belongs under "Add a written offer". */
const OFFER_SOURCE = /carmax|carvana|vroom|peddle|dealer|offer/i;

function CopyValue({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState<boolean>();
  async function copy() {
    try { await navigator.clipboard.writeText(value); setCopied(true); }
    catch { setCopied(false); }
  }
  return <div className="trade-copy">
    <dt>{label}</dt>
    <dd><span className={label === "VIN" ? "vin" : undefined}>{value}</span>
      <button type="button" className="btn-secondary" onClick={() => void copy()}>Copy {label === "VIN" ? "VIN" : label.toLowerCase()}</button>
      <span className="muted" role="status">{copied === true ? "Copied." : copied === false ? "Copy did not work; select the text instead." : ""}</span>
    </dd>
  </div>;
}

/** "Check what your car is worth": the links, what each gives and how long it takes, and the details to paste in. */
export function TradeValueLinks({ vin, mileage, headingLevel = 3 }: { vin?: string | null; mileage?: number | null; headingLevel?: 3 | 4 }) {
  const id = useId();
  const Heading = headingLevel === 3 ? "h3" : "h4";
  return <section className="trade-value-checks" aria-labelledby={`${id}-heading`}>
    <Heading id={`${id}-heading`}>Check what your car is worth</Heading>
    <p className="muted">Two free checks you can run yourself before the dealer makes an offer. They are outside estimates and
      offers that you gather; otdog does not look them up, check them or vouch for them, and does not send your car's
      details to either site.</p>
    <ul className="trade-value-links" aria-label="Free trade-in value checks">
      {VALUE_CHECKS.map(check => <li key={check.name}>
        <a href={check.href} target="_blank" rel="noopener noreferrer">{check.label}<span className="visually-hidden"> (opens in a new tab)</span></a>
        <p>{check.gives}</p>
        <p className="muted">{check.time}</p>
      </li>)}
    </ul>
    {(vin || mileage != null) && <dl className="trade-copy-values" aria-label="Details to paste into the other site">
      {vin && <CopyValue label="VIN" value={vin} />}
      {mileage != null && <CopyValue label="Mileage" value={String(mileage)} />}
    </dl>}
  </section>;
}

type Problem = Error & { errors?: { field: string; message: string }[] };

/** The links, then the estimates the Buyer typed back, which stand in for a value range until a licensed one exists. */
export function TradeValueChecks({ trade, path, request, onView }: { trade: Trade; path: string;
  request: (url: string, init?: RequestInit) => Promise<TradeView>; onView: (view: TradeView) => void }) {
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem>();
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [site, setSite] = useState<"CARFAX" | "OTHER">("CARFAX");
  const [otherName, setOtherName] = useState("");
  const estimates = trade.estimates ?? [];
  const fieldError = (field: string) => problem?.errors?.find(error => error.field === field)?.message;
  const described = (field: string) => fieldError(field) ? { "aria-invalid": true as const, "aria-describedby": `estimate-${field}-error` } : {};
  const errorFor = (field: string) => fieldError(field) && <p className="error field-error" id={`estimate-${field}-error`}>{fieldError(field)}</p>;

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const text = (name: string) => String(values.get(name) ?? "").trim();
    const low = toMinor(text("low")), high = text("high") ? toMinor(text("high")) : null;
    if (low === null || (text("high") && high === null)) {
      setProblem(Object.assign(new Error("Check the highlighted estimate details."), { errors: [
        ...(low === null ? [{ field: "lowMinor", message: "Enter the value in dollars, such as 15000." }] : []),
        ...(text("high") && high === null ? [{ field: "highMinor", message: "Enter the high end in dollars, such as 16500." }] : [])] }));
      return;
    }
    setBusy(true); setProblem(undefined);
    try {
      onView(await request(`${path}/estimates`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body: JSON.stringify({ source: site === "CARFAX" ? "Carfax" : otherName.trim(), lowMinor: low, highMinor: high,
          checkedOn: text("checkedOn") || null }) }));
      setAdding(false); setKey(crypto.randomUUID()); setSite("CARFAX"); setOtherName("");
    } catch (failure) { setProblem(failure as Problem); }
    finally { setBusy(false); }
  }
  async function remove(estimate: TradeEstimate) {
    setBusy(true); setProblem(undefined);
    try { onView(await request(`${path}/estimates/${encodeURIComponent(estimate.id)}`, { method: "DELETE" })); }
    catch (failure) { setProblem(failure as Problem); }
    finally { setBusy(false); }
  }

  return <>
    <TradeValueLinks vin={trade.vehicle.vin} mileage={trade.mileage} />
    <section className="trade-estimates" aria-labelledby="trade-estimates-heading">
      <h3 id="trade-estimates-heading">Estimates you looked up</h3>
      <p className="muted">Type back what Carfax or another value site showed you. An estimate is not an offer, so it is
        never shown as something to take to the dealer. When no licensed value range is available, the trade-in check
        compares the dealer's allowance with your most recent estimate and names where and when you looked it up.</p>
      {estimates.length > 0 && <ul className="trade-offer-list" aria-label="Your trade-in estimates">
        {estimates.map(estimate => <li key={estimate.id}>
          <p className="trade-offer-amount">{estimateAmount(estimate)}</p>
          <p className="muted">Your {estimate.source} estimate from {day(estimate.checkedOn)}.</p>
          <p className="desk-check-meta">
            <span className="badge">Estimate, not an offer</span>
            {estimate.old && <span className="badge badge-warn">Old: looked up more than 30 days ago</span>}
            {estimate.id === trade.estimateInUseId && estimates.length > 1 && <span>The most recent one.</span>}
          </p>
          <div className="trade-actions">
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove(estimate)}>
              Remove {estimate.source} estimate</button>
          </div>
        </li>)}
      </ul>}
      {!adding && <button type="button" className="btn-secondary" onClick={() => setAdding(true)}>Add an estimate I looked up</button>}
      {adding && <form className="trade-form" onSubmit={event => void add(event)} noValidate aria-label="Trade-in estimate">
        <fieldset className="trade-choices trade-owed" aria-describedby={fieldError("source") ? "estimate-source-error" : undefined}>
          <legend>Where did you look it up?</legend>
          <label><input type="radio" name="site" value="CARFAX" checked={site === "CARFAX"} onChange={() => setSite("CARFAX")} />
            <span><strong>Carfax</strong></span></label>
          <label><input type="radio" name="site" value="OTHER" checked={site === "OTHER"} onChange={() => setSite("OTHER")} />
            <span><strong>Another site</strong></span></label>
        </fieldset>
        {site === "OTHER" && <label>Site name<input name="source" autoComplete="off" maxLength={120} value={otherName}
          onChange={event => setOtherName(event.target.value)} {...described("source")} /></label>}
        {errorFor("source")}
        {site === "OTHER" && OFFER_SOURCE.test(otherName) && <p className="notice" role="status">A CarMax, Carvana or dealer
          offer is a written offer, not an estimate. Add it under “Add a written offer” below, with its expiry and a photo
          or PDF of it.</p>}
        <label>Value, or the low end of its range ($)<input name="low" inputMode="decimal" autoComplete="off" {...described("lowMinor")} /></label>
        {errorFor("lowMinor")}
        <label>High end of the range, if it showed one ($)<input name="high" inputMode="decimal" autoComplete="off"
          {...described("highMinor")} /></label>
        {errorFor("highMinor")}
        <label>Date you looked it up<input name="checkedOn" type="date" defaultValue={today()} {...described("checkedOn")} /></label>
        {errorFor("checkedOn")}
        <div className="trade-actions">
          <button type="submit" disabled={busy}>{busy ? "Adding…" : "Add estimate"}</button>
          <button type="button" className="btn-secondary" onClick={() => { setAdding(false); setProblem(undefined); }}>Cancel</button>
        </div>
      </form>}
      {problem && <p role="alert">{problem.message}</p>}
    </section>
  </>;
}
