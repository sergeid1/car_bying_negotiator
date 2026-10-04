import { FormEvent, useEffect, useRef, useState } from "react";
import type { Credentials } from "./CasePortability";

type Contact = { email: string; firstName: string | null; lastName: string | null; phone: string | null };
type AttachedCase = { caseId: string; attachedAt: string; state: string };
type Account = { contact: Contact; createdAt: string; sessionExpiresAt: string; cases: AttachedCase[] };
type Receipt = { state: "DELETION_PENDING" | "DELETED"; failureCode?: string | null; cases: { caseId: string; state: string }[] };
type Problem = { status: number; code?: string; detail?: string };

/** The session survives a page reload in this browser only; nothing is stored until the Buyer signs in. */
export const ACCOUNT_SESSION_KEY = "deal-xray.account-session";
function storedSession() {
  try { return window.localStorage.getItem(ACCOUNT_SESSION_KEY) ?? ""; } catch { return ""; }
}
function storeSession(value: string) {
  try {
    if (value) window.localStorage.setItem(ACCOUNT_SESSION_KEY, value);
    else window.localStorage.removeItem(ACCOUNT_SESSION_KEY);
  } catch { /* Storage can be unavailable; the session then lasts for this page only. */ }
}

async function call(path: string, init: RequestInit & { session?: string; caseToken?: string; key?: string } = {}) {
  const { session, caseToken, key, ...rest } = init;
  const response = await fetch(path, { ...rest, headers: {
    ...(rest.body ? { "Content-Type": "application/json" } : {}),
    ...(session ? { "Account-Session-Token": session } : {}),
    ...(caseToken ? { "Case-Access-Token": caseToken } : {}),
    ...(key ? { "Idempotency-Key": key } : {}),
  } });
  if (!response.ok) {
    let problem: Problem = { status: response.status };
    try { problem = { ...(await response.json()), status: response.status }; } catch { /* No problem body. */ }
    throw problem;
  }
  return response;
}

const STATE_LABELS: Record<string, string> = {
  ACTIVE: "Open", EXPIRED: "Expired", DELETION_PENDING: "Being deleted", DELETED: "Deleted",
};
const LINK_MESSAGES: Record<string, string> = {
  SIGN_IN_LINK_USED: "This sign-in link was already used. Each link works once. Request a new link below.",
  SIGN_IN_LINK_EXPIRED: "This sign-in link has expired. Request a new link below.",
  SIGN_IN_LINK_UNKNOWN: "This sign-in link is not recognized. Check that you opened the whole link, or request a new one below.",
};
const ENDED = "Your sign-in has ended. Request a new sign-in link to continue.";

