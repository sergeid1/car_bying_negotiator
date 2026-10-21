import "./DealerAddOns.css";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

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

const dollars = (minor: number) => format.money(minor, { minimumFractionDigits: minor % 100 === 0 ? 0 : 2 });

type Copy = Messages["paperwork"]["dealerAddOns"];

function heading(line: DealerAddOnLine, t: Copy, fill: ReturnType<typeof useMessages>["fill"]) {
  if (line.status === "RECOGNISED") return fill(t.recognised, { name: String(line.name?.replace(/^./, first => first.toUpperCase())) });
  if (line.status === "POSSIBLE") return fill(t.possible, { name: String(line.name) });
  return t.unrecognised;
}

/**
 * Every recognised add-on and extra dealer charge with its amount, what it is, whether it is usually optional and
 * the question to ask; an uncertain name is shown as "possibly", and an unrecognised dealer charge is listed too.
 */
export function DealerAddOnsNote({ check, question = true, title: given }: {
  check: DealerAddOnCheck; question?: boolean; title?: string;
}) {
  const { m: { paperwork: { dealerAddOns: t } }, fill, fillNodes } = useMessages();
  const title = given ?? t.title;
  const unverified = check.lines.some(line => !line.verified);
  return <div className="dealer-add-ons" aria-label={title}>
    <h3>{title}</h3>
    <p>{check.summary}</p>
    <ul>{check.lines.map((line, index) => <li className="dealer-add-on" data-status={line.status} key={`${line.label}-${index}`}>
      <p>{fillNodes(t.line, { label: <strong>{line.label}</strong>,
        amount: line.amountMinor === null ? t.amountNotRead : dollars(line.amountMinor), heading: heading(line, t, fill) })}
        {line.status === "RECOGNISED" && line.kindWording && <> {line.kindWording}</>}
        {line.confirmedByBuyer && <> {t.namedByYou}</>}</p>
      {line.status === "RECOGNISED" && line.what && <p className="muted">{line.what}</p>}
      {line.status === "RECOGNISED" && line.note && <p>{line.note}</p>}
      {question && <p className="dealer-question"><span className="dealer-question-label">{t.ask}</span>
        <q>{line.question}</q></p>}
    </li>)}</ul>
    {check.listingNote && <p>{check.listingNote}</p>}
    {unverified && <p className="muted">{t.unverified}</p>}
  </div>;
}
