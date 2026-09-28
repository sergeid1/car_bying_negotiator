import { useState } from "react";

type Receipt = { state: "DELETION_PENDING" | "DELETED"; attempts?: number; failureCode?: string; backupStatus: string };
export function CaseLifecycle({ caseId, token, onRevoked }: { caseId: string; token: string; onRevoked: () => void }) {
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
      if (!response.ok) throw new Error("Deletion request could not be verified. Retry safely.");
      setReceipt(await response.json()); onRevoked();
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <section className="panel" aria-label="Case retention and deletion">
    <h2>Retention and deletion</h2>
    {receipt ? <>
      <p role="status">{receipt.state === "DELETED" ? "Active-system removal verified." : "Case access has been revoked. Removal is pending."}</p>
      {receipt.failureCode && <p>Removal needs another attempt. The background worker will retry.</p>}
      <p>Backup expiry is unverified. Active-system completion does not confirm removal from backups or copies you downloaded.</p>
      <button className="btn-secondary" disabled={busy} onClick={() => deletion("GET")}>Check deletion progress</button>
    </> : <>
      <p>Original documents are retained for at most 30 days by default; server settings may shorten this.
        Expiry removes the case and its derived data. You can choose a shorter period measured from case creation.
        Active-system deletion has a 24-hour p95 objective. Backup expiry is unverified.</p>
      <form onSubmit={async event => {
        event.preventDefault(); const data = new FormData(event.currentTarget);
        setBusy(true); setError("");
        try {
          const response = await fetch(`${base}/retention`, { method: "POST", headers: {
            "Content-Type": "application/json", "Case-Access-Token": token,
          }, body: JSON.stringify({ days: Number(data.get("days")) }) });
          if (!response.ok) throw new Error("Retention could not be changed. Retry safely.");
          const result = await response.json(); setMessage(`Case expires ${new Date(result.caseExpiresAt).toLocaleString()}. Existing deadlines are never extended.`);
        } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
      }}>
        <label>Retain for days<input name="days" type="number" required min={1} max={30} defaultValue={7} /></label>
        <button className="btn-secondary" disabled={busy}>Shorten retention</button>
      </form>
      {message && <p role="status">{message}</p>}
      <p>Deletion immediately revokes case and recovery access, then removes original documents, reports, exports and derived data. This cannot be undone.</p>
      <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />I want to permanently delete this Buying Case.</label>
      <button className="btn-danger" disabled={busy || !confirmed} onClick={() => deletion("POST")}>Delete Buying Case</button>
    </>}
    {error && <p role="alert">{error}</p>}
  </section>;
}