export function BuyerAccount({ caseCredentials }: { caseCredentials?: Credentials }) {
  const [session, setSession] = useState(storedSession);
  const [linkSecret, setLinkSecret] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get("sign-in") ?? "");
  const [account, setAccount] = useState<Account>();
  const [receipt, setReceipt] = useState<Receipt>();
  const [deletionSession, setDeletionSession] = useState("");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loads, setLoads] = useState(0);

  useEffect(() => {
    if (window.location.hash.startsWith("#sign-in=")) window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }, []);
  useEffect(() => {
    if (!session) { setAccount(undefined); return; }
    let current = true;
    call("/api/v1/account", { session }).then(response => response.json())
      .then(value => { if (current) setAccount(value); })
      .catch((problem: Problem) => {
        if (!current) return;
        if (problem?.status === 401) signedOut(ENDED); else setError("Your account could not be loaded. Retry safely.");
      });
    return () => { current = false; };
  }, [session, loads]);

  function signedOut(notice = "") {
    storeSession(""); setSession(""); setAccount(undefined); setMessage(""); setError(notice);
  }
  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true); setError(""); setMessage("");
    try { await action(); }
    catch (problem) {
      const status = (problem as Problem)?.status;
      if (status === 401) signedOut(ENDED); else setError((problem as Problem)?.detail ?? failure);
    } finally { setBusy(false); }
  }

  if (receipt) return <section className="panel" aria-label="Buyer Account">
    <h2>Buyer Account</h2>
    <p role="status">{receipt.state === "DELETED" ? "Active-system removal of your account and its cases is verified."
      : "Your account and attached cases are no longer accessible. Removal is pending."}</p>
    {receipt.failureCode && <p>Removal needs another attempt. The background worker will retry.</p>}
    {receipt.cases.length > 0 && <ul className="account-cases" aria-label="Attached cases being deleted">
      {receipt.cases.map(c => <li key={c.caseId}>Case {c.caseId.slice(0, 8)}: {STATE_LABELS[c.state] ?? c.state}</li>)}
    </ul>}
    <p>Backup expiry is unverified. Active-system completion does not confirm removal from backups or copies you downloaded.</p>
    <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setReceipt(await (await call("/api/v1/account/deletion", { session: deletionSession })).json());
    }, "Deletion progress could not be checked. Retry safely.")}>Check deletion progress</button>
    {error && <p role="alert">{error}</p>}
  </section>;

  if (!session || !account) return <section className="panel" aria-label="Buyer Account">
    <h2>Buyer Account</h2>
    <p>Optional. Analysis works without an account. An account keeps your Buying Cases in one place for decisions that
      take more than a day. There is no password: each sign-in uses a single-use email link that expires in 15 minutes.</p>
    {linkSecret && <button disabled={busy} onClick={() => run(async () => {
      try {
        const result = await (await call("/api/v1/account/sign-in/verify", { method: "POST", body: JSON.stringify({ token: linkSecret }) })).json();
        setLinkSecret(""); storeSession(result.sessionToken); setSession(result.sessionToken);
      } catch (problem) {
        const code = (problem as Problem)?.code;
        if (code && LINK_MESSAGES[code]) { setLinkSecret(""); throw { status: 0, detail: LINK_MESSAGES[code] }; }
        throw problem;
      }
    }, "The sign-in link could not be checked. Retry safely.")}>Finish signing in</button>}
    {session && !account && !error && <p role="status">Loading your account…</p>}
    {session && !account && error && <button className="btn-secondary" onClick={() => { setError(""); setLoads(loads + 1); }}>Retry loading account</button>}
    {!session && <SignInRequest busy={busy} run={run} setMessage={setMessage} />}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;

  const attached = caseCredentials && account.cases.some(c => c.caseId === caseCredentials.caseId);
  return <section className="panel" aria-label="Buyer Account">
    <h2>Buyer Account</h2>
    <p>Signed in as <strong className="account-email">{account.contact.email}</strong>. Your contact details are stored
      once, encrypted, and are never handed to a dealer.</p>
    <ContactForm account={account} busy={busy} onSave={(body) => run(async () => {
      setAccount(await (await call("/api/v1/account/contact", { method: "PUT", session, body: JSON.stringify(body) })).json());
      setMessage("Contact details saved.");
    }, "Contact details could not be saved. Check the phone number format and retry.")} />
    <NotificationPreferences session={session} onSessionEnded={() => signedOut(ENDED)} />
    <h3>Attached Buying Cases</h3>
    {account.cases.length === 0 ? <p className="muted">No cases attached yet. Open a case by its private link, then attach it here.</p>
      : <ul className="account-cases" aria-label="Attached Buying Cases">
        {account.cases.map(c => <li key={c.caseId}>Case {c.caseId.slice(0, 8)}: {STATE_LABELS[c.state] ?? c.state}</li>)}
      </ul>}
    {caseCredentials && !attached && <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setAccount(await (await call("/api/v1/account/cases", { method: "POST", session, caseToken: caseCredentials.token,
        body: JSON.stringify({ caseId: caseCredentials.caseId }) })).json());
      setMessage("Case attached. Its private case link keeps working.");
    }, "This case could not be attached. Retry safely.")}>Attach this Buying Case</button>}
    <div className="actions">
      <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
        const url = URL.createObjectURL(await (await call("/api/v1/account/export", { method: "POST", session })).blob());
        const link = document.createElement("a"); link.href = url; link.download = "buyer-account-export.zip";
        link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        setMessage("Account export ready. It includes the export of every attached case.");
      }, "Export failed. Retry safely.")}>Export account data</button>
      <button className="btn-ghost" disabled={busy} onClick={() => run(async () => {
        await call("/api/v1/account/sign-out", { method: "POST", session }); signedOut(); setMessage("Signed out.");
      }, "Sign-out failed. Retry safely.")}>Sign out</button>
      <button className="btn-ghost" disabled={busy} onClick={() => run(async () => {
        await call("/api/v1/account/sign-out?everywhere=true", { method: "POST", session }); signedOut(); setMessage("Signed out on every device.");
      }, "Sign-out failed. Retry safely.")}>Sign out everywhere</button>
    </div>
    <AccountDeletion busy={busy} onDelete={() => run(async () => {
      const result = await (await call("/api/v1/account/deletion", { method: "POST", session })).json();
      setDeletionSession(session); storeSession(""); setSession(""); setAccount(undefined); setReceipt(result);
    }, "Deletion request could not be verified. Retry safely.")} />
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}

const NOTIFICATION_LABELS: Record<string, string> = {
  DEALER_REPLY: "Dealer replies", DRAFT_NEEDS_APPROVAL: "Drafts needing approval",
  MANDATE_EXPIRING: "Mandate expiry", APPOINTMENT: "Appointments", ACCOUNT_SECURITY: "Account security",
};
type NotificationPreference = { type: string; enabled: boolean; mandatory: boolean };

