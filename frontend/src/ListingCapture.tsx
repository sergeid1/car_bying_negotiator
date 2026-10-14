import { ChangeEvent, FormEvent, useEffect, useRef, useState } from "react";
import "./ListingCapture.css";
import { toMinor } from "./TradeVehicle";

type Credentials = { caseId: string; token: string };
type Region = { pageNumber: number; x: number; y: number; width: number; height: number };
export type ListingField = { field: string; originalWording: string; value: string | null; unit: string; evidence: Region; confidence: number };
export type ListingCaptureItem = {
  documentId: string; filename: string; mediaType: string; pageCount: number; uploadedAt: string; stage: string | null;
  problem: string | null;
  reading: null | { status: "READ" | "UNREADABLE"; extractorVersion: string; fields: ListingField[]; warnings: string[] };
};
export type ListingSnapshot = {
  id: string; vin: string | null; year: number | null; make: string | null; model: string | null; trim: string | null;
  mileage: number | null; advertisedPriceMinor: number; currency: string; source: string; retrievedAt: string;
  dealer: { name: string } | null; capturedOn: string | null; priceConditions: string[] | null;
};
/** listing-price-check.v1: the price on paperwork set beside the price in the listing the Buyer saved. */
export type ListingPriceCheck = {
  policyVersion: string; status: "HIGHER" | "SAME" | "LOWER" | "NO_PAPERWORK_PRICE" | "DIFFERENT_VIN";
  listingDate: string; advertisedPriceMinor: number; paperworkPriceMinor: number | null; differenceMinor: number | null;
  paperwork: "DEALER_OFFER" | "DESK_PAPERWORK"; verified: boolean; listingVin: string | null; paperworkVin: string | null;
  priceConditions: string[]; summary: string; deskQuestion: string | null;
};
export type ListingConflict = { id: string; field: string; confirmedValue: string; listingValue: string; status: string };
export type ListingCaptureView = {
  captures: ListingCaptureItem[]; snapshot: ListingSnapshot | null; check: ListingPriceCheck | null; identityConflicts: ListingConflict[];
};

const ACCEPT = "image/jpeg,image/png,image/heic,image/heif,application/pdf,.jpg,.jpeg,.png,.heic,.heif,.pdf";
const EMPTY: ListingCaptureView = { captures: [], snapshot: null, check: null, identityConflicts: [] };
/** The form's fields, in the order the Buyer checks them, with the request name each one is sent as. */
const FIELDS = [
  { field: "LISTING_PRICE", name: "advertisedPriceMinor", label: "Price the ad shows" },
  { field: "VEHICLE_VIN", name: "vin", label: "VIN" },
  { field: "VEHICLE_YEAR", name: "year", label: "Year" },
  { field: "VEHICLE_MAKE", name: "make", label: "Make" },
  { field: "VEHICLE_MODEL", name: "model", label: "Model" },
  { field: "VEHICLE_TRIM", name: "trim", label: "Trim" },
  { field: "ODOMETER", name: "mileage", label: "Mileage" },
  { field: "DEALER_NAME", name: "dealerName", label: "Dealer" },
  { field: "CAPTURE_DATE", name: "capturedOn", label: "Date you saved it" },
] as const;

function money(minor: number) {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: minor % 100 ? 2 : 0 })
    .format(minor / 100);
}
/** A field's label inside a sentence; the VIN keeps its capitals. */
function lower(label: string) {
  return label === "VIN" ? label : label.toLowerCase();
}
function shownDate(iso: string) {
  const [year, month, day] = iso.slice(0, 10).split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString("en-US", { year: "numeric", month: "long", day: "numeric" });
}
/** A read value as the Buyer types it: dollars for the price, the rest as read. */
function asTyped(field: ListingField | undefined) {
  if (!field?.value) return "";
  if (field.unit === "USD_MINOR") return (Number(field.value) / 100).toLocaleString("en-US", { maximumFractionDigits: 2 });
  return field.value;
}

class Refusal extends Error {
  constructor(message: string, readonly field?: string) { super(message); }
}

async function send<T>(path: string, token: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, { ...init, headers: { "Case-Access-Token": token,
    ...(typeof init.body === "string" ? { "Content-Type": "application/json" } : {}), ...(init.headers as Record<string, string>) } });
  if (response.ok) return response.json() as Promise<T>;
  let body: { detail?: string; field?: string } = {};
  try { body = await response.json(); } catch { /* No problem body to show. */ }
  throw new Refusal(typeof body.detail === "string" ? body.detail : "That did not work. Check your connection and try again.", body.field);
}

