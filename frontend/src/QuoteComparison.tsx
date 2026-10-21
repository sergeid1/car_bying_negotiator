import { useEffect, useId, useState } from "react";
import { Arithmetic, Calculation, DocumentSource, DocumentSourceView, formatMoney } from "./EvidenceSource";
import { useMessages } from "./i18n/LanguageProvider";

export type QuoteAmount = { label: string; minorUnits: number; currency: string;
  evidence: { documentSource: DocumentSource | null; sourceNote: string | null; calculation: Calculation | null } };
export type ComparisonCar = { candidateId: string; caseId: string; title: string; state: string; offerVersion: number | null;
  amounts: QuoteAmount[]; missing: string[]; noneListed?: string[]; marketPosition: string; reportVersion: number | null };
export type QuoteComparisonView = { cars: ComparisonCar[]; otdComparable: boolean };
type Order = "shortlist" | "selling" | "fees" | "addons" | "otd";
/** The labels the server gives each comparable charge; they are matched, not shown. */
const prefixes = { selling: "Selling price", fees: "Dealer fees", addons: "Add-ons", otd: "Out-the-door total" };
function total(car: ComparisonCar, order: Exclude<Order, "shortlist">) {
  if (car.missing.includes(prefixes[order])) return null;
  if (car.noneListed?.includes(prefixes[order])) return 0;
  const amounts = car.amounts.filter(amount => amount.label === prefixes[order] || amount.label.startsWith(`${prefixes[order]}:`));
  return amounts.length ? amounts.reduce((sum, amount) => sum + amount.minorUnits, 0) : null;
}

export function QuoteComparison({ journeyId, onSessionEnded }: {
  journeyId: string; onSessionEnded: () => void;
}) {
  const { m: { messages: { quoteComparison: t }, paperwork: { common: { unknown } } }, fill } = useMessages();
  const id = useId();
  const [view, setView] = useState<QuoteComparisonView>();
  const [order, setOrder] = useState<Order>("shortlist");
  const [revision, setRevision] = useState(0);
  const [status, setStatus] = useState<"loading" | "failed" | "">("loading");
  useEffect(() => {
    let active = true;
    setView(undefined); setStatus("loading");
    fetch(`/api/v1/journeys/${encodeURIComponent(journeyId)}/quote-comparison`)
      .then(async response => {
        if (!active) return;
        if (response.status === 401) { onSessionEnded(); return; }
        if (!response.ok) throw new Error();
        const saved = await response.json() as QuoteComparisonView;
        if (active) { setView(saved); setStatus(""); if (!saved.otdComparable) setOrder(previous => previous === "otd" ? "shortlist" : previous); }
      }).catch(() => { if (active) setStatus("failed"); });
    return () => { active = false; };
  }, [journeyId, revision]);
  const cars = [...(view?.cars ?? [])];
  if (order !== "shortlist") cars.sort((a, b) => {
    const left = total(a, order), right = total(b, order);
    return left == null ? right == null ? 0 : 1 : right == null ? -1 : left - right;
  });
  return <section className="quote-comparison" aria-labelledby={`${id}-heading`}>
    <h3 id={`${id}-heading`}>{t.heading}</h3>
    <p>{t.intro}</p>
    <button type="button" className="btn-secondary" onClick={() => setRevision(value => value + 1)}>{t.refresh}</button>
    {status && <p role="status">{status === "loading" ? t.loading : t.unavailable}</p>}
    {view && <>
      {!cars.length ? <p>{t.empty}</p> : <>
        <label>{t.orderBy}
          <select value={order} onChange={event => setOrder(event.target.value as Order)}>
            <option value="shortlist">{t.orders.shortlist}</option>
            <option value="selling">{t.orders.selling}</option>
            <option value="fees">{t.orders.fees}</option>
            <option value="addons">{t.orders.addons}</option>
            <option value="otd" disabled={!view.otdComparable}>{t.orders.otd}</option>
          </select>
        </label>
        <p>{view.otdComparable ? t.otdComparable : t.otdNotComparable}</p>
        <p className="muted">{t.orderingNote}</p>
        <div className="quote-comparison-cars">
          {cars.map(car => <article key={car.caseId} aria-label={car.title}>
            <h4>{car.title}</h4>
            <p>{car.state}{car.offerVersion != null && <> · {fill(t.offerVersion, { version: String(car.offerVersion) })}</>}</p>
            {car.missing.length > 0 && <p>{fill(t.otdUnavailable, { missing: car.missing.join("; ") })}</p>}
            <p>{car.marketPosition}{car.reportVersion != null && <>{" "}{fill(t.reportVersion, { version: String(car.reportVersion) })}</>}</p>
            <dl>
              {(car.noneListed ?? []).map(category => <div key={category}><dt>{category}</dt><dd>{t.noneListed}</dd></div>)}
              {car.amounts.map((amount, index) => <div key={index}>
                <dt>{amount.label}</dt>
                <dd>{formatMoney(amount.minorUnits, amount.currency, unknown)} · <a href={`#${id}-${car.caseId}-${index}`}
                  onClick={() => { const evidence = document.getElementById(`${id}-${car.caseId}-${index}`); if (evidence instanceof HTMLDetailsElement) evidence.open = true; }}>
                  {fill(t.sourceFor, { label: amount.label })}</a></dd>
              </div>)}
            </dl>
            {car.amounts.map((amount, index) => <details key={index} className="amount-evidence" id={`${id}-${car.caseId}-${index}`}>
              <summary>{fill(t.evidence, { label: amount.label })}</summary>
              {amount.evidence.documentSource
                ? <DocumentSourceView source={amount.evidence.documentSource} what={amount.label} />
                : <p>{amount.evidence.sourceNote ?? t.noDocumentRegion}</p>}
              {amount.evidence.calculation && <Arithmetic calculation={amount.evidence.calculation} result={amount.minorUnits}
                label={amount.label} currency={amount.currency} />}
            </details>)}
          </article>)}
        </div>
      </>}
    </>}
  </section>;
}