function NotificationPreferences({ session, onSessionEnded }: { session: string; onSessionEnded: () => void }) {
  const [preferences, setPreferences] = useState<NotificationPreference[]>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  useEffect(() => {
    let current = true;
    call("/api/v1/account/notifications", { session }).then(response => response.json()).then(value => {
      if (current) {
        if (!Array.isArray(value)) throw new Error("Preferences unavailable");
        setPreferences(value); setError("");
      }
    }).catch((problem: Problem) => {
      if (!current) return;
      if (problem?.status === 401) onSessionEnded();
      else setError("Email preferences could not be loaded. Retry safely.");
    });
    return () => { current = false; };
  }, [session, loads]);
  async function change(type: string, enabled: boolean) {
    setBusy(true); setError(""); setNotice("");
    try {
      const value = await (await call("/api/v1/account/notifications", { method: "PUT", session,
        body: JSON.stringify({ type, enabled }) })).json();
      setPreferences(value); setNotice("Email preferences saved.");
    } catch (problem) {
      if ((problem as Problem)?.status === 401) onSessionEnded();
      else setError("Email preferences could not be saved. Retry safely.");
    } finally { setBusy(false); }
  }
  return <fieldset>
    <legend>Email notifications</legend>
    <p>Emails contain a prompt to sign in and open your journey. Account-security emails are required.</p>
    {preferences?.map(preference => <label key={preference.type}>
      <input type="checkbox" checked={preference.enabled} disabled={busy || preference.mandatory}
        onChange={event => void change(preference.type, event.target.checked)} />
      {NOTIFICATION_LABELS[preference.type]}{preference.mandatory ? " (required)" : ""}
    </label>)}
    {error && <><p role="alert">{error}</p>{!preferences && <button className="btn-secondary" type="button"
      onClick={() => setLoads(loads + 1)}>Retry email preferences</button>}</>}
    {notice && <p role="status">{notice}</p>}
  </fieldset>;
}

function SignInRequest({ busy, run, setMessage }: {
  busy: boolean; run: (action: () => Promise<void>, failure: string) => Promise<void>; setMessage: (value: string) => void;
}) {
  const [email, setEmail] = useState("");
  const key = useRef(crypto.randomUUID());
  return <form onSubmit={(event: FormEvent) => { event.preventDefault(); void run(async () => {
    const result = await (await call("/api/v1/account/sign-in-requests", { method: "POST", key: key.current, body: JSON.stringify({ email }) })).json();
    key.current = crypto.randomUUID(); setMessage(result.status);
  }, "The sign-in link could not be sent. Retry safely."); }}>
    <label>Email for sign-in<input type="email" autoComplete="email" required maxLength={254} value={email}
      onChange={event => { setEmail(event.target.value); key.current = crypto.randomUUID(); }} /></label>
    <button disabled={busy}>Email me a sign-in link</button>
  </form>;
}

function ContactForm({ account, busy, onSave }: {
  account: Account; busy: boolean; onSave: (body: { firstName: string; lastName: string; phone: string }) => void;
}) {
  const [firstName, setFirstName] = useState(account.contact.firstName ?? "");
  const [lastName, setLastName] = useState(account.contact.lastName ?? "");
  const [phone, setPhone] = useState(account.contact.phone ?? "");
  return <form aria-label="Contact details" onSubmit={event => { event.preventDefault(); onSave({ firstName, lastName, phone }); }}>
    <label>First name (optional)<input autoComplete="given-name" maxLength={100} value={firstName} onChange={event => setFirstName(event.target.value)} /></label>
    <label>Last name (optional)<input autoComplete="family-name" maxLength={100} value={lastName} onChange={event => setLastName(event.target.value)} /></label>
    <label>Phone (optional)<input type="tel" autoComplete="tel" maxLength={32} value={phone} onChange={event => setPhone(event.target.value)} /></label>
    <button className="btn-secondary" disabled={busy}>Save contact details</button>
  </form>;
}

function AccountDeletion({ busy, onDelete }: { busy: boolean; onDelete: () => void }) {
  const [confirmed, setConfirmed] = useState(false);
  return <>
    <h3>Delete account</h3>
    <p>Deleting your account immediately ends every sign-in and revokes access to every attached Buying Case, then
      removes the account, its contact details and those cases under the case deletion rules. This cannot be undone.</p>
    <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />I want to permanently delete my Buyer Account and its attached cases.</label>
    <button className="btn-danger" disabled={busy || !confirmed} onClick={onDelete}>Delete Buyer Account</button>
  </>;
}
