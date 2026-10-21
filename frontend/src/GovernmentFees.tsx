import "./GovernmentFees.css";
import * as format from "./i18n/format";

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

function range(min: number, max: number | null) {
  if (max === null) return min === 0 ? "set by local rates" : `from ${dollars(min)}`;
  return min === max ? dollars(max) : `${dollars(min)} to ${dollars(max)}`;
}

/**
 * The state fee check as one plain statement, a question for each line to ask about, other notes, and what each
 * of the state's fees is for. It states the state's numbers and the paperwork's, nothing more.
 */
export function GovernmentFeeNote({ check, question = true, title = "State fees" }: {
  check: GovernmentFeeCheck; question?: boolean; title?: string;
}) {
  const asked = check.findings.filter(finding => finding.severity === "MATERIAL");
  const notes = check.findings.filter(finding => finding.severity !== "MATERIAL");
  const unverified = check.findings.some(finding => !finding.verified);
  return <div className="government-fee-check" data-status={asked.length > 0 ? "ASK" : check.status}>
    <h3>{title}</h3>
    <p>{check.summary}</p>
    {asked.map((finding, index) => <div className="government-fee-finding" key={`${finding.code}-${index}`}>
      {(index > 0 || finding.summary !== check.summary) && <p>{finding.summary}</p>}
      {question && finding.deskQuestion && <p className="dealer-question"><span className="dealer-question-label">Ask at the desk</span>
        <q>{finding.deskQuestion}</q></p>}
    </div>)}
    {notes.length > 0 && <details>
      <summary>{notes.length} {notes.length === 1 ? "note" : "notes"} on the state fee lines</summary>
      <ul>{notes.map((finding, index) => <li key={`${finding.code}-${index}`}>{finding.summary}
        {finding.deskQuestion && <> <q>{finding.deskQuestion}</q></>}</li>)}</ul>
    </details>}
    {check.fees.length > 0 && <details>
      <summary>What {check.stateName}'s fees are for</summary>
      <ul className="government-fee-list">{check.fees.map(fee => <li key={fee.name}>
        <strong>{fee.name}</strong> ({range(fee.minMinor, fee.maxMinor)}): {fee.purpose}
      </li>)}</ul>
      {check.note && <p className="muted">{check.note}</p>}
    </details>}
    <p className="muted">{check.status === "CHECKED"
      ? `From ${check.stateName}'s published fees, checked ${check.checkedOn}${unverified ? ", against paperwork numbers that are not yet verified" : ""}.`
      : "No amount on this paperwork was treated as checked."}</p>
  </div>;
}
