import { useEffect, useState } from "react";

export type ConnectedMailbox = { provider: "GOOGLE" | "MICROSOFT"; providerLabel: string; address: string; connectedAt: string };
export type MailboxView = { available: boolean; providers: ("GOOGLE" | "MICROSOFT")[]; connection: ConnectedMailbox | null };
type Problem = { status: number; code?: string; detail?: string };

const LABELS: Record<string, string> = { GOOGLE: "Gmail", MICROSOFT: "Outlook" };

export function asMailboxView(value: unknown): MailboxView | undefined {
  const v = value as Partial<MailboxView> | null;
  if (!v || typeof v.available !== "boolean" || !Array.isArray(v.providers)) return undefined;
  const c = v.connection as Partial<ConnectedMailbox> | null | undefined;
  const connection = c && typeof c.address === "string" && typeof c.providerLabel === "string" ? c as ConnectedMailbox : null;
  return { available: v.available, providers: v.providers.filter(p => p === "GOOGLE" || p === "MICROSOFT"), connection };
}

async function call(path: string, method = "GET", body?: unknown) {
  const response = await fetch(path, { method, headers: {
    ...(body ? { "Content-Type": "application/json" } : {}), ...(method !== "GET" ? { "Account-Request": "1" } : {}),
  }, ...(body ? { body: JSON.stringify(body) } : {}) });
  if (!response.ok) {
    let problem: Problem = { status: response.status };
    try { problem = { ...(await response.json()), status: response.status }; } catch { /* No problem body. */ }
    throw problem;
  }
  return response.json();
}

const consumed = new Set<string>();

/**
 * The provider's answer on return from its consent page. It is taken out of the address bar at once and handled
 * once, however often the panel mounts.
 */
function consumeReturn() {
  const query = new URLSearchParams(window.location.search);
  const state = query.get("state"), code = query.get("code"), error = query.get("error");
  if (!state || (!code && !error) || consumed.has(state)) return undefined;
  consumed.add(state);
  ["state", "code", "error", "error_description", "scope", "session_state", "authuser", "prompt", "hd", "iss"]
    .forEach(key => query.delete(key));
  const rest = query.toString();
  window.history.replaceState(null, "", window.location.pathname + (rest ? `?${rest}` : "") + window.location.hash);
  return { state, code, error };
}

/**
 * The Buyer's Mailbox Connection on the account page: connect Gmail or Outlook with send-only permission, see the
 * connected address, and disconnect. Without one, every message can still be sent from the Buyer's own email app.
 */
export function MailboxConnection({ onSessionEnded }: { onSessionEnded: () => void }) {
  const [view, setView] = useState<MailboxView>();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [loads, setLoads] = useState(0);
  const [unloaded, setUnloaded] = useState(false);

  function failed(problem: unknown, fallback: string) {
    if ((problem as Problem)?.status === 401) { onSessionEnded(); return; }
    setError((problem as Problem)?.detail ?? fallback);
  }

  useEffect(() => {
    let current = true;
    setUnloaded(false);
    const load = (gated: boolean) => call("/api/v1/account/mailbox").then(value => {
      if (gated && !current) return;
      const loaded = asMailboxView(value);
      if (!loaded) throw { status: 0 };
      setView(loaded);
    }).catch(problem => {
      if (gated && !current) return;
      // The panel is optional: a failed load is shown in place rather than announced as an alert.
      if ((problem as Problem)?.status === 401) onSessionEnded(); else setUnloaded(true);
    });
    const pending = consumeReturn();
    if (pending?.code) {
      // Completing is not tied to this mount: it happens once, and its answer is the current view.
      setBusy(true);
      call("/api/v1/account/mailbox/connection", "POST", { state: pending.state, code: pending.code }).then(value => {
        const connected = asMailboxView(value);
        if (!connected?.connection) throw { status: 0 };
        setView(connected);
        setNotice(`${connected.connection.providerLabel} is connected. Approved messages can now be sent from ${connected.connection.address}.`);
      }).catch(problem => { failed(problem, "Your email could not be connected. Try again."); return load(false); })
        .finally(() => setBusy(false));
    } else {
      if (pending?.error)
        setError("You did not allow sending, so nothing was connected. Every message can still be sent from your own email app.");
      void load(true);
    }
    return () => { current = false; };
    // Loads once per retry; the session callback does not change what is loaded.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loads]);

  async function connect(provider: "GOOGLE" | "MICROSOFT") {
    setBusy(true); setError(""); setNotice("");
    try {
      const started = await call("/api/v1/account/mailbox/authorizations", "POST", { provider });
      if (typeof started?.authorizationUrl !== "string") throw { status: 0 };
      window.location.assign(started.authorizationUrl);
    } catch (problem) { failed(problem, `${LABELS[provider]} could not be opened. Try again.`); setBusy(false); }
  }

  async function disconnect(label: string) {
    setBusy(true); setError(""); setNotice("");
    try {
      const value = asMailboxView(await call("/api/v1/account/mailbox/connection", "DELETE"));
      if (value) setView(value);
      setNotice(`${label} is disconnected and its access was deleted. Send messages from your own email app, or connect again.`);
    } catch (problem) { failed(problem, "Disconnecting failed. Retry safely."); }
    finally { setBusy(false); }
  }

  const connection = view?.connection;
  return <section className="mailbox-connection" aria-labelledby="mailbox-connection-heading">
    <h3 id="mailbox-connection-heading">Send from your own email</h3>
    {!view && !unloaded && <p>Loading your email connection…</p>}
    {!view && unloaded && <>
      <p>Your email connection could not be loaded.</p>
      <button type="button" className="btn-secondary" onClick={() => { setUnloaded(false); setLoads(loads + 1); }}>
        Retry loading your email connection</button>
    </>}
    {view && !view.available && !connection && <p>Connecting Gmail or Outlook is not switched on yet. Every message you approve
      can be sent from your own email app.</p>}
    {connection && <>
      <p>Connected: <strong className="mailbox-address">{connection.address}</strong> ({connection.providerLabel})
        {connection.connectedAt ? ` since ${new Date(connection.connectedAt).toLocaleDateString()}` : ""}.</p>
      <p>Deal X-Ray can only send messages you approve, exactly as approved, from this address. They appear in your Sent
        folder. It cannot read your email, so dealer replies still come in when you paste or upload them.</p>
      <button type="button" className="btn-secondary" disabled={busy} onClick={() => void disconnect(connection.providerLabel)}>
        Disconnect {connection.providerLabel}</button>
    </>}
    {view?.available && !connection && <>
      <p>Connect Gmail or Outlook to send an approved message from your address with one button. Deal X-Ray asks only for
        permission to send: it can send just the messages you approve and cannot read your email. Every message can still be
        sent from your own email app instead.</p>
      <div className="actions">
        {view.providers.map(provider => <button key={provider} type="button" className="btn-secondary" disabled={busy}
          onClick={() => void connect(provider)}>Connect {LABELS[provider]}</button>)}
      </div>
    </>}
    {notice && <p role="status">{notice}</p>}
    {error && <p role="alert">{error}</p>}
  </section>;
}
