import { FormEvent, useEffect, useRef, useState } from "react";

type EvidenceRegion = { pageNumber: number; x: number; y: number; width: number; height: number };

type Correction = {
  id: string; version: number; normalizedValue: string | null; normalizedUnit: string | null;
  category: string | null; amountMinor: number | null; createdAt: string;
};

type Candidate = {
  id: string; field: string; originalWording: string; normalizedValue: string | null;
  normalizedUnit: string; evidence: EvidenceRegion; confidence: number; material: boolean;
  verificationState: string; currentCorrection: Correction | null; correctionHistory: Correction[];
};

type LineItem = {
  id: string; originalLabel: string; category: string; amountMinor: number; currency: string;
  evidence: EvidenceRegion; confidence: number; verificationState: string;
  currentCorrection: Correction | null; correctionHistory: Correction[];
};

type Extraction = {
  id: string; documentId: string; version: number; extractorVersion: string; modelVersion: string;
  candidates: Candidate[]; lineItems: LineItem[];
};

type SourceDocument = { id: string; originalFilename: string; createdAt: string };

type OfferCandidate = { field: string; value: string | null; unit: string };
type OfferLineItem = { originalLabel: string; category: string; amountMinor: number; currency: string };
type DealerOffer = {
  id: string; version: number; status: string; createdAt: string;
  terms: { candidates: OfferCandidate[]; lineItems: OfferLineItem[] };
};

type Decision = "CONFIRMED" | "CORRECTED";

/** Terms read with less confidence than this are flagged for a closer look at the source page. */
const LOW_CONFIDENCE = 0.85;

const FIELD_LABELS: Record<string, string> = {
  SELLING_PRICE: "Selling price",
  DISCOUNT_TOTAL: "Discounts",
  REBATE_TOTAL: "Rebates",
  SUBTOTAL: "Subtotal",
  TAX_TOTAL: "Taxes",
  DEALER_CHARGES_TOTAL: "Dealer charges",
  GOVERNMENT_CHARGES_TOTAL: "Government charges",
  ADD_ON_TOTAL: "Add-ons",
  TRADE_ALLOWANCE: "Trade-in allowance",
  TRADE_PAYOFF: "Trade-in payoff",
  CASH_DOWN: "Cash down",
  OUT_THE_DOOR_TOTAL: "Out-the-door total",
  AMOUNT_FINANCED: "Amount financed",
  APR: "APR",
  TERM_MONTHS: "Loan term",
  MONTHLY_PAYMENT: "Monthly payment",
  FINANCE_CHARGE: "Finance charge",
  TOTAL_OF_PAYMENTS: "Total of payments",
  VEHICLE_VIN: "VIN",
  TRADE_VIN: "Trade-in VIN",
  VEHICLE_YEAR: "Vehicle year",
  VEHICLE_MAKE: "Vehicle make",
  VEHICLE_MODEL: "Vehicle model",
  VEHICLE_TRIM: "Vehicle trim",
  DOCUMENT_NUMBER: "Document number",
  DEALER_NAME: "Dealer name",
  BUYER_NAME: "Buyer name",
  OFFER_DATE: "Offer date",
  CONDITIONAL_LANGUAGE: "Conditions",
};

const CATEGORIES = [
  "SELLING_PRICE", "DISCOUNT", "REBATE", "DEALER_FEE", "ADD_ON", "TAX",
  "GOVERNMENT_CHARGE", "TRADE", "CREDIT", "UNKNOWN",
];

const TRADE_FIELDS = new Set(["TRADE_ALLOWANCE", "TRADE_PAYOFF"]);

function humanize(value: string) {
  const words = value.toLowerCase().replaceAll("_", " ");
  return words[0].toUpperCase() + words.slice(1);
}

const fieldLabel = (field: string) => FIELD_LABELS[field] ?? humanize(field);

function money(minor: number, currency = "USD") {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(minor / 100);
}

export function formatValue(value: string | null | undefined, unit: string) {
  if (value == null || value === "") return "Not found on the paperwork";
  if (unit === "USD_MINOR" && /^-?\d+$/.test(value)) return money(Number(value));
  if (unit === "PERCENT") return `${value}%`;
  if (unit === "MONTH") return `${value} months`;
  return value;
}

