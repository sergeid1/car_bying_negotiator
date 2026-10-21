import { FormEvent, useEffect, useRef, useState } from "react";
import * as format from "./i18n/format";
import { en } from "./i18n/en";
import { fillIn, type Message, type Values } from "./i18n/messages";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

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
  /** What a model-backed reader thinks the charge is (ticket 142); it counts only once the Buyer confirms it. */
  suggestedCharge?: { id: string; name: string; category: string } | null;
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

type Decision = "CONFIRMED" | "CORRECTED" | "SUGGESTION";

/** Terms read with less confidence than this are flagged for a closer look at the source page. */
const LOW_CONFIDENCE = 0.85;

const CATEGORIES = [
  "SELLING_PRICE", "DISCOUNT", "REBATE", "DEALER_FEE", "ADD_ON", "TAX",
  "GOVERNMENT_CHARGE", "TRADE", "CREDIT", "UNKNOWN",
];

const TRADE_FIELDS = new Set(["TRADE_ALLOWANCE", "TRADE_PAYOFF"]);

function humanize(value: string) {
  const words = value.toLowerCase().replaceAll("_", " ");
  return words[0].toUpperCase() + words.slice(1);
}

type Copy = Messages["paperwork"]["offerVerification"];

/** English, for a caller outside a component; components pass the current language's copy. */
const ENGLISH: Copy = en.paperwork.offerVerification;

function fillCopy<M extends Message>(message: M, values: Values<M>) {
  return fillIn(format.locale(), message, values);
}

const fieldLabel = (field: string, t: Copy) => (t.fields as Record<string, string>)[field] ?? humanize(field);
const categoryLabel = (category: string, t: Copy) => (t.categories as Record<string, string>)[category] ?? humanize(category);
const lineLabel = (label: string, t: Copy) => fillCopy(t.lineItem, { label });

function money(minor: number, currency = "USD") {
  return format.money(minor, { currency });
}

