import { useRef, useState } from "react";

type Reason = { kind: "MUST_HAVE" | "NICE_TO_HAVE" | "REFERENCE"; text: string };
type Snapshot = { source: string; retrievedAt: string; observedAt: string | null; staleness: "CURRENT" | "STALE" | "UNKNOWN";
  synthetic: boolean };
export type CandidateVehicle = { listingId: string; vin: string | null; title: string; year: number | null; make: string | null;
  model: string | null; trim: string | null; mileage: number | null; distanceMiles: number | null;
  advertisedPriceMinor: number | null; currency: string | null; matched: Reason[]; missed: Reason[]; unconfirmed: Reason[];
  snapshot: Snapshot };
type Excluded = { listingId: string; vin: string | null; title: string; missed: Reason[]; snapshot: Snapshot };
export type CandidateResults = { matcherVersion: string; briefVersion: number; referenceListVersion: string;
  availability: "AVAILABLE" | "UNAVAILABLE" | "TIMED_OUT" | "RATE_LIMITED"; notice: string | null;
  provider: { name: string; version: string; licence: string; synthetic: boolean }; retrievedAt: string; ordering: string;
  candidates: CandidateVehicle[]; excluded: Excluded[] };
type Problem = { status?: number; detail?: string };

const moment = (iso: string | null) => {
  const date = iso ? new Date(iso) : undefined;
  return !date || Number.isNaN(date.getTime()) ? "" : date.toLocaleString("en-US", { month: "short", day: "numeric",
    year: "numeric", hour: "numeric", minute: "2-digit" });
};
const amount = (minor: number, currency: string | null) => {
  const whole = minor % 100 === 0;
  const shown = (minor / 100).toLocaleString("en-US", { minimumFractionDigits: whole ? 0 : 2, maximumFractionDigits: 2 });
  return !currency || currency === "USD" ? `$${shown}` : `${shown} ${currency}`;
};

/**
 * Inventory search results for the current Shopping Brief as Candidate Vehicles. Each one says in plain
 * words which criteria and reference details it matches, misses, or the listing does not state; the
 * order comes only from the brief, the Reference Vehicles and what each listing states.
 */
export function CandidateVehicles({ briefVersion, search, onSessionEnded }: {
  briefVersion: number; search: () => Promise<CandidateResults>; onSessionEnded: () => void;
}) {
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
      else setError(problem?.detail ?? "Inventory could not be searched. Retry safely; nothing was saved.");
    } finally { setBusy(false); }
  }

  const count = results?.candidates.length ?? 0;
  return <section className="candidate-vehicles" aria-labelledby="candidate-vehicles-heading">
    <h3 id="candidate-vehicles-heading">Candidate Vehicles</h3>
    <p>Search licensed inventory for brief version {briefVersion}. Each candidate lists which of your criteria and reference
      details it matches, which it misses, and which the listing does not state. There is no hidden score, and the search
      shares nothing about you.</p>
    <button disabled={busy} onClick={run}>{results ? "Search inventory again" : "Search inventory for candidates"}</button>
    {busy && <p role="status">Searching inventory…</p>}
    {error && <p role="alert">{error}</p>}
    {results && <div className="candidate-results">
      <h4 ref={heading} tabIndex={-1}>{results.availability !== "AVAILABLE" ? "No search results"
        : `${count} ${count === 1 ? "candidate" : "candidates"} for brief version ${results.briefVersion}`}</h4>
      {results.notice && <p className="notice">{results.notice}</p>}
      {results.availability === "AVAILABLE" && <>
        <p className="muted">{results.ordering}</p>
        <p className="muted">Searched {results.provider.name} (version {results.provider.version}) on {moment(results.retrievedAt)};
          matched with {results.matcherVersion}.</p>
        {count === 0 ? <p>No listing meets your must-have criteria. Widening the brief may find more.</p>
          : <ol className="candidate-list" aria-label={`Candidate Vehicles, brief version ${results.briefVersion}`}>
            {results.candidates.map((candidate, index) => <li key={`${candidate.snapshot.source}-${candidate.listingId}`}>
              <CandidateCard candidate={candidate} position={index + 1} />
            </li>)}
          </ol>}
        {results.excluded.length > 0 && <details className="candidate-excluded">
          <summary>{results.excluded.length} {results.excluded.length === 1 ? "listing was" : "listings were"} left out for missing
            a must-have</summary>
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

function CandidateCard({ candidate, position }: { candidate: CandidateVehicle; position: number }) {
  const { snapshot } = candidate;
  const name = `Candidate ${position}: ${candidate.title}`;
  const reasons = (label: string, list: Reason[]) => list.length > 0 && <div className="candidate-reasons">
    <h6>{label}</h6>
    <ul>{list.map(reason => <li key={reason.text}>{reason.text}</li>)}</ul>
  </div>;
  return <article className="candidate" aria-label={name}>
    <h5>{name}</h5>
    <dl className="facts" aria-label={`Listing details of candidate ${position}`}>
      <div><dt>Mileage</dt><dd>{candidate.mileage === null ? "Not stated" : `${candidate.mileage.toLocaleString("en-US")} miles`}</dd></div>
      <div><dt>Distance</dt><dd>{candidate.distanceMiles === null ? "Not stated" : `${candidate.distanceMiles} miles`}</dd></div>
      <div><dt>Advertised price</dt><dd>{candidate.advertisedPriceMinor === null ? "Not stated"
        : `${amount(candidate.advertisedPriceMinor, candidate.currency)} (advertised, not a quote)`}</dd></div>
      <div><dt>VIN</dt><dd className="vin">{candidate.vin ?? "Not stated"}</dd></div>
    </dl>
    <p className="muted candidate-snapshot">Listing Snapshot from {snapshot.source}, retrieved {moment(snapshot.retrievedAt)}
      {snapshot.observedAt && `; listing last seen ${moment(snapshot.observedAt)}`}.
      {snapshot.synthetic && " Synthetic listing, not for sale."}</p>
    {snapshot.staleness === "STALE" && <p className="badge badge-warn candidate-stale">Stale listing: last seen over a week
      before this search, so it may be gone or changed.</p>}
    {snapshot.staleness === "UNKNOWN" && <p className="badge badge-warn candidate-stale">The listing does not say when it was
      last seen.</p>}
    {reasons("Matches", candidate.matched)}
    {reasons("Misses", candidate.missed)}
    {reasons("Not stated in the listing", candidate.unconfirmed)}
  </article>;
}
