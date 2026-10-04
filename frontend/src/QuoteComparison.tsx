import { useEffect, useId, useState } from "react";
import { Arithmetic, Calculation, DocumentSource, DocumentSourceView, formatMoney } from "./EvidenceSource";

export type QuoteAmount = { label: string; minorUnits: number; currency: string;
  evidence: { documentSource: DocumentSource | null; sourceNote: string | null; calculation: Calculation | null } };
export type ComparisonCar = { candidateId: string; caseId: string; title: string; state: string; offerVersion: number | null;
  amounts: QuoteAmount[]; missing: string[]; noneListed?: string[]; marketPosition: string; reportVersion: number | null };
export type QuoteComparisonView = { cars: ComparisonCar[]; otdComparable: boolean };
type Order = "shortlist" | "selling" | "fees" | "addons" | "otd";
const prefixes = { selling: "Selling price", fees: "Dealer fees", addons: "Add-ons", otd: "Out-the-door total" };
function total(car: ComparisonCar, order: Exclude<Order, "shortlist">) {
  if (car.missing.includes(prefixes[order])) return null;
  const amounts = car.amounts.filter(amount => amount.label === prefixes[order] || amount.label.startsWith(`${prefixes[order]}:`));
  return amounts.length ? amounts.reduce((sum, amount) => sum + amount.minorUnits, 0) : null;
}

export function QuoteComparison({ journeyId, session, onSessionEnded }: {
  journeyId: string; session: string; onSessionEnded: () => void;
}) {
  const id = useId();
  const [view, setView] = useState<QuoteComparisonView>();
  const [order, setOrder] = useState<Order>("shortlist");
  const [revision, setRevision] = useState(0);
  const [status, setStatus] = useState("Loading quote comparison…");
  useEffect(() => {
    let active = true;
    setView(undefined); setStatus("Loading quote comparison…");
    fetch(`/api/v1/journeys/${encodeURIComponent(journeyId)}/quote-comparison`, { headers: { "Account-Session-Token": session } })
      .then(async response => {
        if (!active) return;
        if (response.status === 401) { onSessionEnded(); return; }
        if (!response.ok) throw new Error();
        const saved = await response.json() as QuoteComparisonView;
        if (active) { setView(saved); setStatus(""); if (!saved.otdComparable) setOrder(previous => previous === "otd" ? "shortlist" : previous); }
      }).catch(() => { if (active) setStatus("Quote comparison is unavailable. Try refreshing it."); });
    return () => { active = false; };
  }, [journeyId, session, revision]);
  const cars = [...(view?.cars ?? [])];
  if (order !== "shortlist") cars.sort((a, b) => {
    const left = total(a, order), right = total(b, order);
    return left == null ? right == null ? 0 : 1 : right == null ? -1 : left - right;
  });
  return <section className="quote-comparison" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>Compare verified quotes</h3>
    <p>Only verified Dealer Offers appear here. Out-the-door totals are purchase charges before trade, cash down or financing.</p>
    <button type="button" className="btn-secondary" onClick={() => setRevision(value => value + 1)}>Refresh quote comparison</button>
    {status && <p role="status">{status}</p>}
    {view && <>
      {!cars.length ? <p>Open Buying Cases from your shortlist to compare their quotes.</p> : <>
        <label>Order quotes by
          <select value={order} onChange={event => setOrder(event.target.value as Order)}>
            <option value="shortlist">Your shortlist order</option>
            <option value="selling">Selling price, lowest first</option>
            <option value="fees">Dealer fees, lowest first</option>
            <option value="addons">Add-ons, lowest first</option>
            <option value="otd" disabled={!view.otdComparable}>Out-the-door total, lowest first</option>
          </select>
        </label>
        <p>{view.otdComparable ? "Every verified quoted OTD reconciles within the recorded tolerance. You can order by OTD total."
          : "OTD totals cannot be compared yet. Each car explains what needs review; an absent charge in an unreconciled quote is never treated as zero."}</p>
        <p className="muted">Ordering is your choice. Cars with missing figures follow those with figures. Market ranges are stored estimates of selling price.</p>
        <div className="quote-comparison-cars">
          {cars.map(car => <article key={car.caseId} aria-label={car.title}>
            <h4>{car.title}</h4>
            <p>{car.state}{car.offerVersion != null && ` · Dealer Offer version ${car.offerVersion}`}</p>
            {car.missing.length > 0 && <p>OTD comparison unavailable: {car.missing.join("; ")}.</p>}
            <p>{car.marketPosition}{car.reportVersion != null && ` Report version ${car.reportVersion}.`}</p>
            <dl>
              {(car.noneListed ?? []).map(category => <div key={category}><dt>{category}</dt><dd>None listed</dd></div>)}
              {car.amounts.map((amount, index) => <div key={index}>
                <dt>{amount.label}</dt>
                <dd>{formatMoney(amount.minorUnits, amount.currency)} · <a href={`#${id}-${car.caseId}-${index}`}
                  onClick={() => { const evidence = document.getElementById(`${id}-${car.caseId}-${index}`); if (evidence instanceof HTMLDetailsElement) evidence.open = true; }}>
                  Source for {amount.label}</a></dd>
              </div>)}
            </dl>
            {car.amounts.map((amount, index) => <details key={index} className="amount-evidence" id={`${id}-${car.caseId}-${index}`}>
              <summary>{amount.label} evidence</summary>
              {amount.evidence.documentSource
                ? <DocumentSourceView source={amount.evidence.documentSource} what={amount.label} />
                : <p>{amount.evidence.sourceNote ?? "No document region is recorded for this amount."}</p>}
              {amount.evidence.calculation && <Arithmetic calculation={amount.evidence.calculation} result={amount.minorUnits}
                label={amount.label} currency={amount.currency} />}
            </details>)}
          </article>)}
        </div>
      </>}
    </>}
  </section>;
}