export function formatValue(value: string | null | undefined, unit: string, t: Copy = ENGLISH) {
  if (value == null || value === "") return t.notFound;
  if (unit === "USD_MINOR" && /^-?\d+$/.test(value)) return money(Number(value));
  if (unit === "PERCENT") return `${value}%`;
  if (unit === "MONTH") return fillCopy(t.months, { value });
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
export function offerChanges(previous: DealerOffer, current: DealerOffer, t: Copy = ENGLISH): Change[] {
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
    keyed(offer.terms.candidates ?? [], item => fieldLabel(item.field, t));
  compare(candidates(previous), candidates(current),
    item => (item ? formatValue(item.value, item.unit, t) : t.notInVersion));
  const lines = (offer: DealerOffer) =>
    keyed(offer.terms.lineItems ?? [], item => lineLabel(item.originalLabel, t));
  compare(lines(previous), lines(current),
    item => (item ? `${money(item.amountMinor, item.currency)} · ${categoryLabel(item.category, t)}` : t.notInVersion));
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
  const { m: { paperwork: { offerVerification: t } }, fill } = useMessages();
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
        setError(await detail(response, t.correctionFailed));
        return false;
      }
      setFrozen(undefined);
      setDecisions(current => ({ ...current, [id]: "CORRECTED" }));
      await loadExtraction(extraction.documentId, true);
      return true;
    } catch {
      setError(t.correctionOffline);
      return false;
    } finally {
      setBusy(false);
    }
  }

  function confirm(id: string, decision: Decision = "CONFIRMED") {
    setFrozen(undefined);
    setDecisions(current => ({ ...current, [id]: decision }));
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
    const confirmedSuggestionLineItemIds = extraction.lineItems
      .filter(item => decisions[item.id] === "SUGGESTION" && item.suggestedCharge && !item.currentCorrection).map(item => item.id);
    const corrections = terms.map(item => item.currentCorrection?.id ?? "").filter(Boolean);
    const latestVersion = String(offers.at(-1)?.version ?? 0);
    try {
      const response = await fetch(`${casePath}/dealer-offers`, {
        method: "POST",
        headers: {
          ...headers,
          "Content-Type": "application/json",
          "Idempotency-Key": freezeKey(extraction.id, [latestVersion, ...confirmedCandidateIds, ...confirmedLineItemIds, ...corrections,
            ...confirmedSuggestionLineItemIds.map(id => `suggestion:${id}`)]),
        },
        body: JSON.stringify({ extractionId: extraction.id, confirmedCandidateIds, confirmedLineItemIds,
          ...(confirmedSuggestionLineItemIds.length > 0 ? { confirmedSuggestionLineItemIds } : {}) }),
      });
      if (!response.ok) {
        setError(await detail(response, t.freezeFailed));
        return;
      }
      const offer: DealerOffer = await response.json();
      setFrozen(fill(t.frozen, { version: offer.version }));
      onOfferFrozen?.({ version: offer.version });
      await loadOffers();
    } catch {
      setError(t.freezeOffline);
    } finally {
      setBusy(false);
    }
  }

  const filename = documents.find(document => document.id === extraction?.documentId)?.originalFilename ?? t.thePaperwork;
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
          <p className="step">{t.step}</p>
          <h2 id="verify-heading">{t.heading}</h2>
        </div>
      </div>
      <p className="muted">{t.intro}</p>

      {loading && <p className="operation" role="status"><span className="spinner" aria-hidden="true" />{t.loading}</p>}

      {!loading && !extraction && (
        <div className="notice">
          <div>
            <p>{t.noTerms}</p>
            <button type="button" className="btn-secondary" onClick={loadDocuments}>{t.checkAgain}</button>
          </div>
        </div>
      )}

      {documents.length > 1 && extraction && (
        <label>
          {t.paperworkToVerify}
          <select value={documentId} onChange={event => chooseDocument(event.target.value)}>
            {documents.map(document => <option key={document.id} value={document.id}>{document.originalFilename}</option>)}
          </select>
        </label>
      )}

      {extraction && (
        <>
          <p className="muted verify-provenance">
            {fill(t.provenance, { file: filename, version: extraction.version, extractor: extraction.extractorVersion, model: extraction.modelVersion })}
          </p>

          <ul className="verify-list" aria-label={t.termsList}>
            {extraction.candidates.filter(item => !isTradeCandidate(item)).map(item =>
              <CandidateRow key={item.id} item={item} {...rowProps} />)}
            {extraction.lineItems.filter(item => !isTradeLine(item)).map(item =>
              <LineItemRow key={item.id} item={item} {...rowProps} />)}
          </ul>

          {(tradeCandidates.length > 0 || tradeLines.length > 0) && (
            <section className="verify-trade" aria-labelledby="trade-heading">
              <h3 id="trade-heading">{t.tradeHeading}</h3>
              <p className="muted">{t.tradeIntro}</p>
              <ul className="verify-list" aria-label={t.tradeList}>
                {tradeCandidates.map(item => <CandidateRow key={item.id} item={item} {...rowProps} />)}
                {tradeLines.map(item => <LineItemRow key={item.id} item={item} {...rowProps} />)}
              </ul>
            </section>
          )}

          <div className="verify-freeze">
            <p id="verify-remaining" role="status">
              {undecided.length === 0
                ? t.allDecided
                : fill(t.remaining, { count: undecided.length })}
            </p>
            <button type="button" onClick={freeze} disabled={busy || undecided.length > 0 || Boolean(frozen)} aria-describedby="verify-remaining">
              {busy ? t.saving : t.freeze}
            </button>
          </div>
        </>
      )}

      {error && <p className="error" role="alert">{error}</p>}
      {frozen && <p className="notice verify-frozen" role="status">{frozen}</p>}

      {offers.length > 0 && (
        <section className="verify-versions" aria-labelledby="versions-heading">
          <h3 id="versions-heading">{t.versionsHeading}</h3>
          <p className="muted">{t.versionsIntro}</p>
          {offers.length > 1 && (
            <label>
              {t.compareVersion}
              <select value={compared} onChange={event => setCompared(Number(event.target.value))}>
                {offers.slice(1).map(offer => (
                  <option key={offer.version} value={offer.version}>
                    {fill(t.versionWith, { version: offer.version, previous: offer.version - 1 })}
                  </option>
                ))}
              </select>
            </label>
          )}
          {current && !previous && <p>{fill(t.firstVersion, { version: current.version })}</p>}
          {current && previous && <VersionChanges previous={previous} current={current} />}
        </section>
      )}
    </section>
  );
}

