import { useEffect, useState } from "react";
import "./VehicleSafety.css";
import { useMessages } from "./i18n/LanguageProvider";

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
  const { m: { paperwork: { vehicleSafety: t } }, fillNodes } = useMessages();
  const [view, setView] = useState<VehicleSafetyView>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    setView(undefined); setFailed(false);
    void fetch(`/api/v1/cases/${encodeURIComponent(caseId)}/vehicle-safety`, {
      headers: { "Case-Access-Token": token },
    }).then(async response => {
      if (!response.ok) throw new Error("safety-request-failed");
      const body = await response.json() as VehicleSafetyView;
      if (!body.facts || !["AVAILABLE", "UNAVAILABLE", "MISSING_VEHICLE"].includes(body.facts.status)) throw new Error("safety-answer-unavailable");
      if (live) setView(body);
    }).catch(() => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [caseId, token]);
  return <details className="panel vehicle-safety" aria-label={t.title}>
    <summary><h2>{t.title}</h2></summary>
    {view ? <VehicleSafetyFacts view={view} /> : failed
      ? <p>{fillNodes(t.noAnswerLink, { link: <a href="https://www.nhtsa.gov/recalls">{t.nhtsaSite}</a> })}</p>
      : <p role="status">{t.lookingUp}</p>}
  </details>;
}

export function VehicleSafetyFacts({ view }: { view: VehicleSafetyView }) {
  const { m: { paperwork: { vehicleSafety: t } }, fill, fillNodes } = useMessages();
  const [copyStatus, setCopyStatus] = useState("");
  const { facts, vin } = view;
  const stars = (value: string | null) => value ? fill(t.stars, { rating: value }) : t.notRated;
  return <div className="vehicle-safety">
    {facts.vehicle && <p>{facts.vehicle.year} {facts.vehicle.make} {facts.vehicle.model}</p>}
    {facts.status === "MISSING_VEHICLE" ? <p>{t.confirmVehicle}</p>
      : facts.status === "UNAVAILABLE" ? <p>{t.unavailable}</p>
      : <>
        <p>{fillNodes(t.source, { source: facts.source, time: <time dateTime={facts.retrievedAt}>{facts.retrievedAt}</time> })}</p>
        <p>{t.modelLevel}</p>
        {facts.recalls.length === 0 ? <p>{t.noRecalls}</p>
          : <ul className="vehicle-safety-records">{facts.recalls.map((recall, index) => <li key={`${recall.campaignNumber}-${index}`}>
            <h3>{fill(t.campaign, { number: recall.campaignNumber, component: recall.component })}</h3>
            <p>{recall.summary}</p>
            {recall.consequence && <p>{fill(t.consequence, { consequence: recall.consequence })}</p>}
            <p>{fill(t.remedy, { remedy: recall.remedy ?? t.remedyMissing })}</p>
            {recall.reportDate && <p>{fill(t.reportDate, { date: recall.reportDate })}</p>}
            <p>{fill(t.askRepair, { number: recall.campaignNumber })}</p>
          </li>)}</ul>}
        <h3>{t.ratingsHeading}</h3>
        {facts.ratings.length === 0 ? <p>{t.noRatings}</p>
          : <><p>{t.allVariants}</p>
            <ul className="vehicle-safety-records">{facts.ratings.map(rating => <li key={rating.vehicleId}>
              <h4>{rating.name}</h4>
              <dl className="vehicle-safety-ratings">
                <div><dt>{t.overall}</dt><dd>{stars(rating.overall)}</dd></div>
                <div><dt>{t.frontal}</dt><dd>{stars(rating.frontal)}</dd></div>
                <div><dt>{t.side}</dt><dd>{stars(rating.side)}</dd></div>
                <div><dt>{t.rollover}</dt><dd>{stars(rating.rollover)}</dd></div>
                <div><dt>{t.rolloverRisk}</dt><dd>{rating.rolloverRisk == null ? t.notSupplied : `${(Number(rating.rolloverRisk) * 100).toFixed(1)}%`}</dd></div>
              </dl>
            </li>)}</ul></>}</>}
    <p>{fillNodes(t.checkVin, { link: <a href="https://www.nhtsa.gov/recalls" target="_blank" rel="noreferrer">{t.checkVinLink}</a> })}</p>
    {vin && <div className="vehicle-safety-vin"><span>{fillNodes(t.vin, { vin: <code>{vin}</code> })}</span>
      <button type="button" className="btn-secondary" onClick={async () => {
        try { await navigator.clipboard.writeText(vin); setCopyStatus(t.copied); }
        catch { setCopyStatus(t.copyFailed); }
      }}>{t.copy}</button>
      {copyStatus && <p role="status">{copyStatus}</p>}
    </div>}
  </div>;
}