function asView(value: unknown): ListingCaptureView {
  const v = value as Partial<ListingCaptureView> | null;
  return v && Array.isArray(v.captures) ? { ...EMPTY, ...v } as ListingCaptureView : EMPTY;
}

/**
 * The dealer's ad for this car, saved by the Buyer: a screenshot, the page saved as a PDF, or its text. Each field
 * read from it is confirmed or corrected against where it was read before the case keeps it; otdog never visits
 * the dealer's site. Used in the "Get quotes" step and, compact, in the "I'm at the dealer" result.
 */
export function ListingCapture({ caseId, token, compact = false, pollDelayMs = 1500, onConfirmed }: Credentials & {
  compact?: boolean; pollDelayMs?: number; onConfirmed?: (view: ListingCaptureView) => void;
}) {
  const path = `/api/v1/cases/${encodeURIComponent(caseId)}/listing-captures`;
  const [view, setView] = useState<ListingCaptureView>();
  const [mode, setMode] = useState<"idle" | "paste" | "reading" | "confirm">("idle");
  const [documentId, setDocumentId] = useState<string>();
  const [error, setError] = useState<{ message: string; field?: string }>();
  const [busy, setBusy] = useState(false);
  const live = useRef(true);
  const Heading = compact ? "h3" : "h2";
  const headingId = compact ? "listing-capture-heading-dealer" : "listing-capture-heading";

  useEffect(() => {
    live.current = true;
    send<unknown>(path, token).then(value => { if (live.current) setView(asView(value)); })
      .catch(() => { if (live.current) setView(EMPTY); });
    return () => { live.current = false; };
  }, [path, token]);

  // While a capture is read, check on it until it has a reading or could not be processed.
  useEffect(() => {
    if (mode !== "reading" || !documentId) return;
    let stopped = false;
    const timer = window.setInterval(async () => {
      try {
        const next = asView(await send<unknown>(path, token));
        if (stopped) return;
        setView(next);
        const capture = next.captures.find(item => item.documentId === documentId);
        if (capture?.reading || capture?.stage === "FAILED") setMode("confirm");
      } catch { /* Try again on the next tick. */ }
    }, pollDelayMs);
    return () => { stopped = true; window.clearInterval(timer); };
  }, [mode, documentId, path, token, pollDelayMs]);

  async function step(action: () => Promise<void>) {
    setBusy(true); setError(undefined);
    try { await action(); }
    catch (failure) {
      const refusal = failure instanceof Refusal ? failure : new Refusal("That did not work. Check your connection and try again.");
      setError({ message: refusal.message, field: refusal.field });
    } finally { setBusy(false); }
  }

  function started(next: ListingCaptureView) {
    setView(next);
    const latest = next.captures[0];
    setDocumentId(latest?.documentId);
    setMode(latest?.reading || latest?.stage === "FAILED" ? "confirm" : "reading");
  }

  function chosen(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const form = new FormData();
    form.append("file", file);
    void step(async () => started(asView(await send<unknown>(path, token,
      { method: "POST", body: form, headers: { "Idempotency-Key": crypto.randomUUID() } }))));
  }

  function pasted(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const text = String(new FormData(event.currentTarget).get("listingText") ?? "");
    if (!text.trim()) return setError({ message: "Paste the text of the listing first.", field: "text" });
    void step(async () => started(asView(await send<unknown>(`${path}/text`, token,
      { method: "POST", body: JSON.stringify({ text }), headers: { "Idempotency-Key": crypto.randomUUID() } }))));
  }

  const capture = view?.captures.find(item => item.documentId === documentId);
  const snapshot = view?.snapshot;
  return <section className={`panel listing-capture${compact ? " listing-capture-compact" : ""}`} aria-labelledby={headingId}>
    <div className="section-heading"><div>
      {!compact && <p className="step">Get quotes</p>}
      <Heading id={headingId}>{compact ? "Compare with the dealer's ad" : "The dealer's ad for this car"}</Heading>
    </div></div>
    <p className="muted">Save the listing as you see it: a screenshot, the page saved as a PDF, or its text. Your copy stays
      with this case, and the price on the dealer's paperwork is set beside it. otdog does not visit the dealer's site.</p>

    {snapshot && mode === "idle" && <SavedListing snapshot={snapshot} check={view?.check ?? null}
      conflicts={view?.identityConflicts ?? []} busy={busy}
      onResolve={(conflict, resolution) => void step(async () => {
        await send<unknown>(`/api/v1/cases/${encodeURIComponent(caseId)}/vehicle-evidence/conflicts/${encodeURIComponent(conflict)}/resolution`,
          token, { method: "POST", body: JSON.stringify({ resolution }), headers: { "Idempotency-Key": crypto.randomUUID() } });
        const next = asView(await send<unknown>(path, token));
        setView(next); onConfirmed?.(next);
      })} />}

    {mode === "idle" && <div className="listing-capture-start">
      <label className="listing-capture-file">
        <input type="file" className="visually-hidden" accept={ACCEPT} onChange={chosen} disabled={busy} />
        <span>{snapshot ? "Add a newer screenshot or PDF" : "Add a screenshot or PDF of the ad"}</span>
      </label>
      <button type="button" className="btn-secondary" onClick={() => { setError(undefined); setMode("paste"); }}>Paste the ad's text</button>
      <button type="button" className="btn-link" onClick={() => { setDocumentId(undefined); setError(undefined); setMode("confirm"); }}>
        Type what the ad shows</button>
    </div>}

    {mode === "paste" && <form className="listing-capture-paste" onSubmit={pasted}>
      <label htmlFor={`${headingId}-text`}>The ad's text, as copied from the dealer's page</label>
      <textarea id={`${headingId}-text`} name="listingText" rows={6} maxLength={40000}
        aria-invalid={error?.field === "text" || undefined} aria-describedby={error?.field === "text" ? `${headingId}-error` : undefined} />
      <div className="actions">
        <button type="submit" disabled={busy}>{busy ? "Saving…" : "Read this text"}</button>
        <button type="button" className="btn-secondary" onClick={() => setMode("idle")}>Back</button>
      </div>
    </form>}

    {mode === "reading" && <p role="status">Reading your copy of the ad. This takes a few seconds.
      {" "}<button type="button" className="btn-link" onClick={() => setMode("confirm")}>Type what the ad shows instead</button></p>}

    {mode === "confirm" && <ConfirmListing key={documentId ?? "typed"} caseId={caseId} token={token} capture={capture}
      busy={busy} error={error} idPrefix={headingId}
      onCancel={() => { setMode("idle"); setError(undefined); }}
      onSubmit={body => void step(async () => {
        const next = asView(await send<unknown>(`${path}/confirmation`, token, { method: "POST", body: JSON.stringify(body) }));
        setView(next); setMode("idle"); onConfirmed?.(next);
      })} />}

    {error && mode !== "confirm" && <p className="error" role="alert" id={`${headingId}-error`}>{error.message}</p>}
  </section>;
}