/** Dollars as typed ("$1,234.50") to minor units, or undefined when it is not an amount. */
export function toMinorUnits(typed: string) {
  const cleaned = typed.replace(/[$,\s]/g, "");
  if (!/^-?\d+(\.\d{1,2})?$/.test(cleaned)) return undefined;
  return Math.round(Number(cleaned) * 100);
}

const candidateValue = (candidate: Candidate) =>
  candidate.currentCorrection ? candidate.currentCorrection.normalizedValue : candidate.normalizedValue;
const lineCategory = (line: LineItem) => line.currentCorrection?.category ?? line.category;
const lineAmount = (line: LineItem) => line.currentCorrection?.amountMinor ?? line.amountMinor;

const isTradeCandidate = (candidate: Candidate) => TRADE_FIELDS.has(candidate.field);
const isTradeLine = (line: LineItem) => lineCategory(line) === "TRADE" || line.category === "TRADE";

/** A missing material value cannot be confirmed as blank; the Buyer has to enter what the paperwork says. */
const mustCorrect = (candidate: Candidate) => candidate.material && !candidateValue(candidate);

type Change = { term: string; before: string; after: string };

/** Every term that differs between two consecutive frozen versions, including added and removed ones. */
export function offerChanges(previous: DealerOffer, current: DealerOffer): Change[] {
  const keyed = <T,>(items: T[], key: (item: T) => string) => {
    const seen = new Map<string, number>();
    return new Map(items.map(item => {
      const base = key(item);
      const count = (seen.get(base) ?? 0) + 1;
      seen.set(base, count);
      return [count > 1 ? `${base} (${count})` : base, item] as const;
    }));
  };
  const changes: Change[] = [];
  const compare = <T,>(before: Map<string, T>, after: Map<string, T>, show: (item?: T) => string) => {
    for (const term of new Set([...before.keys(), ...after.keys()])) {
      const was = show(before.get(term));
      const now = show(after.get(term));
      if (was !== now) changes.push({ term, before: was, after: now });
    }
  };
  const candidates = (offer: DealerOffer) =>
    keyed(offer.terms.candidates ?? [], item => fieldLabel(item.field));
  compare(candidates(previous), candidates(current),
    item => (item ? formatValue(item.value, item.unit) : "Not in this version"));
  const lines = (offer: DealerOffer) =>
    keyed(offer.terms.lineItems ?? [], item => `Line item “${item.originalLabel}”`);
  compare(lines(previous), lines(current),
    item => (item ? `${money(item.amountMinor, item.currency)} · ${humanize(item.category)}` : "Not in this version"));
  return changes;
}

/**
 * Short and printable. The same decisions on top of the same latest version replay one frozen offer
 * (a retried or repeated tap); any freeze in between makes the next one a new version.
 */
function freezeKey(extractionId: string, parts: string[]) {
  let hash = 0x811c9dc5;
  for (const character of parts.join("|")) {
    hash = Math.imul(hash ^ character.charCodeAt(0), 0x01000193) >>> 0;
  }
  return `offer-${extractionId}-${parts.length}-${hash.toString(16)}`;
}

async function detail(response: Response, fallback: string) {
  try {
    const body = await response.json();
    return typeof body?.detail === "string" ? body.detail : fallback;
  } catch {
    return fallback;
  }
}

