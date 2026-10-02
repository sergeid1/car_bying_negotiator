/** Example lines only: the hero shows what a review looks like, never a real Buyer's figures. */
const LINES: { name: string; amount: string; flag?: string }[] = [
  { name: "Selling price", amount: "$26,400.00" },
  { name: "Documentation fee", amount: "$699.00", flag: "Question 2" },
  { name: "Paint protection", amount: "$1,195.00", flag: "Question 1" },
  { name: "Highway use tax", amount: "$792.00" },
  { name: "Title and registration", amount: "$126.00" },
];

/** A decorative example buyer's order with a scan line passing over it and two lines flagged. */
export function XrayIllustration() {
  return <div className="hero-visual" aria-hidden="true">
    <div className="xray-tilt">
      <div className="xray-doc">
        <div className="xray-scan" />
        <div className="xray-head"><span>Example buyer's order</span><span>Page 1</span></div>
        <div className="xray-vehicle">2024 Toyota RAV4 XLE</div>
        <div className="xray-dealer">Example Motors</div>
        {LINES.map(line => <div key={line.name} className={line.flag ? "xray-line xray-flagged" : "xray-line"}>
          <span>{line.name}</span>
          <span className="xray-leader" />
          <span className="xray-amount">{line.amount}</span>
          {line.flag && <span className="xray-tag">{line.flag}</span>}
        </div>)}
        <div className="xray-total"><span>Out-the-door total</span><span className="xray-amount">$29,212.00</span></div>
      </div>
    </div>
  </div>;
}
