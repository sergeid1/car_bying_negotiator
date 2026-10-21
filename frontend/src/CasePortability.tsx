import { ReactNode, useEffect, useRef, useState } from "react";
import { type Messages, useMessages } from "./i18n/LanguageProvider";
export type Credentials = { caseId: string; token: string };
type Text = Messages["account"]["casePortability"];
async function post(t: Text, path: string, body: unknown, key: string, token?: string) {
  const response = await fetch(path, { method: "POST", headers: {
    "Content-Type": "application/json", "Idempotency-Key": key,
    ...(token ? { "Case-Access-Token": token } : {}),
  }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error(response.status === 410 ? t.exportExpired : t.requestFailed);
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
  const { m: { account: { casePortability: t } } } = useMessages();
  const [initial] = useState(() => window.location.hash.includes("case=") ? window.location.href : "");
  const [link, setLink] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(false);
  async function open(value: string) {
    setBusy(true); setError(false);
    const credentials = readCaseLink(value);
    try {
      if (!credentials) throw new Error("invalid-link");
      const response = await fetch(`/api/v1/cases/${encodeURIComponent(credentials.caseId)}`, { headers: { "Case-Access-Token": credentials.token } });
      if (!response.ok) throw new Error("unavailable-link");
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
  return <section className="panel" aria-label={t.openLabel}>
    <h3>{t.openHeading}</h3>
    <p>{t.openIntro}</p>
    <form onSubmit={event => { event.preventDefault(); void open(link); }}>
      <label>{t.savedLink}<input type="password" autoComplete="off" required value={link} onChange={event => setLink(event.target.value)} /></label>
      <button disabled={busy}>{busy ? t.opening : t.openSaved}</button>
    </form>
    {error && <p role="alert">{t.cannotOpen}</p>}
  </section>;
}
/**
 * Opening a case by its private link and recovering a lost link, collapsed under the sign-in form. It opens by
 * itself for an emailed recovery link and whenever `expand` changes, such as after a case link fails to open.
 */
export function CaseLinkAccess({ expand, children }: { expand: number; children: ReactNode }) {
  const { m: { account: { casePortability: t } } } = useMessages();
  const [open, setOpen] = useState(() => window.location.hash.startsWith("#recover="));
  useEffect(() => { if (expand) setOpen(true); }, [expand]);
  return <details className="case-link-access" open={open} onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>{t.haveLink}</summary>
    {children}
  </details>;
}
export function SaveCaseLink(credentials: Credentials) {
  const { m: { account: { casePortability: t } } } = useMessages();
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(false);
  const link = caseLink(credentials);
  return <section className="panel saved-case-link" aria-label={t.returnLabel}>
    <h2>{t.returnHeading}</h2>
    <p>{t.returnIntro}</p>
    <a href={link}>{t.privateLink}</a>
    <button className="btn-secondary" onClick={async () => { try { await navigator.clipboard.writeText(link); setCopied(true); setError(false); } catch { setError(true); } }}>{t.copyLink}</button>
    {copied && <p role="status">{t.copied}</p>}
    {error && <p role="alert">{t.copyUnavailable}</p>}
  </section>;
}
export function RecoveryAccess({ onRecovered, caseId }: { onRecovered: (value: Credentials) => void; caseId?: string }) {
  const { m: { account: { casePortability: t } } } = useMessages();
  const [secret, setSecret] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get("recover") ?? "");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const request = useRef({ body: "", key: "" });
  useEffect(() => {
    if (window.location.hash.startsWith("#recover=")) window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }, []);
  return <section className="panel" aria-label={t.recoverLabel}>
    <h3>{t.recoverHeading}</h3>
    <p>{t.recoverIntro}</p>
    {secret ? <button disabled={busy} onClick={async () => {
      setBusy(true); setError("");
      try {
        const result = await post(t, "/api/v1/recovery/verify", { token: secret }, crypto.randomUUID());
        setSecret(""); onRecovered({ caseId: result.caseId, token: result.accessToken });
        setMessage(t.recovered);
      } catch { setError(t.verifyFailed); }
      finally { setBusy(false); }
    }}>{t.verify}</button> : null}
    <form onSubmit={async event => {
      event.preventDefault(); const data = new FormData(event.currentTarget);
      const body = { caseId: caseId ?? readCaseLink(String(data.get("recoveryLink")))?.caseId ?? "unrecognized", email: String(data.get("recoveryEmail")) };
      if (request.current.body !== JSON.stringify(body)) request.current = { body: JSON.stringify(body), key: crypto.randomUUID() };
      setBusy(true); setError("");
      try {
        const result = await post(t, "/api/v1/recovery/requests", body, request.current.key);
        setMessage(result.status); request.current = { body: "", key: "" };
      } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
    }}>
      {!caseId && <label>{t.recoveryLink}<input name="recoveryLink" type="password" autoComplete="off" required /></label>}
      <label>{t.recoveryEmail}<input name="recoveryEmail" type="email" required maxLength={254} /></label>
      <button disabled={busy}>{t.emailRecovery}</button>
    </form>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
export function RecoveryEmailOptIn({ caseId, token }: Credentials) {
  const { m: { account: { casePortability: t } } } = useMessages();
  const [email, setEmail] = useState(""); const [message, setMessage] = useState("");
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID());
  return <section className="panel" aria-label={t.optInLabel}>
    <h2>{t.optInHeading}</h2>
    <p>{t.optInIntro}</p>
    <form onSubmit={async event => {
      event.preventDefault(); setBusy(true); setError("");
      try {
        await post(t, `/api/v1/cases/${encodeURIComponent(caseId)}/recovery-email`, { email }, key.current, token);
        setMessage(t.optInSaved); key.current = crypto.randomUUID();
      } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
    }}>
      <label>{t.optInEmail}<input type="email" required maxLength={254} value={email} onChange={event => { setEmail(event.target.value); key.current = crypto.randomUUID(); }} /></label>
      <button disabled={busy}>{t.optInSubmit}</button>
    </form>
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}
/** `headless` drops the panel and heading so the report's collapsible list can supply them. */
export function CaseDownloads({ caseId, token, reportVersion, headless }: Credentials & { reportVersion?: number; headless?: boolean }) {
  const { m: { account: { casePortability: t } }, fill } = useMessages();
  const keys = useRef<Record<string, string>>({});
  const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function download(format: "ZIP" | "HTML" | "PDF") {
    const version = format === "ZIP" ? 0 : reportVersion;
    const slot = `${format}-${version}`; keys.current[slot] ??= crypto.randomUUID();
    setBusy(true); setError("");
    try {
      const base = `/api/v1/cases/${encodeURIComponent(caseId)}/exports`;
      const job = await post(t, base, { format, reportVersion: version }, keys.current[slot], token);
      const response = await fetch(`${base}/${job.id}`, { headers: { "Case-Access-Token": token } });
      if (!response.ok) throw new Error(t.downloadFailed);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement("a"); link.href = url; link.download = `buying-case-${caseId}.${format.toLowerCase()}`;
      link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setMessage(t.downloadReady);
    } catch (failure) { setError(String(failure)); } finally { setBusy(false); }
  }
  const body = <>
    <p>{t.downloadsIntro}</p>
    <div className="actions">
      <button disabled={busy} onClick={() => download("ZIP")}>{t.exportData}</button>
      {reportVersion !== undefined && <>
        <button className="btn-secondary" disabled={busy} onClick={() => download("HTML")}>{t.downloadHtml}</button>
        <button className="btn-secondary" disabled={busy} onClick={() => download("PDF")}>{t.downloadPdf}</button>
      </>}
      <button className="btn-ghost" disabled={busy} onClick={() => { keys.current = {}; setMessage(t.nextSnapshot); setError(""); }}>{t.newExport}</button>
    </div>
    {reportVersion !== undefined && <p className="muted"><small>{fill(t.savedVersion, { version: reportVersion })}</small></p>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </>;
  return headless ? body : <section className="panel" aria-label={t.downloadsLabel}><h2>{t.downloadsHeading}</h2>{body}</section>;
}
