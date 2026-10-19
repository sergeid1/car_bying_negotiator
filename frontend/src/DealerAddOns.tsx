import "./DealerAddOns.css";

/** One add-on or extra dealer charge, named from the catalogue or listed as unrecognised (ticket 142). */
export type DealerAddOnLine = {
  label: string; amountMinor: number | null; status: "RECOGNISED" | "POSSIBLE" | "UNRECOGNISED"; chargeIds: string[];
  name: string | null; family: string | null; kind: string | null; what: string | null; kindWording: string | null;
  note: string | null; question: string; optional: boolean; confirmedByBuyer: boolean; verified: boolean;
};
export type DealerAddOnCheck = {
  policyVersion: string; catalogueVersion: string; financed: boolean | null; lines: DealerAddOnLine[];
  optionalTotalMinor: number; possibleTotalMinor: number; unrecognisedTotalMinor: number; unreadAmounts: number;
  summary: string; listingNote: string | null;
};

const dollars = (minor: number) => new Intl.NumberFormat("en-US", { style: "currency", currency: "USD",
  minimumFractionDigits: minor % 100 === 0 ? 0 : 2 }).format(minor / 100);

function heading(line: DealerAddOnLine) {
  if (line.status === "RECOGNISED") return `${line.name?.replace(/^./, first => first.toUpperCase())}.`;
  if (line.status === "POSSIBLE") return `Possibly ${line.name}.`;
  return "Unrecognised charge — ask what it is.";
}

/**
 * Every recognised add-on and extra dealer charge with its amount, what it is, whether it is usually optional and
 * the question to ask; an uncertain name is shown as "possibly", and an unrecognised dealer charge is listed too.
 */
export function DealerAddOnsNote({ check, question = true, title = "Add-ons and extra dealer charges" }: {
  check: DealerAddOnCheck; question?: boolean; title?: string;
}) {
  const unverified = check.lines.some(line => !line.verified);
  return <div className="dealer-add-ons" aria-label={title}>
    <h3>{title}</h3>
    <p>{check.summary}</p>
    <ul>{check.lines.map((line, index) => <li className="dealer-add-on" data-status={line.status} key={`${line.label}-${index}`}>
      <p><strong>{line.label}</strong>, {line.amountMinor === null ? "amount not read" : dollars(line.amountMinor)}: {heading(line)}
        {line.status === "RECOGNISED" && line.kindWording && <> {line.kindWording}</>}
        {line.confirmedByBuyer && <> You named this charge.</>}</p>
      {line.status === "RECOGNISED" && line.what && <p className="muted">{line.what}</p>}
      {line.status === "RECOGNISED" && line.note && <p>{line.note}</p>}
      {question && <p className="dealer-question"><span className="dealer-question-label">Ask the dealer</span>
        <q>{line.question}</q></p>}
    </li>)}</ul>
    {check.listingNote && <p>{check.listingNote}</p>}
    {unverified && <p className="muted">Read from the paperwork; these lines are not yet verified.</p>}
  </div>;
}
