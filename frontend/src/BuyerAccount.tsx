import { createContext, useEffect, useRef, useState } from "react";
import type { Credentials } from "./CasePortability";
import { MailboxConnection } from "./MailboxConnection";
import { Passkeys } from "./Passkeys";
import { Passwords } from "./Passwords";
import { openSidePanel } from "./SidePanel";
import { SignIn, type SignInMode } from "./SignIn";
import * as format from "./i18n/format";

type Contact = { email: string; firstName: string | null; lastName: string | null; phone: string | null };
type AttachedCase = {
  caseId: string; attachedAt: string; state: string; startedAt?: string | null; vehicle?: string | null; dealer?: string | null;
};
type Account = { contact: Contact; createdAt: string; sessionExpiresAt: string; cases: AttachedCase[] };
type Receipt = { state: "DELETION_PENDING" | "DELETED"; failureCode?: string | null; cases: { caseId: string; state: string }[] };
type Problem = { status: number; code?: string; detail?: string };

export const AccountSignedInContext = createContext<boolean | undefined>(undefined);

export type AccountControls = { email?: string; busy: boolean; signOut: () => void };
export function AccountHeader({ controls, onOpen }: { controls?: AccountControls; onOpen: () => void }) {
  return <div className="header-account" aria-label="Account controls">
    {controls?.email ? <>
      <button className="btn-ghost header-email" title={controls.email} aria-haspopup="dialog" onClick={onOpen}>{controls.email}</button>
      <button className="btn-ghost" disabled={controls.busy} onClick={controls.signOut}>Sign out</button>
    </> : <button className="btn-ghost" aria-haspopup="dialog" onClick={onOpen}>Sign in</button>}
  </div>;
}