function SavedListing({ snapshot, check, conflicts, busy, onResolve }: {
  snapshot: ListingSnapshot; check: ListingPriceCheck | null; conflicts: ListingConflict[]; busy: boolean;
  onResolve: (conflict: string, resolution: "KEEP_CONFIRMED_VEHICLE" | "USE_LISTING_VEHICLE") => void;
}) {
  const car = [snapshot.year, snapshot.make, snapshot.model, snapshot.trim].filter(Boolean).join(" ");
  return <div className="listing-capture-saved">
    <p><strong>Your saved ad: {money(snapshot.advertisedPriceMinor)}</strong>
      {" "}on {shownDate(snapshot.capturedOn ?? snapshot.retrievedAt)}{car ? `, ${car}` : ""}
      {snapshot.dealer?.name ? `, ${snapshot.dealer.name}` : ""}.</p>
    {snapshot.vin && <p className="muted">VIN <span className="vin">{snapshot.vin}</span></p>}
    {conflicts.map(conflict => <div key={conflict.id} className="listing-conflict" role="group" aria-labelledby={`listing-conflict-${conflict.id}`}>
      <p role="alert" id={`listing-conflict-${conflict.id}`}>This ad is for VIN <span className="vin">{conflict.listingValue}</span>, but
        this case is for VIN <span className="vin">{conflict.confirmedValue}</span>. Prices for different cars are not compared,
        and the dealer's paperwork waits until you choose.</p>
      <div className="actions">
        <button type="button" disabled={busy} onClick={() => onResolve(conflict.id, "KEEP_CONFIRMED_VEHICLE")}>
          Keep this case's car</button>
        <button type="button" className="btn-secondary" disabled={busy} onClick={() => onResolve(conflict.id, "USE_LISTING_VEHICLE")}>
          The ad's car is the right one</button>
      </div>
    </div>)}
    {check ? <ListingPriceNote check={check} /> : snapshot.priceConditions?.length ? <Conditions conditions={snapshot.priceConditions} /> : null}
  </div>;
}

