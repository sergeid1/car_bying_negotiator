import { useEffect, useState } from "react";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
const ROOT = "/api/v1/account/password";

export function Passwords({ request, onSessionEnded }: { request: Request; onSessionEnded: () => void }) {
  const [configured, setConfigured] = useState<boolean>();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  function failed(problem: { status?: number; detail?: string }) {
    if (problem?.status === 401) onSessionEnded();
    else setError(problem?.detail ?? "Your password could not be updated. Try again.");
  }
  useEffect(() => {
    let current = true;
    request(ROOT).then(response => response.json()).then(value => {
      if (typeof value.configured !== "boolean") throw new Error("Password status unavailable");
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
      setNotice(remove ? "Password removed. Email links and passkeys remain available."
        : "Password saved. Other devices have been signed out. Check your account-security email.");
    } catch (problem) { failed(problem as { status?: number; detail?: string }); }
    finally { setPassword(""); setBusy(false); }
  }
  return <fieldset>
    <legend>Optional password</legend>
    <p>{configured ? "A password is set." : "Email links remain the default way to sign in."}</p>
    <p>Use at least 12 characters, up to 72 UTF-8 bytes. No special character rules apply.
      Changes need a sign-in within the last 10 minutes. To sign in again, sign out and use an email link or passkey.</p>
    <form aria-label="Manage password" onSubmit={event => { event.preventDefault(); void update(); }}>
      <label>New password<input type="password" autoComplete="new-password" required minLength={12} maxLength={72}
        value={password} onChange={event => setPassword(event.target.value)} /></label>
      <button className="btn-secondary" disabled={busy || configured === undefined}>{configured ? "Change password" : "Set password"}</button>
    </form>
    {configured && <button className="btn-secondary" disabled={busy} onClick={() => void update(true)}>Remove password</button>}
    {error && <><p role="alert">{error}</p><button className="btn-secondary" disabled={busy}
      onClick={() => setLoads(value => value + 1)}>Reload password status</button></>}
    {notice && <p role="status">{notice}</p>}
  </fieldset>;
}