async function call(path: string, init: RequestInit & { caseToken?: string; key?: string } = {}) {
  const { caseToken, key, ...rest } = init;
  const response = await fetch(path, { ...rest, headers: {
    ...(rest.body ? { "Content-Type": "application/json" } : {}),
    ...(["POST", "PUT", "DELETE"].includes(rest.method ?? "GET") ? { "Account-Request": "1" } : {}),
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
const UNAVAILABLE = "This case can no longer be opened from your account. It may have expired or be being deleted.";

/** Access to a case opened from the account. It works only while this sign-in lasts and is not a case link. */
export const ACCOUNT_PASS_PREFIX = "acct.";
export function isAccountPass(token: string) { return token.startsWith(ACCOUNT_PASS_PREFIX); }

function startedOn(value?: string | null) {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined
    : format.date(date, { year: "numeric", month: "short", day: "numeric" });
}

/**
 * `onSignedIn` reports whether the account has loaded successfully.
 * Changing `recheck` re-reads the account, so a panel that saw the session end can sign it out here.
 */
export function BuyerAccount({ caseCredentials, onSignedIn, onControls, onCaseOpened, onSignInComplete, recheck = 0, showing = true,
  signInMode = "login", onSignInMode }: {
  signInMode?: SignInMode; onSignInMode?: (mode: SignInMode) => void;
  caseCredentials?: Credentials; onSignedIn?: (signedIn: boolean) => void; recheck?: number;
  /** Whether the Buyer can see this panel; the email box offers saved passkeys only then. */
  showing?: boolean;
  /** The Buyer finished signing in here, by email link, password or passkey. */
  onSignInComplete?: (notice?: string) => void;
  onControls?: (controls: AccountControls) => void;
  /** Opens an attached case with access that ends when this sign-in ends. */
  onCaseOpened?: (value: Credentials) => void;
}) {
  const [loading, setLoading] = useState(true);
  const [linkSecret, setLinkSecret] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get("sign-in") ?? "");
  const [account, setAccount] = useState<Account>();
  const [receipt, setReceipt] = useState<Receipt>();
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loads, setLoads] = useState(0);
  const [socialReturn, setSocialReturn] = useState(() => {
    const hash = new URLSearchParams(window.location.hash.slice(1));
    return { state: hash.get("social-sign-in"), error: hash.get("social-sign-in-error") };
  });
  const socialAttempt = useRef<string | null>(null);

  useEffect(() => {
    function receiveSocialReturn() {
      const hash = new URLSearchParams(window.location.hash.slice(1));
      if (!hash.has("social-sign-in") && !hash.has("social-sign-in-error")) return;
      setSocialReturn({ state: hash.get("social-sign-in"), error: hash.get("social-sign-in-error") });
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      openSidePanel("account");
    }
    receiveSocialReturn();
    window.addEventListener("hashchange", receiveSocialReturn);
    return () => window.removeEventListener("hashchange", receiveSocialReturn);
  }, []);
  useEffect(() => {
    if (socialReturn.error) {
      setError(socialReturn.error === "cancelled" ? "Sign-in with your provider was cancelled."
        : "Sign-in with your provider could not be completed. Try again or continue with your email.");
      return;
    }
    if (!socialReturn.state || socialAttempt.current === socialReturn.state) return;
    socialAttempt.current = socialReturn.state;
    void run(async () => {
      const result = await (await call("/api/v1/account/social-sign-in/complete", {
        method: "POST", body: JSON.stringify({ state: socialReturn.state }),
      })).json();
      const loaded = await (await call("/api/v1/account")).json();
      setAccount(loaded); setLoading(false);
      const notice = result.provider === "GOOGLE" ? "Signed in with Google." : result.provider === "APPLE" ? "Signed in with Apple." : "Signed in.";
      setMessage(notice); onSignInComplete?.(notice);
    }, "Sign-in with your provider could not be completed. Try again or continue with your email.").then(() => {
      setLoading(false); setSocialReturn({ state: null, error: null });
    });
  }, [socialReturn]);

  useEffect(() => {
    try { window.localStorage.removeItem("deal-xray.account-session"); } catch { /* Browser storage can be unavailable. */ }
    if (window.location.hash.startsWith("#sign-in=")) window.history.replaceState(null, "", window.location.pathname + window.location.search);
    function receiveLink() {
      const secret = new URLSearchParams(window.location.hash.slice(1)).get("sign-in");
      if (!secret) return;
      setLinkSecret(secret);
      window.history.replaceState(null, "", window.location.pathname + window.location.search);
      openSidePanel("account");
    }
    window.addEventListener("hashchange", receiveLink);
    return () => window.removeEventListener("hashchange", receiveLink);
  }, []);
  useEffect(() => {
    if (socialReturn.state) return;
    let current = true;
    setLoading(true);
    call("/api/v1/account").then(response => response.json())
      .then(value => {
        if (!value?.contact || !Array.isArray(value.cases)) throw new Error("Account unavailable");
        if (current) { setAccount(value); setLoading(false); }
      })
      .catch((problem: Problem) => {
        if (!current) return;
        setLoading(false);
        if (problem?.status === 401) {
          if (recheck) signedOut(ENDED); else setAccount(undefined);
        } else setError("Your account could not be loaded. Retry safely.");
      });
    return () => { current = false; };
  }, [loads, recheck, socialReturn.state]);
  useEffect(() => { onSignedIn?.(!!account); }, [account]);
  useEffect(() => { onControls?.({ email: account?.contact.email, busy, signOut }); }, [account, busy]);

  function signOut() {
    void run(async () => {
      await call("/api/v1/account/sign-out", { method: "POST" }); signedOut(); setMessage("Signed out.");
    }, "Sign-out failed. Retry safely.");
  }

  function signedOut(notice = "") {
    setLoading(false); setAccount(undefined); setMessage(""); setError(notice);
  }
  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true); setError(""); setMessage("");
    try { await action(); }
    catch (problem) {
      const status = (problem as Problem)?.status;
      if (status === 401) signedOut(ENDED); else setError((problem as Problem)?.detail ?? failure);
    } finally { setBusy(false); }
  }

  const finishLink = linkSecret && <button disabled={busy} onClick={() => run(async () => {
    try {
      await call("/api/v1/account/sign-in/verify", { method: "POST", body: JSON.stringify({ token: linkSecret }) });
      setLinkSecret(""); setLoads(value => value + 1); onSignInComplete?.();
    } catch (problem) {
      const code = (problem as Problem)?.code;
      if (code && LINK_MESSAGES[code]) { setLinkSecret(""); throw { status: 0, detail: LINK_MESSAGES[code] }; }
      throw problem;
    }
  }, "The sign-in link could not be checked. Retry safely.")}>Finish signing in</button>;

  if (receipt) return <section id="buyer-account" tabIndex={-1} className="panel" aria-label="Buyer Account">
    <h2>Buyer Account</h2>
    <p role="status">{receipt.state === "DELETED" ? "Active-system removal of your account and its cases is verified."
      : "Your account and attached cases are no longer accessible. Removal is pending."}</p>
    {receipt.failureCode && <p>Removal needs another attempt. The background worker will retry.</p>}
    {receipt.cases.length > 0 && <ul className="account-cases" aria-label="Attached cases being deleted">
      {receipt.cases.map(c => <li key={c.caseId}>Case {c.caseId.slice(0, 8)}: {STATE_LABELS[c.state] ?? c.state}</li>)}
    </ul>}
    <p>Backup expiry is unverified. Active-system completion does not confirm removal from backups or copies you downloaded.</p>
    {receipt.state !== "DELETED" && <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setReceipt(await (await call("/api/v1/account/deletion")).json());
    }, "Deletion progress could not be checked. Retry safely.")}>Check deletion progress</button>}
    {error && <p role="alert">{error}</p>}
  </section>;

  if (!account) return <section id="buyer-account" tabIndex={-1} className="panel" aria-label="Buyer Account">
    {finishLink}
    {loading && !error && <p role="status">Loading your account…</p>}
    {error && <button className="btn-secondary" onClick={() => { setError(""); setLoads(loads + 1); }}>Retry loading account</button>}
    {!loading && !linkSecret && <SignIn request={call} mode={signInMode} onMode={onSignInMode} offerPasskeys={showing} onSignedIn={async method => {
      setAccount(await (await call("/api/v1/account")).json());
      setMessage(method === "password" ? "Signed in with a password." : "Signed in with a passkey."); onSignInComplete?.();
    }} />}
    {signInMode === "signup" && <p className="sign-in-footer">Optional. Keeps your cases in one place.</p>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;

  const attached = caseCredentials && account.cases.some(c => c.caseId === caseCredentials.caseId);
  return <section id="buyer-account" tabIndex={-1} className="panel" aria-label="Buyer Account">
    <h2>Buyer Account</h2>
    {finishLink}
    <p>Signed in as <strong className="account-email">{account.contact.email}</strong>. Your contact details are stored
      once, encrypted, and are never handed to a dealer.</p>
    <ContactForm account={account} busy={busy} onSave={(body) => run(async () => {
      setAccount(await (await call("/api/v1/account/contact", { method: "PUT", body: JSON.stringify(body) })).json());
      setMessage("Contact details saved.");
    }, "Contact details could not be saved. Check the phone number format and retry.")} />
    <NotificationPreferences onSessionEnded={() => signedOut(ENDED)} />
    <MailboxConnection onSessionEnded={() => signedOut(ENDED)} />
    <Passkeys request={call} onSessionEnded={() => signedOut(ENDED)} />
    <Passwords key={loads} request={call} onSessionEnded={() => signedOut(ENDED)} />
    <h3>Your Buying Cases</h3>
    {account.cases.length === 0 ? <p className="muted">No cases saved yet. A case you start while signed in is saved here, and
      a case opened by its private link can be attached.</p>
      : <ul className="account-cases" aria-label="Your Buying Cases">
        {account.cases.map(c => {
          const name = c.vehicle ?? "Vehicle not entered yet";
          const started = startedOn(c.startedAt);
          const open = c.state === "ACTIVE";
          return <li key={c.caseId}>
            <span className="account-case-name">{name}</span>
            <span className="account-case-meta">{[c.dealer, started && `Started ${started}`].filter(Boolean).join(" · ")
              || `Case ${c.caseId.slice(0, 8)}`}</span>
            {open ? <button className="btn-secondary" disabled={busy} aria-label={`Open ${name}${started ? `, started ${started}` : ""}`}
              onClick={() => run(async () => {
                try {
                  const opened = await (await call(`/api/v1/account/cases/${encodeURIComponent(c.caseId)}/open`, { method: "POST" })).json();
                  onCaseOpened?.({ caseId: opened.caseId, token: opened.accessToken });
                  setMessage("Case opened from your account.");
                } catch (problem) {
                  if ((problem as Problem)?.status === 404) { setLoads(value => value + 1); throw { status: 0, detail: UNAVAILABLE }; }
                  throw problem;
                }
              }, "This case could not be opened. Retry safely.")}>Open case</button>
              : <span className="account-case-state">Unavailable: {STATE_LABELS[c.state] ?? c.state}</span>}
          </li>;
        })}
      </ul>}
    {caseCredentials && !attached && <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setAccount(await (await call("/api/v1/account/cases", { method: "POST", caseToken: caseCredentials.token,
        body: JSON.stringify({ caseId: caseCredentials.caseId }) })).json());
      setMessage("Case attached. Its private case link keeps working.");
    }, "This case could not be attached. Retry safely.")}>Attach this Buying Case</button>}
    <div className="actions">
      <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
        const url = URL.createObjectURL(await (await call("/api/v1/account/export", { method: "POST" })).blob());
        const link = document.createElement("a"); link.href = url; link.download = "buyer-account-export.zip";
        link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        setMessage("Account export ready. It includes the export of every attached case.");
      }, "Export failed. Retry safely.")}>Export account data</button>
      <button className="btn-ghost" disabled={busy} onClick={signOut}>Sign out</button>
      <button className="btn-ghost" disabled={busy} onClick={() => run(async () => {
        await call("/api/v1/account/sign-out?everywhere=true", { method: "POST" }); signedOut(); setMessage("Signed out on every device.");
      }, "Sign-out failed. Retry safely.")}>Sign out everywhere</button>
    </div>
    <AccountDeletion busy={busy} onDelete={() => run(async () => {
      const result = await (await call("/api/v1/account/deletion", { method: "POST" })).json();
      setLoading(false); setAccount(undefined); setReceipt(result);
    }, "Deletion request could not be verified. Retry safely.")} />
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}

const NOTIFICATION_LABELS: Record<string, string> = {
  DEALER_REPLY: "Dealer replies", DRAFT_NEEDS_APPROVAL: "Drafts needing approval",
  MANDATE_EXPIRING: "Mandate expiry", APPOINTMENT: "Appointments", ACCOUNT_SECURITY: "Account security",
};
type NotificationPreference = { type: string; enabled: boolean; mandatory: boolean };

function NotificationPreferences({ onSessionEnded }: { onSessionEnded: () => void }) {
  const [preferences, setPreferences] = useState<NotificationPreference[]>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  useEffect(() => {
    let current = true;
    call("/api/v1/account/notifications").then(response => response.json()).then(value => {
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
  }, [loads]);
  async function change(type: string, enabled: boolean) {
    setBusy(true); setError(""); setNotice("");
    try {
      const value = await (await call("/api/v1/account/notifications", { method: "PUT",
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