export function OfferVerification({
  caseId, token, onOfferFrozen,
}: { caseId: string; token: string; onOfferFrozen?: (offer: { version: number }) => void }) {
  const [documents, setDocuments] = useState<SourceDocument[]>([]);
  const [documentId, setDocumentId] = useState<string>();
  const [extraction, setExtraction] = useState<Extraction>();
  const [decisions, setDecisions] = useState<Record<string, Decision>>({});
  const [offers, setOffers] = useState<DealerOffer[]>([]);
  const [compared, setCompared] = useState<number>();
  const [pages, setPages] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [frozen, setFrozen] = useState<string>();
  const pageUrls = useRef<string[]>([]);
  const casePath = `/api/v1/cases/${encodeURIComponent(caseId)}`;
  const headers = { "Case-Access-Token": token };

  async function getJson<T>(path: string): Promise<T | undefined> {
    const response = await fetch(path, { headers });
    return response.ok ? response.json() as Promise<T> : undefined;
  }

  async function loadOffers() {
    const latest = await getJson<DealerOffer>(`${casePath}/dealer-offers/latest`).catch(() => undefined);
    if (!latest?.version) {
      setOffers([]);
      return;
    }
    const earlier = await Promise.all(
      Array.from({ length: latest.version - 1 }, (_, index) =>
        getJson<DealerOffer>(`${casePath}/dealer-offers/${index + 1}`).catch(() => undefined)));
    setOffers([...earlier.filter((offer): offer is DealerOffer => Boolean(offer)), latest]);
    setCompared(latest.version);
  }

  /** Terms the Buyer already corrected or confirmed into a frozen offer keep that decision on return. */
  function priorDecisions(value: Extraction) {
    const prior: Record<string, Decision> = {};
    for (const item of [...value.candidates, ...value.lineItems]) {
      if (item.currentCorrection) prior[item.id] = "CORRECTED";
      else if (item.verificationState === "VERIFIED") prior[item.id] = "CONFIRMED";
    }
    return prior;
  }

  async function loadExtraction(id: string, keepDecisions = false) {
    const value = await getJson<Extraction>(
      `${casePath}/documents/${encodeURIComponent(id)}/extractions/latest`).catch(() => undefined);
    setExtraction(value);
    if (value) {
      setDecisions(current => ({ ...priorDecisions(value), ...(keepDecisions ? current : {}) }));
    }
    return value;
  }

  async function loadDocuments() {
    setLoading(true);
    setError(undefined);
    try {
      const listed = await getJson<SourceDocument[]>(`${casePath}/documents`).catch(() => undefined);
      const available = Array.isArray(listed) ? listed : [];
      setDocuments(available);
      // The newest paperwork with terms read is the one the Buyer most likely wants to verify.
      for (const document of [...available].reverse()) {
        if (await loadExtraction(document.id)) {
          setDocumentId(document.id);
          break;
        }
      }
      await loadOffers();
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadDocuments();
    return () => pageUrls.current.forEach(url => URL.revokeObjectURL?.(url));
  }, [caseId, token]);

  // Each source page is fetched once, with the case token, and shared by every term read from it.
  const wantedPages = extraction
    ? [...new Set([...extraction.candidates, ...extraction.lineItems].map(item => item.evidence.pageNumber))]
    : [];
  const pageSignature = `${extraction?.documentId}:${wantedPages.join(",")}`;
  useEffect(() => {
    if (!extraction || typeof URL.createObjectURL !== "function") return undefined;
    let cancelled = false;
    for (const pageNumber of wantedPages) {
      const key = `${extraction.documentId}:${pageNumber}`;
      if (pages[key]) continue;
      fetch(`${casePath}/documents/${encodeURIComponent(extraction.documentId)}/pages/${pageNumber}/image`, { headers })
        .then(response => (response.ok ? response.blob() : undefined))
        .then(blob => {
          if (!blob || cancelled) return;
          const url = URL.createObjectURL(blob);
          pageUrls.current.push(url);
          setPages(current => ({ ...current, [key]: url }));
        })
        .catch(() => undefined);
    }
    return () => {
      cancelled = true;
    };
  }, [pageSignature]);

  async function chooseDocument(id: string) {
    setDocumentId(id);
    setFrozen(undefined);
    setError(undefined);
    await loadExtraction(id);
  }

  async function correct(targetType: "CANDIDATE" | "LINE_ITEM", id: string, body: Record<string, unknown>) {
    if (!extraction) return false;
    setBusy(true);
    setError(undefined);
    try {
      const response = await fetch(
        `${casePath}/documents/${encodeURIComponent(extraction.documentId)}/extractions/`
          + `${encodeURIComponent(extraction.id)}/corrections`,
        {
          method: "POST",
          headers: { ...headers, "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify({ targetType, targetId: id, ...body }),
        });
      if (!response.ok) {
        setError(await detail(response, "The correction was not saved. Check the value and try again."));
        return false;
      }
      setFrozen(undefined);
      setDecisions(current => ({ ...current, [id]: "CORRECTED" }));
      await loadExtraction(extraction.documentId, true);
      return true;
    } catch {
      setError("The correction was not saved. Check your connection and try again.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  function confirm(id: string) {
    setFrozen(undefined);
    setDecisions(current => ({ ...current, [id]: "CONFIRMED" }));
  }

  const terms = extraction ? [...extraction.candidates, ...extraction.lineItems] : [];
  const undecided = terms.filter(item => !decisions[item.id]
    || ("field" in item && decisions[item.id] === "CONFIRMED" && mustCorrect(item)));

  async function freeze() {
    if (!extraction || undecided.length > 0) return;
    setBusy(true);
    setError(undefined);
    const confirmedCandidateIds = extraction.candidates.map(item => item.id).filter(id => decisions[id]);
    const confirmedLineItemIds = extraction.lineItems.map(item => item.id).filter(id => decisions[id]);
    const corrections = terms.map(item => item.currentCorrection?.id ?? "").filter(Boolean);
    const latestVersion = String(offers.at(-1)?.version ?? 0);
    try {
      const response = await fetch(`${casePath}/dealer-offers`, {
        method: "POST",
        headers: {
          ...headers,
          "Content-Type": "application/json",
          "Idempotency-Key": freezeKey(extraction.id, [latestVersion, ...confirmedCandidateIds, ...confirmedLineItemIds, ...corrections]),
        },
        body: JSON.stringify({ extractionId: extraction.id, confirmedCandidateIds, confirmedLineItemIds }),
      });
      if (!response.ok) {
        setError(await detail(response, "The Dealer Offer was not frozen. Try again."));
        return;
      }
      const offer: DealerOffer = await response.json();
      setFrozen(`Dealer Offer version ${offer.version} is frozen. Later corrections create a new version; this one does not change.`);
      onOfferFrozen?.({ version: offer.version });
      await loadOffers();
    } catch {
      setError("The Dealer Offer was not frozen. Check your connection and try again.");
    } finally {
      setBusy(false);
    }
  }

  const filename = documents.find(document => document.id === extraction?.documentId)?.originalFilename ?? "the paperwork";
  const pageFor = (item: { evidence: EvidenceRegion }) =>
    extraction ? pages[`${extraction.documentId}:${item.evidence.pageNumber}`] : undefined;
  const rowProps = { busy, filename, onConfirm: confirm, onCorrect: correct, decisions, pageFor };
  const tradeCandidates = extraction?.candidates.filter(isTradeCandidate) ?? [];
  const tradeLines = extraction?.lineItems.filter(isTradeLine) ?? [];
  const current = offers.find(offer => offer.version === compared);
  const previous = offers.find(offer => offer.version === (compared ?? 0) - 1);

  return (
    <section className="panel verify-panel" aria-labelledby="verify-heading">
      <div className="section-heading">
        <div>
          <p className="step">Step 3 of 3</p>
          <h2 id="verify-heading">Check the terms against the paperwork</h2>
        </div>
      </div>
      <p className="muted">
        Compare each term with the highlighted area of its page. Confirm it, or enter what the page
        actually says. The value read from the paperwork is kept alongside anything you enter.
      </p>

      {loading && <p className="operation" role="status"><span className="spinner" aria-hidden="true" />Loading read terms</p>}

      {!loading && !extraction && (
        <div className="notice">
          <div>
            <p>No terms have been read from this case's paperwork yet.</p>
            <button type="button" className="btn-secondary" onClick={loadDocuments}>Check for read paperwork</button>
          </div>
        </div>
      )}

      {documents.length > 1 && extraction && (
        <label>
          Paperwork to verify
          <select value={documentId} onChange={event => chooseDocument(event.target.value)}>
            {documents.map(document => <option key={document.id} value={document.id}>{document.originalFilename}</option>)}
          </select>
        </label>
      )}

      {extraction && (
        <>
          <p className="muted verify-provenance">
            Read from “{filename}” · extraction version {extraction.version} · {extraction.extractorVersion} / {extraction.modelVersion}
          </p>

          <ul className="verify-list" aria-label="Deal terms read from the paperwork">
            {extraction.candidates.filter(item => !isTradeCandidate(item)).map(item =>
              <CandidateRow key={item.id} item={item} {...rowProps} />)}
            {extraction.lineItems.filter(item => !isTradeLine(item)).map(item =>
              <LineItemRow key={item.id} item={item} {...rowProps} />)}
          </ul>

          {(tradeCandidates.length > 0 || tradeLines.length > 0) && (
            <section className="verify-trade" aria-labelledby="trade-heading">
              <h3 id="trade-heading">Trade-in terms on the paperwork</h3>
              <p className="muted">
                Recorded exactly as the paperwork states them and kept apart from the purchase terms.
                This review does not rate or value the trade-in.
              </p>
              <ul className="verify-list" aria-label="Trade-in terms read from the paperwork">
                {tradeCandidates.map(item => <CandidateRow key={item.id} item={item} {...rowProps} />)}
                {tradeLines.map(item => <LineItemRow key={item.id} item={item} {...rowProps} />)}
              </ul>
            </section>
          )}

          <div className="verify-freeze">
            <p id="verify-remaining" role="status">
              {undecided.length === 0
                ? "Every term has your decision."
                : `${undecided.length} ${undecided.length === 1 ? "term still needs" : "terms still need"} your decision before the Dealer Offer can be frozen.`}
            </p>
            <button type="button" onClick={freeze} disabled={busy || undecided.length > 0 || Boolean(frozen)} aria-describedby="verify-remaining">
              {busy ? "Saving…" : "Freeze this Dealer Offer"}
            </button>
          </div>
        </>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {frozen && <p className="notice verify-frozen" role="status">{frozen}</p>}

      {offers.length > 0 && (
        <section className="verify-versions" aria-labelledby="versions-heading">
          <h3 id="versions-heading">Frozen Dealer Offer versions</h3>
          <p className="muted">Frozen versions never change. Each later freeze adds a new version.</p>
          {offers.length > 1 && (
            <label>
              Compare version
              <select value={compared} onChange={event => setCompared(Number(event.target.value))}>
                {offers.slice(1).map(offer => (
                  <option key={offer.version} value={offer.version}>
                    Version {offer.version} with version {offer.version - 1}
                  </option>
                ))}
              </select>
            </label>
          )}
          {current && !previous && <p>Version {current.version} is the first frozen Dealer Offer.</p>}
          {current && previous && <VersionChanges previous={previous} current={current} />}
        </section>
      )}
    </section>
  );
}

function VersionChanges({ previous, current }: { previous: DealerOffer; current: DealerOffer }) {
  const changes = offerChanges(previous, current);
  if (changes.length === 0) {
    return <p>No terms differ between version {previous.version} and version {current.version}.</p>;
  }
  return (
    <table className="verify-changes">
      <caption>Terms that differ between version {previous.version} and version {current.version}</caption>
      <thead>
        <tr><th scope="col">Term</th><th scope="col">Version {previous.version}</th><th scope="col">Version {current.version}</th></tr>
      </thead>
      <tbody>
        {changes.map(change => (
          <tr key={change.term}><th scope="row">{change.term}</th><td>{change.before}</td><td>{change.after}</td></tr>
        ))}
      </tbody>
    </table>
  );
}

type RowProps = {
  busy: boolean;
  filename: string;
  decisions: Record<string, Decision>;
  pageFor: (item: { evidence: EvidenceRegion }) => string | undefined;
  onConfirm: (id: string) => void;
  onCorrect: (targetType: "CANDIDATE" | "LINE_ITEM", id: string, body: Record<string, unknown>) => Promise<boolean>;
};

function SourcePage({ evidence, image, filename, term }: {
  evidence: EvidenceRegion; image?: string; filename: string; term: string;
}) {
  const percent = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 10000) / 100}%`;
  return (
    <figure className="source-page">
      <div className="source-page-frame">
        {image
          ? <img src={image} alt={`Page ${evidence.pageNumber} of “${filename}”, where ${term} was read`} />
          : <div className="source-page-placeholder" role="img" aria-label={`Page ${evidence.pageNumber} of “${filename}” is not available to show`} />}
        <span
          className="source-region"
          aria-hidden="true"
          style={{ left: percent(evidence.x), top: percent(evidence.y), width: percent(evidence.width), height: percent(evidence.height) }}
        />
      </div>
      <figcaption>Page {evidence.pageNumber} · outlined where it was read</figcaption>
    </figure>
  );
}

function DecisionBadge({ decision, lowConfidence, missing }: { decision?: Decision; lowConfidence: boolean; missing: boolean }) {
  return (
    <p className="verify-badges">
      {missing && <span className="verify-flag">Missing</span>}
      {lowConfidence && !missing && <span className="verify-flag">Low confidence</span>}
      <span className={decision ? "verify-state verify-done" : "verify-state"}>
        {decision === "CONFIRMED" ? "Confirmed" : decision === "CORRECTED" ? "Corrected by you" : "Needs your decision"}
      </span>
    </p>
  );
}

function CorrectionHistory({ history, show }: { history: Correction[]; show: (correction: Correction) => string }) {
  if (history.length < 2) return null;
  return (
    <details className="verify-history">
      <summary>Earlier corrections</summary>
      <ol>{history.map(correction => <li key={correction.id}>Correction {correction.version}: {show(correction)}</li>)}</ol>
    </details>
  );
}

function CandidateRow({ item, busy, filename, decisions, pageFor, onConfirm, onCorrect }: RowProps & { item: Candidate }) {
  const [editing, setEditing] = useState(false);
  const label = fieldLabel(item.field);
  const decision = decisions[item.id] === "CONFIRMED" && mustCorrect(item) ? undefined : decisions[item.id];
  const missing = !item.normalizedValue;
  const lowConfidence = item.confidence < LOW_CONFIDENCE;
  const inputId = `correct-${item.id}`;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const typed = String(new FormData(event.currentTarget).get("value") ?? "").trim();
    const value = item.normalizedUnit === "USD_MINOR" ? toMinorUnits(typed)?.toString() : typed;
    if (!value) return;
    if (await onCorrect("CANDIDATE", item.id, { normalizedValue: value, normalizedUnit: item.normalizedUnit })) {
      setEditing(false);
    }
  }

  return (
    <li className="verify-item" aria-label={label}>
      <div className="verify-term">
        <h4>{label}</h4>
        <DecisionBadge decision={decision} lowConfidence={lowConfidence} missing={missing && !item.currentCorrection} />
        <dl className="verify-facts">
          {item.currentCorrection && <div><dt>Your value</dt><dd>{formatValue(item.currentCorrection.normalizedValue, item.normalizedUnit)}</dd></div>}
          <div><dt>{item.currentCorrection ? "Read from paperwork (kept)" : "Read from paperwork"}</dt><dd>{formatValue(item.normalizedValue, item.normalizedUnit)}</dd></div>
          <div><dt>Printed as</dt><dd>“{item.originalWording}”</dd></div>
          <div><dt>Confidence</dt><dd>{Math.round(item.confidence * 100)}%</dd></div>
          <div><dt>Source</dt><dd>Page {item.evidence.pageNumber} of “{filename}”</dd></div>
        </dl>
        <CorrectionHistory history={item.correctionHistory} show={correction => formatValue(correction.normalizedValue, item.normalizedUnit)} />
        <div className="actions">
          {!mustCorrect(item) && (
            <button type="button" className="btn-secondary" disabled={busy} aria-pressed={decision === "CONFIRMED"}
              onClick={() => onConfirm(item.id)}>
              {missing ? `Confirm ${label} is not on the paperwork` : `Confirm ${label}`}
            </button>
          )}
          <button type="button" className="btn-secondary" disabled={busy} aria-expanded={editing}
            onClick={() => setEditing(value => !value)}>
            Correct {label}
          </button>
        </div>
        {editing && (
          <form className="verify-correct" onSubmit={submit}>
            <label htmlFor={inputId}>
              {label} as shown on page {item.evidence.pageNumber}
              {item.normalizedUnit === "USD_MINOR" ? " ($)" : item.normalizedUnit === "PERCENT" ? " (%)" : item.normalizedUnit === "MONTH" ? " (months)" : ""}
            </label>
            <input id={inputId} name="value" required
              type={item.normalizedUnit === "DATE" ? "date" : "text"}
              inputMode={["USD_MINOR", "PERCENT", "MONTH"].includes(item.normalizedUnit) ? "decimal" : undefined}
              pattern={item.normalizedUnit === "USD_MINOR" ? "\\$?-?[0-9,]+(\\.[0-9]{1,2})?" : undefined} />
            <button disabled={busy}>Save correction</button>
          </form>
        )}
      </div>
      <SourcePage evidence={item.evidence} image={pageFor(item)} filename={filename} term={label} />
    </li>
  );
}

function LineItemRow({ item, busy, filename, decisions, pageFor, onConfirm, onCorrect }: RowProps & { item: LineItem }) {
  const [editing, setEditing] = useState(false);
  const label = `Line item “${item.originalLabel}”`;
  const decision = decisions[item.id];
  const correction = item.currentCorrection;
  const inputId = `correct-${item.id}`;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    const amountMinor = toMinorUnits(String(values.get("amount") ?? ""));
    if (amountMinor === undefined) return;
    if (await onCorrect("LINE_ITEM", item.id, { category: String(values.get("category")), amountMinor })) {
      setEditing(false);
    }
  }

  return (
    <li className="verify-item" aria-label={label}>
      <div className="verify-term">
        <h4>{label}</h4>
        <DecisionBadge decision={decision} lowConfidence={item.confidence < LOW_CONFIDENCE} missing={false} />
        <dl className="verify-facts">
          {correction && <div><dt>Your value</dt><dd>{money(correction.amountMinor ?? 0, item.currency)} · {humanize(correction.category ?? item.category)}</dd></div>}
          <div><dt>{correction ? "Read from paperwork (kept)" : "Read from paperwork"}</dt><dd>{money(item.amountMinor, item.currency)} · {humanize(item.category)}</dd></div>
          <div><dt>Confidence</dt><dd>{Math.round(item.confidence * 100)}%</dd></div>
          <div><dt>Source</dt><dd>Page {item.evidence.pageNumber} of “{filename}”</dd></div>
        </dl>
        {item.category === "UNKNOWN" && !correction && (
          <p className="notice">The paperwork does not say what this charge is. Ask the Dealer to explain it.</p>
        )}
        <CorrectionHistory history={item.correctionHistory}
          show={value => `${money(value.amountMinor ?? 0, item.currency)} · ${humanize(value.category ?? item.category)}`} />
        <div className="actions">
          <button type="button" className="btn-secondary" disabled={busy} aria-pressed={decision === "CONFIRMED"}
            onClick={() => onConfirm(item.id)}>
            Confirm {label}
          </button>
          <button type="button" className="btn-secondary" disabled={busy} aria-expanded={editing}
            onClick={() => setEditing(value => !value)}>
            Correct {label}
          </button>
        </div>
        {editing && (
          <form className="verify-correct" onSubmit={submit}>
            <label htmlFor={`${inputId}-amount`}>Amount on page {item.evidence.pageNumber} ($)</label>
            <input id={`${inputId}-amount`} name="amount" required inputMode="decimal"
              pattern="\$?-?[0-9,]+(\.[0-9]{1,2})?" defaultValue={(lineAmount(item) / 100).toFixed(2)} />
            <label htmlFor={`${inputId}-category`}>Kind of charge</label>
            <select id={`${inputId}-category`} name="category" defaultValue={lineCategory(item)}>
              {CATEGORIES.map(category => <option key={category} value={category}>{humanize(category)}</option>)}
            </select>
            <button disabled={busy}>Save correction</button>
          </form>
        )}
      </div>
      <SourcePage evidence={item.evidence} image={pageFor(item)} filename={filename} term={label} />
    </li>
  );
}