function Conditions({ conditions }: { conditions: string[] }) {
  return <div className="listing-conditions">
    <p>The ad's fine print, which can explain part of a price difference:</p>
    <ul>{conditions.map(condition => <li key={condition}>{condition}</li>)}</ul>
  </div>;
}

/** The price check as one plain statement, the question to ask, and the ad's fine print beside it. */
export function ListingPriceNote({ check, question = true, title }: { check: ListingPriceCheck; question?: boolean; title?: string }) {
  return <div className="listing-price-check" data-status={check.status}>
    {title && <h3>{title}</h3>}
    <p>{check.summary}</p>
    {question && check.deskQuestion && <p className="dealer-question"><span className="dealer-question-label">Ask at the desk</span>
      <q>{check.deskQuestion}</q></p>}
    {check.priceConditions.length > 0 && <Conditions conditions={check.priceConditions} />}
    <p className="muted">From your own copy of the dealer's ad{check.paperwork === "DESK_PAPERWORK" && !check.verified
      ? ", against paperwork numbers that are not yet verified" : ""}. otdog did not visit the dealer's site.</p>
  </div>;
}

type Confirmation = Record<string, string | number | boolean | string[] | undefined>;

function ConfirmListing({ caseId, token, capture, busy, error, idPrefix, onCancel, onSubmit }: Credentials & {
  capture?: ListingCaptureItem; busy: boolean; error?: { message: string; field?: string }; idPrefix: string;
  onCancel: () => void; onSubmit: (body: Confirmation) => void;
}) {
  const fields = capture?.reading?.fields ?? [];
  const read = (field: string) => fields.find(item => item.field === field && item.value !== null)
    ?? fields.find(item => item.field === field);
  const readConditions = fields.filter(item => item.field === "LISTING_PRICE_CONDITIONS" && item.value);
  const [problem, setProblem] = useState<{ message: string; field?: string }>();
  const [shown, setShown] = useState<string>();
  const unreadable = !capture || capture.reading?.status === "UNREADABLE" || capture.stage === "FAILED";

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setProblem(undefined);
    const values = new FormData(event.currentTarget);
    const text = (name: string) => String(values.get(name) ?? "").trim();
    const price = toMinor(text("advertisedPriceMinor"));
    if (price === null || price <= 0) return setProblem({ field: "advertisedPriceMinor", message: "Enter the price the ad shows, like 23,995." });
    const body: Confirmation = { documentId: capture?.documentId, advertisedPriceMinor: price, confirmed: values.get("confirmed") === "on" };
    for (const name of ["vin", "make", "model", "trim", "dealerName", "capturedOn"]) if (text(name)) body[name] = text(name);
    if (text("year")) {
      if (!/^\d{4}$/.test(text("year"))) return setProblem({ field: "year", message: "Enter the model year, like 2021." });
      body.year = Number(text("year"));
    }
    if (text("mileage")) {
      const miles = text("mileage").replace(/[,\s]|mi(les)?$/gi, "");
      if (!/^\d{1,7}$/.test(miles)) return setProblem({ field: "mileage", message: "Enter the mileage as a whole number, like 38,000." });
      body.mileage = Number(miles);
    }
    const kept = values.getAll("condition").map(String);
    const added = text("otherConditions").split("\n").map(line => line.trim()).filter(Boolean);
    body.priceConditions = [...kept, ...added];
    if (!body.confirmed) return setProblem({ field: "confirmed", message: "Check each field against your copy of the ad, then tick the box." });
    onSubmit(body);
  }

  const shownError = problem ?? error;
  const errorId = (name: string) => `${idPrefix}-${name}-error`;
  const described = (name: string) => shownError?.field === name ? { "aria-invalid": true, "aria-describedby": errorId(name) } : {};
  const fieldError = (name: string) => shownError?.field === name
    ? <p className="error" role="alert" id={errorId(name)}>{shownError.message}</p> : null;
  return <form className="listing-capture-confirm" onSubmit={submit} noValidate>
    <h3>{unreadable ? (capture ? "This copy could not be read. Type what the ad shows" : "Type what the ad shows")
      : "Check each field against the ad"}</h3>
    {!unreadable && <p className="muted">Each field shows where it was read. Correct anything that is not what the ad says, and clear
      anything that is not in the ad.</p>}
    {capture?.problem && <p className="muted">{capture.problem}</p>}
    {FIELDS.map(({ field, name, label }) => {
      const value = read(field);
      const inputId = `${idPrefix}-${name}`;
      return <div className="listing-field" key={field}>
        <label htmlFor={inputId}>{label}{field === "LISTING_PRICE" ? "" : " (if shown)"}</label>
        <input id={inputId} name={name} defaultValue={asTyped(value)} autoComplete="off"
          type={name === "capturedOn" ? "date" : "text"}
          inputMode={name === "advertisedPriceMinor" ? "decimal" : name === "year" || name === "mileage" ? "numeric" : undefined}
          required={field === "LISTING_PRICE"} {...described(name)} />
        {fieldError(name)}
        {value && <p className="listing-field-source">
          <span className="muted">Read from “{value.originalWording}”{value.value === null ? ", but it could not be read clearly" : ""}.</span>
          {" "}<button type="button" className="btn-link" aria-expanded={shown === field}
            onClick={() => setShown(shown === field ? undefined : field)}>{shown === field ? "Hide where" : "Show where"}{" "}
            <span className="visually-hidden">the {lower(label)} was read</span></button>
        </p>}
        {value && shown === field && capture && <SourcePage caseId={caseId} token={token} documentId={capture.documentId}
          region={value.evidence} what={lower(label)} />}
      </div>;
    })}
    <fieldset className="listing-capture-conditions">
      <legend>The ad's fine print about the price</legend>
      {readConditions.length > 0 ? readConditions.map((item, index) => <label key={index} className="listing-condition">
        <input type="checkbox" name="condition" value={item.value!} defaultChecked />{item.value}</label>)
        : <p className="muted">None was read. Add any the ad shows, such as “requires dealer financing”.</p>}
      <label htmlFor={`${idPrefix}-other`}>Other fine print, one per line</label>
      <textarea id={`${idPrefix}-other`} name="otherConditions" rows={2} maxLength={3000} />
    </fieldset>
    <label className="listing-capture-check">
      <input type="checkbox" name="confirmed" {...described("confirmed")} />
      I checked these against my copy of the ad
    </label>
    {fieldError("confirmed")}
    {shownError && !["advertisedPriceMinor", "year", "mileage", "confirmed", "vin", "capturedOn", "make", "model", "trim", "dealerName"]
      .includes(shownError.field ?? "") && <p className="error" role="alert">{shownError.message}</p>}
    <div className="actions">
      <button type="submit" disabled={busy}>{busy ? "Saving…" : "Save this ad"}</button>
      <button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button>
    </div>
  </form>;
}

