import { useEffect, useRef, useState } from "react";
import type { CoreReport } from "./CoreDealReport";

export function ReportTotalMeasure({ report, caseId, token }: { report: CoreReport; caseId: string; token: string }) {
  const session = useRef<Promise<string> | undefined>(undefined);
  const opened = useRef(performance.now());
  const [answer, setAnswer] = useState("");
  const [unknown, setUnknown] = useState(false);
  const [status, setStatus] = useState("");
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const base = `/api/v1/cases/${encodeURIComponent(caseId)}/feedback/report-total`;
  async function post(path: string, body: object) {
    const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json", "Case-Access-Token": token }, body: JSON.stringify(body) });
    if (!response.ok) throw new Error("Measure unavailable. Try again.");
    return response.json();
  }
  useEffect(() => {
    if (session.current) return;
    const pending = post(base, { reportVersion: report.version }).then(body => body.id as string);
    session.current = pending;
    void pending.catch(() => setStatus("Measure unavailable. Reopen the report to try again."));
  }, [base, report.version, token]);
  return <details className="panel"><summary>Optional pilot check: find the total</summary>
    <form onSubmit={async event => {
      event.preventDefault(); setBusy(true);
      const durationMillis = Math.round(performance.now() - opened.current);
      try {
        const id = await session.current;
        if (!id) throw new Error("Measure unavailable. Reopen the report to try again.");
        const result = await post(`${base}/${encodeURIComponent(id)}`, { unknown, answerMinor: unknown ? null : Math.round(Number(answer) * 100), durationMillis });
        setSaved(result.correct); setStatus(result.correct ? "Total correctly identified. Pilot time saved." : "That does not match the saved report. Check its out-the-door total and try again.");
      } catch (error) { setStatus((error as Error).message); } finally { setBusy(false); }
    }}>
      <p>Enter the out-the-door total you found in this report. Only correctness and elapsed time are retained.</p>
      <label>Total in the report (USD)<input type="number" min="0" step="0.01" required={!unknown} disabled={unknown || saved || busy} value={answer} onChange={event => setAnswer(event.target.value)} /></label>
      <label><input type="checkbox" checked={unknown} disabled={saved || busy} onChange={event => setUnknown(event.target.checked)} />The total is unknown</label>
      <button disabled={saved || busy}>Check total</button>
      {status && <p role="status">{status}</p>}
    </form>
  </details>;
}