function VersionChanges({ previous, current }: { previous: DealerOffer; current: DealerOffer }) {
  const { m: { paperwork: { offerVerification: t } }, fill } = useMessages();
  const changes = offerChanges(previous, current, t);
  const versions = { previous: previous.version, current: current.version };
  if (changes.length === 0) {
    return <p>{fill(t.noDifferences, versions)}</p>;
  }
  return (
    <table className="verify-changes">
      <caption>{fill(t.differences, versions)}</caption>
      <thead>
        <tr><th scope="col">{t.term}</th><th scope="col">{fill(t.version, { version: previous.version })}</th><th scope="col">{fill(t.version, { version: current.version })}</th></tr>
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
  onConfirm: (id: string, decision?: Decision) => void;
  onCorrect: (targetType: "CANDIDATE" | "LINE_ITEM", id: string, body: Record<string, unknown>) => Promise<boolean>;
};

function SourcePage({ evidence, image, filename, term }: {
  evidence: EvidenceRegion; image?: string; filename: string; term: string;
}) {
  const { m: { paperwork: { offerVerification: t, common } }, fill } = useMessages();
  const percent = (value: number) => `${Math.round(Math.min(1, Math.max(0, value)) * 10000) / 100}%`;
  return (
    <figure className="source-page">
      <div className="source-page-frame">
        {image
          ? <img src={image} alt={fill(t.pageImage, { page: evidence.pageNumber, file: filename, term })} />
          : <div className="source-page-placeholder" role="img" aria-label={fill(t.pageUnavailable, { page: evidence.pageNumber, file: filename })} />}
        <span
          className="source-region"
          aria-hidden="true"
          style={{ left: percent(evidence.x), top: percent(evidence.y), width: percent(evidence.width), height: percent(evidence.height) }}
        />
      </div>
      <figcaption>{fill(common.pageOutlined, { page: evidence.pageNumber })}</figcaption>
    </figure>
  );
}

function DecisionBadge({ decision, lowConfidence, missing }: { decision?: Decision; lowConfidence: boolean; missing: boolean }) {
  const { m: { paperwork: { offerVerification: t } } } = useMessages();
  return (
    <p className="verify-badges">
      {missing && <span className="verify-flag">{t.missing}</span>}
      {lowConfidence && !missing && <span className="verify-flag">{t.lowConfidence}</span>}
      <span className={decision ? "verify-state verify-done" : "verify-state"}>
        {decision === "CONFIRMED" ? t.confirmed : decision === "CORRECTED" ? t.corrected
          : decision === "SUGGESTION" ? t.named : t.needsDecision}
      </span>
    </p>
  );
}

function CorrectionHistory({ history, show }: { history: Correction[]; show: (correction: Correction) => string }) {
  const { m: { paperwork: { offerVerification: t } }, fill } = useMessages();
  if (history.length < 2) return null;
  return (
    <details className="verify-history">
      <summary>{t.earlierCorrections}</summary>
      <ol>{history.map(correction => <li key={correction.id}>{fill(t.correction, { version: correction.version, value: show(correction) })}</li>)}</ol>
    </details>
  );
}

function CandidateRow({ item, busy, filename, decisions, pageFor, onConfirm, onCorrect }: RowProps & { item: Candidate }) {
  const { m: { paperwork: { offerVerification: t } }, fill } = useMessages();
  const [editing, setEditing] = useState(false);
  const label = fieldLabel(item.field, t);
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
          {item.currentCorrection && <div><dt>{t.yourValue}</dt><dd>{formatValue(item.currentCorrection.normalizedValue, item.normalizedUnit, t)}</dd></div>}
          <div><dt>{item.currentCorrection ? t.readKept : t.read}</dt><dd>{formatValue(item.normalizedValue, item.normalizedUnit, t)}</dd></div>
          <div><dt>{t.printedAs}</dt><dd>{fill(t.printed, { wording: item.originalWording })}</dd></div>
          <div><dt>{t.confidence}</dt><dd>{Math.round(item.confidence * 100)}%</dd></div>
          <div><dt>{t.source}</dt><dd>{fill(t.sourcePage, { page: item.evidence.pageNumber, file: filename })}</dd></div>
        </dl>
        <CorrectionHistory history={item.correctionHistory} show={correction => formatValue(correction.normalizedValue, item.normalizedUnit, t)} />
        <div className="actions">
          {!mustCorrect(item) && (
            <button type="button" className="btn-secondary" disabled={busy} aria-pressed={decision === "CONFIRMED"}
              onClick={() => onConfirm(item.id)}>
              {fill(missing ? t.confirmMissing : t.confirm, { label })}
            </button>
          )}
          <button type="button" className="btn-secondary" disabled={busy} aria-expanded={editing}
            onClick={() => setEditing(value => !value)}>
            {fill(t.correct, { label })}
          </button>
        </div>
        {editing && (
          <form className="verify-correct" onSubmit={submit}>
            <label htmlFor={inputId}>
              {fill(item.normalizedUnit === "USD_MINOR" ? t.shownOnPageDollars : item.normalizedUnit === "PERCENT" ? t.shownOnPagePercent
                : item.normalizedUnit === "MONTH" ? t.shownOnPageMonths : t.shownOnPage, { label, page: item.evidence.pageNumber })}
            </label>
            <input id={inputId} name="value" required
              type={item.normalizedUnit === "DATE" ? "date" : "text"}
              inputMode={["USD_MINOR", "PERCENT", "MONTH"].includes(item.normalizedUnit) ? "decimal" : undefined}
              pattern={item.normalizedUnit === "USD_MINOR" ? "\\$?-?[0-9,]+(\\.[0-9]{1,2})?" : undefined} />
            <button disabled={busy}>{t.saveCorrection}</button>
          </form>
        )}
      </div>
      <SourcePage evidence={item.evidence} image={pageFor(item)} filename={filename} term={label} />
    </li>
  );
}

