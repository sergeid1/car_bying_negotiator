import { useState } from "react";
import * as format from "./i18n/format";
import { useMessages } from "./i18n/LanguageProvider";

type Receipt = { state: "DELETION_PENDING" | "DELETED"; attempts?: number; failureCode?: string; backupStatus: string };
export function CaseLifecycle({ caseId, token, onRevoked }: { caseId: string; token: string; onRevoked: () => void }) {
  const { m: { account: { caseLifecycle: t } }, fill } = useMessages();
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [receipt, setReceipt] = useState<Receipt>();
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const base = `/api/v1/cases/${encodeURIComponent(caseId)}`;
  async function deletion(method: "POST" | "GET") {
    setBusy(true); setError("");
    try {
      const response = await fetch(`${base}/deletion`, { method, headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.deletionFailed);
      setReceipt(await response.json()); onRevoked();
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <section className="panel" aria-label={t.label}>
    <h2>{t.heading}</h2>
    {receipt ? <>
      <p role="status">{receipt.state === "DELETED" ? t.deletionVerified : t.deletionPending}</p>
      {receipt.failureCode && <p>{t.deletionRetry}</p>}
      <p>{t.backupsUnverified}</p>
      <button className="btn-secondary" disabled={busy} onClick={() => deletion("GET")}>{t.checkProgress}</button>
    </> : <>
      <p>{t.retention}</p>
      <form onSubmit={async event => {
        event.preventDefault(); const data = new FormData(event.currentTarget);
        setBusy(true); setError("");
        try {
          const response = await fetch(`${base}/retention`, { method: "POST", headers: {
            "Content-Type": "application/json", "Case-Access-Token": token,
          }, body: JSON.stringify({ days: Number(data.get("days")) }) });
          if (!response.ok) throw new Error(t.retentionFailed);
          const result = await response.json(); setMessage(fill(t.expires, { date: format.dateTime(result.caseExpiresAt) }));
        } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
      }}>
        <label>{t.days}<input name="days" type="number" required min={1} max={30} defaultValue={7} /></label>
        <button className="btn-secondary" disabled={busy}>{t.shorten}</button>
      </form>
      {message && <p role="status">{message}</p>}
      <p>{t.deletionExplanation}</p>
      <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{t.confirm}</label>
      <button className="btn-danger" disabled={busy || !confirmed} onClick={() => deletion("POST")}>{t.delete}</button>
    </>}
    {error && <p role="alert">{error}</p>}
  </section>;
}
