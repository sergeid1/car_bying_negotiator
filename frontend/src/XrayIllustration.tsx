import { money } from "./i18n/format";
import { type Messages, useMessages } from "./i18n/LanguageProvider";

/** Example lines only: the hero shows what a review looks like, never a real Buyer's figures. */
const LINES: { name: keyof Messages["xray"]; minor: number; question?: number }[] = [
  { name: "sellingPrice", minor: 2640000 },
  { name: "docFee", minor: 69900, question: 2 },
  { name: "paintProtection", minor: 119500, question: 1 },
  { name: "highwayUseTax", minor: 79200 },
  { name: "titleAndRegistration", minor: 12600 },
];

/** A decorative example buyer's order with a scan line passing over it and two lines flagged. */
export function XrayIllustration() {
  const { m, fill } = useMessages();
  const x = m.xray;
  return <div className="hero-visual" aria-hidden="true">
    <div className="xray-tilt">
      <div className="xray-doc">
        <div className="xray-scan" />
        <div className="xray-head"><span>{x.heading}</span><span>{x.page}</span></div>
        <div className="xray-vehicle">{x.vehicle}</div>
        <div className="xray-dealer">{x.dealer}</div>
        {LINES.map(line => <div key={line.name} className={line.question ? "xray-line xray-flagged" : "xray-line"}>
          <span>{x[line.name]}</span>
          <span className="xray-leader" />
          <span className="xray-amount">{money(line.minor)}</span>
          {line.question && <span className="xray-tag">{fill(x.question, { number: line.question })}</span>}
        </div>)}
        <div className="xray-total"><span>{x.total}</span><span className="xray-amount">{money(2921200)}</span></div>
      </div>
    </div>
  </div>;
}
