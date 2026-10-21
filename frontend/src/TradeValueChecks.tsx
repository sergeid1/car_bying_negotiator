import { FormEvent, useId, useState } from "react";
import { toMinor, type Trade, type TradeEstimate, type TradeView } from "./TradeVehicle";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

/**
 * Free checks the Buyer runs themselves on other sites (ticket 106). These are the sites' public pages with
 * nothing added: no referral or tracking parameters. otdog never queries or fetches these sites.
 */
export const VALUE_CHECKS = [
  { id: "carfax", name: "Carfax", href: "https://www.carfax.com/value/" },
  { id: "carmax", name: "CarMax", href: "https://www.carmax.com/sell-my-car" },
] as const;
/** The site the estimate form offers first; its brand name is the same in every language. */
const CARFAX = VALUE_CHECKS[0].name;

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
/** A written offer typed into the estimate form belongs under "Add a written offer". */
const OFFER_SOURCE = /carmax|carvana|vroom|peddle|dealer|offer/i;

function CopyValue({ label, copyLabel, value, vin = false }: { label: string; copyLabel: string; value: string; vin?: boolean }) {
  const { m: { dealer: { tradeValueChecks: t } } } = useMessages();
  const [copied, setCopied] = useState<boolean>();
  async function copy() {
    try { await navigator.clipboard.writeText(value); setCopied(true); }
    catch { setCopied(false); }
  }
  return <div className="trade-copy">
    <dt>{label}</dt>
    <dd><span className={vin ? "vin" : undefined}>{value}</span>
      <button type="button" className="btn-secondary" onClick={() => void copy()}>{copyLabel}</button>
      <span className="muted" role="status">{copied === true ? t.copied : copied === false ? t.copyFailed : ""}</span>
    </dd>
  </div>;
}

/** "Check what your car is worth": the links, what each gives and how long it takes, and the details to paste in. */
export function TradeValueLinks({ vin, mileage, headingLevel = 3 }: { vin?: string | null; mileage?: number | null; headingLevel?: 3 | 4 }) {
  const { m: { dealer: { tradeValueChecks: t, common } } } = useMessages();
  const id = useId();
  const Heading = headingLevel === 3 ? "h3" : "h4";
  return <section className="trade-value-checks" aria-labelledby={`${id}-heading`}>
    <Heading id={`${id}-heading`}>{t.heading}</Heading>
    <p className="muted">{t.intro}</p>
    <ul className="trade-value-links" aria-label={t.links}>
      {VALUE_CHECKS.map(check => <li key={check.name}>
        <a href={check.href} target="_blank" rel="noopener noreferrer">{t.sites[check.id].label}<span className="visually-hidden"> {t.opensInNewTab}</span></a>
        <p>{t.sites[check.id].gives}</p>
        <p className="muted">{t.sites[check.id].time}</p>
      </li>)}
    </ul>
    {(vin || mileage != null) && <dl className="trade-copy-values" aria-label={t.pasteDetails}>
      {vin && <CopyValue label={common.vin} copyLabel={t.copyVin} value={vin} vin />}
      {mileage != null && <CopyValue label={common.mileage} copyLabel={t.copyMileage} value={String(mileage)} />}
    </dl>}
  </section>;
}

type Problem = Error & { errors?: { field: string; message: string }[] };

