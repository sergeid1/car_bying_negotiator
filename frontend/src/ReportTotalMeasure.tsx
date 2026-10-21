import { useEffect, useRef, useState } from "react";
import type { CoreReport } from "./CoreDealReport";
import { useMessages } from "./i18n/LanguageProvider";

export function ReportTotalMeasure({ report, caseId, token }: { report: CoreReport; caseId: string; token: string }) {
  const { m: { paperwork: { reportTotalMeasure: t } } } = useMessages();
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
    if (!response.ok) throw new Error(t.unavailable);
    return response.json();
  }
  useEffect(() => {
    if (session.current) return;
    const pending = post(base, { reportVersion: report.version }).then(body => body.id as string);
    session.current = pending;
    void pending.catch(() => setStatus(t.reopen));
  }, [base, report.version, token]);
  return <details className="panel"><summary>{t.summary}</summary>
    <form onSubmit={async event => {
      event.preventDefault(); setBusy(true);
      const durationMillis = Math.round(performance.now() - opened.current);
      try {
        const id = await session.current;
        if (!id) throw new Error(t.reopen);
        const result = await post(`${base}/${encodeURIComponent(id)}`, { unknown, answerMinor: unknown ? null : Math.round(Number(answer) * 100), durationMillis });
        setSaved(result.correct); setStatus(result.correct ? t.correct : t.incorrect);
      } catch (error) { setStatus((error as Error).message); } finally { setBusy(false); }
    }}>
      <p>{t.intro}</p>
      <label>{t.total}<input type="number" min="0" step="0.01" required={!unknown} disabled={unknown || saved || busy} value={answer} onChange={event => setAnswer(event.target.value)} /></label>
      <label><input type="checkbox" checked={unknown} disabled={saved || busy} onChange={event => setUnknown(event.target.checked)} />{t.unknown}</label>
      <button disabled={saved || busy}>{t.check}</button>
      {status && <p role="status">{status}</p>}
    </form>
  </details>;
}
