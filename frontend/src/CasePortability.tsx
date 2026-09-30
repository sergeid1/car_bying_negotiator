import { useEffect, useRef, useState } from "react";
export type Credentials = { caseId: string; token: string };
async function post(path: string, body: unknown, key: string, token?: string) {
  const response = await fetch(path, { method: "POST", headers: {
    "Content-Type": "application/json", "Idempotency-Key": key,
    ...(token ? { "Case-Access-Token": token } : {}),
  }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(response.status === 410 ? "Export expired. Start a new export." : "Request failed. You can retry safely.");
  return response.json();
}
export function caseLink({ caseId, token }: Credentials) {
  return `${window.location.origin}${window.location.pathname}#case=${encodeURIComponent(caseId)}&access=${encodeURIComponent(token)}`;
}
export function readCaseLink(value: string): Credentials | undefined {
  try {
    const url = new URL(value, window.location.origin);
    if (url.origin !== window.location.origin || url.pathname !== window.location.pathname) return undefined;
    const params = new URLSearchParams(url.hash.slice(1));
    const caseId = params.get("case"), token = params.get("access");
    return caseId && token ? { caseId, token } : undefined;
  } catch { return undefined; }
}
export function SavedCaseAccess({ onOpened, onUnavailable }: { onOpened: (value: Credentials) => void; onUnavailable: (caseId?: string) => void }) {
  const [initial] = useState(() => window.location.hash.includes("case=") ? window.location.href : "");
  const [link, setLink] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  async function open(value: string) {
    setBusy(true); setError(false);
    const credentials = readCaseLink(value);
    try {
      if (!credentials) throw new Error("Invalid link");
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(credentials.caseId)}`, { headers: { "Case-Access-Token": credentials.token } });
      if (!response.ok) throw new Error("Unavailable link");
      onOpened(credentials); setLink("");
    } catch { setError(true); onUnavailable(credentials?.caseId); }
    finally { setBusy(false); }
  }
  useEffect(() => {
    if (initial) {
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      void open(initial);
    }
  }, [initial]);
  return <section id="case-workflow" tabIndex={-1} className="panel" aria-label="Open an existing Buying Case">
    <h2>Open a saved case</h2>
    <p>Open your private saved case link, or paste it here. Anyone with the link can access the case.</p>
    <form onSubmit={event => { event.preventDefault(); void open(link); }}>
      <label>Saved case link<input type="password" autoComplete="off" required value={link} onChange={event => setLink(event.target.value)} /></label>
      <button disabled={busy}>{busy ? "Opening case…" : "Open saved case"}</button>
    </form>
    {error && <p role="alert">This link cannot be opened. It may have expired, been revoked, or be unrecognized. Use email recovery below if you enabled it, or start a new case.</p>}
  </section>;
}
export function SaveCaseLink(credentials: Credentials) {
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(false);
  const link = caseLink(credentials);
  return <section className="panel saved-case-link" aria-label="Private return link">
    <h2>Return to your case</h2>
    <p>Save this private link to return from another browser. It stops working when access expires or the case is deleted. Keep it private.</p>
    <a href={link}>Private case link</a>
    <button className="btn-secondary" onClick={async () => { try { await navigator.clipboard.writeText(link); setCopied(true); setError(false); } catch { setError(true); } }}>Copy private case link</button>
    {copied && <p role="status">Private link copied.</p>}
    {error && <p role="alert">Copy is unavailable. Save the Private case link using your browser.</p>}
  </section>;
}
export function RecoveryAccess({ onRecovered, caseId }: { onRecovered: (value: Credentials) => void; caseId?: string }) {
  const [secret, setSecret] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get("recover") ?? "");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const request = useRef({ body: "", key: "" });
  useEffect(() => {
    if (window.location.hash.startsWith("#recover=")) window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }, []);
  return <section className="panel" aria-label="Recover Buying Case access">
    <h2>Recover case access</h2>
    <p>If you previously opted into email recovery, request a new access link. Email is optional for analysis.</p>
    {secret ? <button disabled={busy} onClick={async () => {
      setBusy(true); setError("");
      try {
        const result = await post("/api/v1/recovery/verify", { token: secret }, crypto.randomUUID());
        setSecret(""); onRecovered({ caseId: result.caseId, token: result.accessToken });
        setMessage("Access recovered. Your previous access token has been replaced.");
      } catch { setError("The link could not be verified. Retry, or request a new link if it has expired or was already used."); }
      finally { setBusy(false); }
    }}>Verify recovery link</button> : null}
    <form onSubmit={async event => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      const body = { caseId: caseId ?? readCaseLink(String(data.get("recoveryLink")))?.caseId ?? "unrecognized", email: String(data.get("recoveryEmail")) };
      if (request.current.body !== JSON.stringify(body)) request.current = { body: JSON.stringify(body), key: crypto.randomUUID() };
      setBusy(true); setError("");
      try {
        const result = await post("/api/v1/recovery/requests", body, request.current.key);
        setMessage(result.status); request.current = { body: "", key: "" };
      } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
    }}>
      {!caseId && <label>Saved case link for recovery<input name="recoveryLink" type="password" autoComplete="off" required /></label>}
      <label>Recovery email<input name="recoveryEmail" type="email" required maxLength={254} /></label>
      <button disabled={busy}>Email recovery link</button>
    </form>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
export function RecoveryEmailOptIn({ caseId, token }: Credentials) {
  const [email, setEmail] = useState(""); const [message, setMessage] = useState("");
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  return <section className="panel" aria-label="Optional email recovery">
    <h2>Optional email recovery</h2>
    <p>Save an email address and receive a single-use access link. It expires in 15 minutes. Save your private case link to request another email later.</p>
    <form onSubmit={async event => {
      event.preventDefault(); setBusy(true); setError("");
      try {
        await post(`/api/v1/cases/${encodeURIComponent(caseId)}/recovery-email`, { email }, key.current, token);
        setMessage("Recovery email saved. Check your inbox for your access link."); key.current = crypto.randomUUID();
      } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
    }}>
      <label>Email for case recovery<input type="email" required maxLength={254} value={email} onChange={event => { setEmail(event.target.value); key.current = crypto.randomUUID(); }} /></label>
      <button disabled={busy}>Enable email recovery and send link</button>
    </form>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
export function CaseDownloads({ caseId, token, reportVersion }: Credentials & { reportVersion?: number }) {
  const keys = useRef<Record<string, string>>({});
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function download(format: "ZIP" | "HTML" | "PDF") {
    const version = format === "ZIP" ? 0 : reportVersion;
    const slot = `${format}-${version}`; keys.current[slot] ??= crypto.randomUUID();
    setBusy(true); setError("");
    try {
      const base = `/api/v1/cases/${encodeURIComponent(caseId)}/exports`;
      const job = await post(base, { format, reportVersion: version }, keys.current[slot], token);
      const response = await fetch(`${base}/${job.id}`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error("Download failed. Retry safely, or start a new export if it has expired.");
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = `buying-case-${caseId}.${format.toLowerCase()}`;
      link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setMessage("Download ready. Repeat downloads use the same saved snapshot.");
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  return <section className="panel" aria-label="Case downloads">
    <h2>Save a copy</h2>
    <p>Export retained case data and version history. Saved reports include their evidence and limitations.</p>
    <div className="actions">
      <button disabled={busy} onClick={() => download("ZIP")}>Export retained case data</button>
      {reportVersion !== undefined && <>
        <button className="btn-secondary" disabled={busy} onClick={() => download("HTML")}>Download HTML report</button>
        <button className="btn-secondary" disabled={busy} onClick={() => download("PDF")}>Download PDF report</button>
      </>}
      <button className="btn-ghost" disabled={busy} onClick={() => { keys.current = {}; setMessage("Next download will create a new snapshot."); setError(""); }}>Start a new export</button>
    </div>
    {reportVersion !== undefined && <p className="muted"><small>Saved report version {reportVersion}</small></p>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
