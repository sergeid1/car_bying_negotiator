import { useEffect, useState } from "react";
import { useMessages } from "./i18n/LanguageProvider";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
const ROOT = "/api/v1/account/password";

export function Passwords({ request, onSessionEnded }: { request: Request; onSessionEnded: () => void }) {
  const { m: { account: { passwords: t } } } = useMessages();
  const [configured, setConfigured] = useState<boolean>();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  function failed(problem: { status?: number; detail?: string }) {
    if (problem?.status === 401) onSessionEnded();
    else setError(problem?.detail ?? t.failed);
  }
  useEffect(() => {
    let current = true;
    request(ROOT).then(response => response.json()).then(value => {
      if (typeof value.configured !== "boolean") throw new Error("password-status-unavailable");
      if (current) { setConfigured(value.configured); setError(""); }
    }).catch(problem => { if (current) failed(problem); });
    return () => { current = false; };
  }, [loads]);
  async function update(remove = false) {
    setBusy(true); setError(""); setNotice("");
    try {
      const value = await (await request(ROOT, { method: remove ? "DELETE" : "PUT",
        ...(remove ? {} : { body: JSON.stringify({ password }) }) })).json();
      setConfigured(value.configured);
      setNotice(remove ? t.removed : t.saved);
    } catch (problem) { failed(problem as { status?: number; detail?: string }); }
    finally { setPassword(""); setBusy(false); }
  }
  return <fieldset>
    <legend>{t.legend}</legend>
    <p>{configured ? t.isSet : t.notSet}</p>
    <p>{t.rules}</p>
    <form aria-label={t.form} onSubmit={event => { event.preventDefault(); void update(); }}>
      <label>{t.newPassword}<input type="password" autoComplete="new-password" required minLength={12} maxLength={72}
        value={password} onChange={event => setPassword(event.target.value)} /></label>
      <button className="btn-secondary" disabled={busy || configured === undefined}>{configured ? t.change : t.set}</button>
    </form>
    {configured && <button className="btn-secondary" disabled={busy} onClick={() => void update(true)}>{t.remove}</button>}
    {error && <><p role="alert">{error}</p><button className="btn-secondary" disabled={busy}
      onClick={() => setLoads(value => value + 1)}>{t.reload}</button></>}
    {notice && <p role="status">{notice}</p>}
  </fieldset>;
}