/** The links, then the estimates the Buyer typed back, which stand in for a value range until a licensed one exists. */
export function TradeValueChecks({ trade, path, request, onView }: { trade: Trade; path: string;
  request: (url: string, init?: RequestInit) => Promise<TradeView>; onView: (view: TradeView) => void }) {
  const { m: { dealer: { tradeValueChecks: t, common } }, fill } = useMessages();
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
  /** "$14,000 to $16,000", or "$15,000" for a single value. */
  const estimateAmount = (estimate: Pick<TradeEstimate, "lowMinor" | "highMinor">) => estimate.highMinor === null
    ? money(estimate.lowMinor) : fill(common.range, { low: money(estimate.lowMinor), high: money(estimate.highMinor) });

  async function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const text = (name: string) => String(values.get(name) ?? "").trim();
    const low = toMinor(text("low")), high = text("high") ? toMinor(text("high")) : null;
    if (low === null || (text("high") && high === null)) {
      setProblem(Object.assign(new Error(t.checkHighlighted), { errors: [
        ...(low === null ? [{ field: "lowMinor", message: t.lowError }] : []),
        ...(text("high") && high === null ? [{ field: "highMinor", message: t.highError }] : [])] }));
      return;
    }
    setBusy(true); setProblem(undefined);
    try {
      onView(await request(`${path}/estimates`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body: JSON.stringify({ source: site === "CARFAX" ? CARFAX : otherName.trim(), lowMinor: low, highMinor: high,
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
      <h3 id="trade-estimates-heading">{t.estimatesHeading}</h3>
      <p className="muted">{t.estimatesIntro}</p>
      {estimates.length > 0 && <ul className="trade-offer-list" aria-label={t.estimatesList}>
        {estimates.map(estimate => <li key={estimate.id}>
          <p className="trade-offer-amount">{estimateAmount(estimate)}</p>
          <p className="muted">{fill(t.yourEstimate, { source: estimate.source, date: day(estimate.checkedOn) })}</p>
          <p className="desk-check-meta">
            <span className="badge">{t.notAnOffer}</span>
            {estimate.old && <span className="badge badge-warn">{t.old}</span>}
            {estimate.id === trade.estimateInUseId && estimates.length > 1 && <span>{t.mostRecent}</span>}
          </p>
          <div className="trade-actions">
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => void remove(estimate)}>
              {fill(t.remove, { source: estimate.source })}</button>
          </div>
        </li>)}
      </ul>}
      {!adding && <button type="button" className="btn-secondary" onClick={() => setAdding(true)}>{t.add}</button>}
      {adding && <form className="trade-form" onSubmit={event => void add(event)} noValidate aria-label={t.form}>
        <fieldset className="trade-choices trade-owed" aria-describedby={fieldError("source") ? "estimate-source-error" : undefined}>
          <legend>{t.where}</legend>
          <label><input type="radio" name="site" value="CARFAX" checked={site === "CARFAX"} onChange={() => setSite("CARFAX")} />
            <span><strong>{CARFAX}</strong></span></label>
          <label><input type="radio" name="site" value="OTHER" checked={site === "OTHER"} onChange={() => setSite("OTHER")} />
            <span><strong>{t.anotherSite}</strong></span></label>
        </fieldset>
        {site === "OTHER" && <label>{t.siteName}<input name="source" autoComplete="off" maxLength={120} value={otherName}
          onChange={event => setOtherName(event.target.value)} {...described("source")} /></label>}
        {errorFor("source")}
        {site === "OTHER" && OFFER_SOURCE.test(otherName) && <p className="notice" role="status">{t.writtenOfferNotice}</p>}
        <label>{t.low}<input name="low" inputMode="decimal" autoComplete="off" {...described("lowMinor")} /></label>
        {errorFor("lowMinor")}
        <label>{t.high}<input name="high" inputMode="decimal" autoComplete="off"
          {...described("highMinor")} /></label>
        {errorFor("highMinor")}
        <label>{t.checkedOn}<input name="checkedOn" type="date" defaultValue={today()} {...described("checkedOn")} /></label>
        {errorFor("checkedOn")}
        <div className="trade-actions">
          <button type="submit" disabled={busy}>{busy ? common.adding : t.addEstimate}</button>
          <button type="button" className="btn-secondary" onClick={() => { setAdding(false); setProblem(undefined); }}>{common.cancel}</button>
        </div>
      </form>}
      {problem && <p role="alert">{problem.message}</p>}
    </section>
  </>;
}
