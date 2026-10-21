import "./GovernmentFees.css";
import * as format from "./i18n/format";
import { useMessages, type Messages } from "./i18n/LanguageProvider";

/** One state fee line checked against the state's fee schedule (ticket 139). */
export type GovernmentFeeFinding = {
  code: string; severity: "MATERIAL" | "INFORMATIONAL"; label: string; amountMinor: number | null;
  lowestMinor: number | null; highestMinor: number | null; summary: string; deskQuestion: string | null;
  dependsOn: string[]; notCounted: string[]; verified: boolean;
};
export type GovernmentFeeCheck = {
  policyVersion: string; scheduleVersion: string; state: string | null; stateName: string | null;
  status: "CHECKED" | "NOT_CHECKED"; checkedOn: string | null; reviewOn: string | null; stateFeesMinor: number;
  highestMinor: number | null; findings: GovernmentFeeFinding[]; summary: string; scope: string | null; note: string | null;
  fees: { name: string; purpose: string; minMinor: number; maxMinor: number | null; dependsOn: string; citation: string }[];
};

const dollars = (minor: number) => format.money(minor, { minimumFractionDigits: minor % 100 === 0 ? 0 : 2 });

type Copy = Messages["paperwork"]["governmentFees"];

function range(min: number, max: number | null, t: Copy, fill: ReturnType<typeof useMessages>["fill"]) {
  if (max === null) return min === 0 ? t.localRates : fill(t.from, { amount: dollars(min) });
  return min === max ? dollars(max) : fill(t.between, { low: dollars(min), high: dollars(max) });
}

/**
 * The state fee check as one plain statement, a question for each line to ask about, other notes, and what each
 * of the state's fees is for. It states the state's numbers and the paperwork's, nothing more.
 */
export function GovernmentFeeNote({ check, question = true, title: given }: {
  check: GovernmentFeeCheck; question?: boolean; title?: string;
}) {
  const { m: { paperwork: { governmentFees: t } }, fill, fillNodes } = useMessages();
  const title = given ?? t.title;
  const asked = check.findings.filter(finding => finding.severity === "MATERIAL");
  const notes = check.findings.filter(finding => finding.severity !== "MATERIAL");
  const unverified = check.findings.some(finding => !finding.verified);
  return <div className="government-fee-check" data-status={asked.length > 0 ? "ASK" : check.status}>
    <h3>{title}</h3>
    <p>{check.summary}</p>
    {asked.map((finding, index) => <div className="government-fee-finding" key={`${finding.code}-${index}`}>
      {(index > 0 || finding.summary !== check.summary) && <p>{finding.summary}</p>}
      {question && finding.deskQuestion && <p className="dealer-question"><span className="dealer-question-label">{t.askAtDesk}</span>
        <q>{finding.deskQuestion}</q></p>}
    </div>)}
    {notes.length > 0 && <details>
      <summary>{fill(t.notes, { count: notes.length })}</summary>
      <ul>{notes.map((finding, index) => <li key={`${finding.code}-${index}`}>{finding.summary}
        {finding.deskQuestion && <> <q>{finding.deskQuestion}</q></>}</li>)}</ul>
    </details>}
    {check.fees.length > 0 && <details>
      <summary>{fill(t.feesFor, { state: String(check.stateName) })}</summary>
      <ul className="government-fee-list">{check.fees.map(fee => <li key={fee.name}>
        {fillNodes(t.fee, { name: <strong>{fee.name}</strong>, range: range(fee.minMinor, fee.maxMinor, t, fill), purpose: fee.purpose })}
      </li>)}</ul>
      {check.note && <p className="muted">{check.note}</p>}
    </details>}
    <p className="muted">{check.status === "CHECKED"
      ? fill(unverified ? t.checkedUnverified : t.checked, { state: String(check.stateName), date: String(check.checkedOn) })
      : t.notChecked}</p>
  </div>;
}
