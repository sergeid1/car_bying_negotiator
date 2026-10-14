import { useEffect, useState } from "react";
import "./VehicleSafety.css";

export type VehicleSafetyView = {
  vin: string | null;
  facts: {
    status: "AVAILABLE" | "UNAVAILABLE" | "MISSING_VEHICLE";
    source: string; retrievedAt: string;
    vehicle: { year: number; make: string; model: string } | null;
    recalls: { campaignNumber: string; component: string; summary: string; consequence: string | null; remedy: string | null; reportDate: string | null }[];
    ratings: { vehicleId: string; name: string; overall: string | null; frontal: string | null; side: string | null; rollover: string | null; rolloverRisk: string | null }[];
  };
};

export function VehicleSafety({ caseId, token }: { caseId: string; token: string }) {
  const [view, setView] = useState<VehicleSafetyView>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    setView(undefined); setFailed(false);
    void fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/vehicle-safety`, {
      headers: { "Case-Access-Token": token },
    }).then(async response => {
      if (!response.ok) throw new Error("Request failed");
      const body = await response.json() as VehicleSafetyView;
      if (!body.facts || !["AVAILABLE", "UNAVAILABLE", "MISSING_VEHICLE"].includes(body.facts.status)) throw new Error("Answer unavailable");
      if (live) setView(body);
    }).catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [caseId, token]);
  return <details className="panel vehicle-safety" aria-label="Recalls and safety ratings">
    <summary><h2>Recalls and safety ratings</h2></summary>
    {view ? <VehicleSafetyFacts view={view} /> : failed
      ? <p>NHTSA did not answer; check <a href="https://www.nhtsa.gov/recalls">nhtsa.gov/recalls</a>.</p>
      : <p role="status">Looking up model recalls and ratings…</p>}
  </details>;
}

export function VehicleSafetyFacts({ view }: { view: VehicleSafetyView }) {
  const [copyStatus, setCopyStatus] = useState("");
  const { facts, vin } = view;
  const stars = (value: string | null) => value ? `${value} of 5 stars` : "Not rated";
  return <div className="vehicle-safety">
    {facts.vehicle && <p>{facts.vehicle.year} {facts.vehicle.make} {facts.vehicle.model}</p>}
    {facts.status === "MISSING_VEHICLE" ? <p>Confirm the car’s year, make and model to look up model recalls and ratings.</p>
      : facts.status === "UNAVAILABLE" ? <p>NHTSA did not answer; check nhtsa.gov/recalls. Recall counts and ratings are unavailable.</p>
      : <>
        <p>Source: {facts.source}. Retrieved <time dateTime={facts.retrievedAt}>{facts.retrievedAt}</time>.</p>
        <p>These recalls are listed for the model year, make and model. They do not show whether this VIN is affected or whether a repair was completed.</p>
        {facts.recalls.length === 0 ? <p>NHTSA returned zero model recall records for this lookup. Check this car’s VIN with NHTSA.</p>
          : <ul className="vehicle-safety-records">{facts.recalls.map((recall, index) => <li key={`${recall.campaignNumber}-${index}`}>
            <h3>Campaign {recall.campaignNumber}: {recall.component}</h3>
            <p>{recall.summary}</p>
            {recall.consequence && <p>Consequence: {recall.consequence}</p>}
            <p>Remedy: {recall.remedy ?? "Not supplied by NHTSA."}</p>
            {recall.reportDate && <p>Report date: {recall.reportDate}</p>}
            <p>Ask the dealer: Has campaign {recall.campaignNumber} been repaired on this car, and can you show the repair record?</p>
          </li>)}</ul>}
        <h3>Crash-test ratings</h3>
        {facts.ratings.length === 0 ? <p>NHTSA returned no rating variants for this model.</p>
          : <><p>All returned variants are shown. Match the description to the car; no variant has been selected for you.</p>
            <ul className="vehicle-safety-records">{facts.ratings.map(rating => <li key={rating.vehicleId}>
              <h4>{rating.name}</h4>
              <dl className="vehicle-safety-ratings">
                <div><dt>Overall</dt><dd>{stars(rating.overall)}</dd></div>
                <div><dt>Frontal</dt><dd>{stars(rating.frontal)}</dd></div>
                <div><dt>Side</dt><dd>{stars(rating.side)}</dd></div>
                <div><dt>Rollover</dt><dd>{stars(rating.rollover)}</dd></div>
                <div><dt>Rollover risk</dt><dd>{rating.rolloverRisk == null ? "Not supplied" : `${(Number(rating.rolloverRisk) * 100).toFixed(1)}%`}</dd></div>
              </dl>
            </li>)}</ul></>}</>}
    <p><a href="https://www.nhtsa.gov/recalls" target="_blank" rel="noreferrer">Check this car’s VIN at NHTSA (opens a new tab)</a>. Enter the VIN there; this tool does not query that lookup.</p>
    {vin && <div className="vehicle-safety-vin"><span>VIN: <code>{vin}</code></span>
      <button type="button" className="btn-secondary" onClick={async () => {
        try { await navigator.clipboard.writeText(vin); setCopyStatus("VIN copied."); }
        catch { setCopyStatus("Copy unavailable. Select the VIN and copy it manually."); }
      }}>Copy VIN</button>
      {copyStatus && <p role="status">{copyStatus}</p>}
    </div>}
  </div>;
}