function LineItemRow({ item, busy, filename, decisions, pageFor, onConfirm, onCorrect }: RowProps & { item: LineItem }) {
  const { m: { paperwork: { offerVerification: t } }, fill } = useMessages();
  const [editing, setEditing] = useState(false);
  const label = lineLabel(item.originalLabel, t);
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
          {correction && <div><dt>{t.yourValue}</dt><dd>{money(correction.amountMinor ?? 0, item.currency)} · {categoryLabel(correction.category ?? item.category, t)}</dd></div>}
          <div><dt>{correction ? t.readKept : t.read}</dt><dd>{money(item.amountMinor, item.currency)} · {categoryLabel(item.category, t)}</dd></div>
          <div><dt>{t.confidence}</dt><dd>{Math.round(item.confidence * 100)}%</dd></div>
          <div><dt>{t.source}</dt><dd>{fill(t.sourcePage, { page: item.evidence.pageNumber, file: filename })}</dd></div>
        </dl>
        {item.category === "UNKNOWN" && !correction && !item.suggestedCharge && (
          <p className="notice">{t.unknownCharge}</p>
        )}
        {item.suggestedCharge && !correction && (
          <div className="notice verify-suggestion">
            <p>{fill(t.suggestion, { name: item.suggestedCharge.name })}</p>
            <p className="muted">{t.suggestionNote}</p>
            <button type="button" className="btn-secondary" disabled={busy} aria-pressed={decision === "SUGGESTION"}
              onClick={() => onConfirm(item.id, "SUGGESTION")}>
              {fill(t.confirmSuggestion, { label: item.originalLabel, name: item.suggestedCharge.name })}
            </button>
          </div>
        )}
        <CorrectionHistory history={item.correctionHistory}
          show={value => `${money(value.amountMinor ?? 0, item.currency)} · ${categoryLabel(value.category ?? item.category, t)}`} />
        <div className="actions">
          <button type="button" className="btn-secondary" disabled={busy} aria-pressed={decision === "CONFIRMED"}
            onClick={() => onConfirm(item.id)}>
            {fill(t.confirm, { label })}
          </button>
          <button type="button" className="btn-secondary" disabled={busy} aria-expanded={editing}
            onClick={() => setEditing(value => !value)}>
            {fill(t.correct, { label })}
          </button>
        </div>
        {editing && (
          <form className="verify-correct" onSubmit={submit}>
            <label htmlFor={`${inputId}-amount`}>{fill(t.amountOnPage, { page: item.evidence.pageNumber })}</label>
            <input id={`${inputId}-amount`} name="amount" required inputMode="decimal"
              pattern="\$?-?[0-9,]+(\.[0-9]{1,2})?" defaultValue={(lineAmount(item) / 100).toFixed(2)} />
            <label htmlFor={`${inputId}-category`}>{t.kindOfCharge}</label>
            <select id={`${inputId}-category`} name="category" defaultValue={lineCategory(item)}>
              {CATEGORIES.map(category => <option key={category} value={category}>{categoryLabel(category, t)}</option>)}
            </select>
            <button disabled={busy}>{t.saveCorrection}</button>
          </form>
        )}
      </div>
      <SourcePage evidence={item.evidence} image={pageFor(item)} filename={filename} term={label} />
    </li>
  );
}
