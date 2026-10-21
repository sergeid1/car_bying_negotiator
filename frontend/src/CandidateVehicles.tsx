import { ReactNode, useRef, useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";
import { findingCode } from "./i18n/findings";

export type CandidateMarketCheck = { status: string; range: { lowMinor: number; highMinor: number; currency: string } | null;
  confidence: string; provider: { retrievedAt: string } | null; limitation: string | null;
  vintage: { oldestObservationAt: string; newestObservationAt: string } | null };

type Reason = { kind: "MUST_HAVE" | "NICE_TO_HAVE" | "REFERENCE"; text: string };
type Snapshot = { source: string; retrievedAt: string; observedAt: string | null; staleness: "CURRENT" | "STALE" | "UNKNOWN";
  synthetic: boolean };
export type CandidateVehicle = { listingId: string; vin: string | null; title: string; year: number | null; make: string | null;
  model: string | null; trim: string | null; mileage: number | null; distanceMiles: number | null;
  advertisedPriceMinor: number | null; currency: string | null; matched: Reason[]; missed: Reason[]; unconfirmed: Reason[];
  snapshot: Snapshot; candidateId?: string; marketCheck?: CandidateMarketCheck };
type Excluded = { listingId: string; vin: string | null; title: string; missed: Reason[]; snapshot: Snapshot };
export type CandidateResults = { matcherVersion: string; briefVersion: number; referenceListVersion: string;
  availability: "AVAILABLE" | "UNAVAILABLE" | "TIMED_OUT" | "RATE_LIMITED"; notice: string | null;
  provider: { name: string; version: string; licence: string; synthetic: boolean }; retrievedAt: string; ordering: string;
  candidates: CandidateVehicle[]; excluded: Excluded[] };
type Problem = { status?: number; detail?: string };

const moment = (iso: string | null) => {
  const date = iso ? new Date(iso) : undefined;
  return !date || Number.isNaN(date.getTime()) ? "" : format.dateTime(date, { month: "short", day: "numeric",
    year: "numeric", hour: "numeric", minute: "2-digit" });
};
const amount = (minor: number, currency: string | null) => {
  const whole = minor % 100 === 0;
  const shown = format.number(minor / 100, { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 });
  return !currency || currency === "USD" ? `$${shown}` : `${shown} ${currency}`;
};

/**
 * Inventory search results for the current Shopping Brief as Candidate Vehicles. Each one says in plain
 * words which criteria and reference details it matches, misses, or the listing does not state; the
 * order comes only from the brief, the Reference Vehicles and what each listing states.
 */
export function CandidateVehicles({ briefVersion, search, onSessionEnded, actions }: {
  briefVersion: number; search: () => Promise<CandidateResults>; onSessionEnded: () => void;
  actions?: (candidate: CandidateVehicle) => ReactNode;
}) {
  const { m: { content: { candidateVehicles: t } }, fill } = useMessages();
  const [results, setResults] = useState<CandidateResults>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const heading = useRef<HTMLHeadingElement>(null);

  async function run() {
    setBusy(true); setError("");
    try {
      setResults(await search());
      window.setTimeout(() => heading.current?.focus(), 0);
    } catch (thrown) {
      const problem = thrown as Problem;
      if (problem?.status === 401) onSessionEnded();
      else setError(problem?.detail ?? t.searchFailed);
    } finally { setBusy(false); }
  }

  const count = results?.candidates.length ?? 0;
  return <section className="candidate-vehicles" aria-labelledby="candidate-vehicles-heading">
    <h3 id="candidate-vehicles-heading">{t.heading}</h3>
    <p>{fill(t.intro, { version: String(briefVersion) })}</p>
    <button disabled={busy} onClick={run}>{results ? t.searchAgain : t.search}</button>
    {busy && <p role="status">{t.searching}</p>}
    {error && <p role="alert">{error}</p>}
    {results && <div className="candidate-results">
      <h4 ref={heading} tabIndex={-1}>{results.availability !== "AVAILABLE" ? t.noResults
        : fill(t.results, { count, version: String(results.briefVersion) })}</h4>
      {results.notice && <p className="notice">{results.notice}</p>}
      {results.availability === "AVAILABLE" && <>
        <p className="muted">{results.ordering}</p>
        <p className="muted">{fill(t.searched, { provider: results.provider.name, version: results.provider.version,
          date: moment(results.retrievedAt), matcher: results.matcherVersion })}</p>
        {count === 0 ? <p>{t.noneMeet}</p>
          : <ol className="candidate-list" aria-label={fill(t.listLabel, { version: String(results.briefVersion) })}>
            {results.candidates.map((candidate, index) => <li key={`${candidate.snapshot.source}-${candidate.listingId}`}>
              <CandidateCard candidate={candidate} position={index + 1} />
              {actions?.(candidate)}
            </li>)}
          </ol>}
        {results.excluded.length > 0 && <details className="candidate-excluded">
          <summary>{fill(t.excluded, { count: results.excluded.length })}</summary>
          <ul>
            {results.excluded.map(row => <li key={`${row.snapshot.source}-${row.listingId}`}>
              <strong>{row.title}</strong>
              <ul>{row.missed.map(reason => <li key={reason.text}>{reason.text}</li>)}</ul>
            </li>)}
          </ul>
        </details>}
      </>}
    </div>}
  </section>;
}

export function CandidateCard({ candidate, position }: { candidate: CandidateVehicle; position: number }) {
  const { m: { content: { candidateVehicles: { card: t } } }, fill } = useMessages();
  const { snapshot } = candidate;
  const name = fill(t.name, { position: String(position), title: candidate.title });
  const reasons = (label: string, list: Reason[]) => list.length > 0 && <div className="candidate-reasons">
    <h6>{label}</h6>
    <ul>{list.map(reason => <li key={reason.text}>{reason.text}</li>)}</ul>
  </div>;
  return <article className="candidate" aria-label={name}>
    <h5>{name}</h5>
    <dl className="facts" aria-label={fill(t.detailsLabel, { position: String(position) })}>
      <div><dt>{t.mileage}</dt><dd>{candidate.mileage === null ? t.notStated : fill(t.miles, { miles: format.number(candidate.mileage) })}</dd></div>
      <div><dt>{t.distance}</dt><dd>{candidate.distanceMiles === null ? t.notStated : fill(t.miles, { miles: String(candidate.distanceMiles) })}</dd></div>
      <div><dt>{t.advertisedPrice}</dt><dd>{candidate.advertisedPriceMinor === null ? t.notStated
        : fill(t.advertised, { price: amount(candidate.advertisedPriceMinor, candidate.currency) })}</dd></div>
      <div><dt>{t.vin}</dt><dd className="vin">{candidate.vin ?? t.notStated}</dd></div>
    </dl>
    <CandidateMarketAssessment check={candidate.marketCheck} />
    <p className="muted candidate-snapshot">{snapshot.observedAt
      ? fill(t.snapshotSeen, { source: snapshot.source, retrieved: moment(snapshot.retrievedAt), seen: moment(snapshot.observedAt) })
      : fill(t.snapshot, { source: snapshot.source, retrieved: moment(snapshot.retrievedAt) })}
      {snapshot.synthetic && <>{" "}{t.synthetic}</>}</p>
    {snapshot.staleness === "STALE" && <p className="badge badge-warn candidate-stale">{t.stale}</p>}
    {snapshot.staleness === "UNKNOWN" && <p className="badge badge-warn candidate-stale">{t.unknownSeen}</p>}
    {reasons(t.matches, candidate.matched)}
    {reasons(t.misses, candidate.missed)}
    {reasons(t.unconfirmed, candidate.unconfirmed)}
  </article>;
}

export function CandidateMarketAssessment({ check }: { check?: CandidateMarketCheck }) {
  const { m: { content: { candidateVehicles: { marketCheck: t } } }, fill, fillNodes, language } = useMessages();
  if (!check) return <p className="muted">{t.unavailable}</p>;
  const confidence = findingCode(language, check.confidence).toLocaleLowerCase(language);
  const retrieved = check.provider?.retrievedAt;
  const observed = check.vintage && { from: moment(check.vintage.oldestObservationAt), to: moment(check.vintage.newestObservationAt) };
  return <div className="candidate-market-check">
    <p>{fillNodes(t.title, { name: <strong>{t.name}</strong> })}</p>
    <p>{check.range ? fill(t.range, { low: amount(check.range.lowMinor, check.range.currency),
      high: amount(check.range.highMinor, check.range.currency), confidence }) : fill(t.noRange, { confidence })}</p>
    <p className="muted">{retrieved
      ? observed ? fill(t.retrievedObserved, { date: moment(retrieved), ...observed }) : fill(t.retrieved, { date: moment(retrieved) })
      : observed ? fill(t.unknownObserved, observed) : t.unknown}</p>
    {check.limitation && <p className="muted">{check.limitation}</p>}
  </div>;
}