/** The capture's page with the region a field was read from outlined. */
function SourcePage({ caseId, token, documentId, region, what }: Credentials & { documentId: string; region: Region; what: string }) {
  const [image, setImage] = useState<string>();
  const [status, setStatus] = useState("Loading the page…");
  useEffect(() => {
    let current = true;
    let url: string | undefined;
    fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/listing-captures/${encodeURIComponent(documentId)}/pages/${region.pageNumber}/image`,
      { headers: { "Case-Access-Token": token } })
      .then(async response => {
        if (!response.ok) throw new Error();
        url = URL.createObjectURL(await response.blob());
        if (current) { setImage(url); setStatus(""); }
      })
      .catch(() => { if (current) setStatus("The page image is unavailable. The wording above still shows what was read."); });
    return () => { current = false; if (url) URL.revokeObjectURL(url); };
  }, [caseId, token, documentId, region.pageNumber]);
  const place = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 10000) / 100}%`;
  return <figure className="source-page source-page-shown listing-source-page">
    <div className="source-page-frame">
      {image ? <img src={image} alt={`Page ${region.pageNumber} of your copy of the ad, where the ${what} was read`} />
        : <div className="source-page-placeholder" role="img" aria-label={`Outline of page ${region.pageNumber} with the region where the ${what} was read`} />}
      <span className="source-region" aria-hidden="true"
        style={{ left: place(region.x), top: place(region.y), width: place(region.width), height: place(region.height) }} />
    </div>
    <figcaption>Page {region.pageNumber} · outlined where it was read</figcaption>
    {status && <p role="status">{status}</p>}
  </figure>;
}
