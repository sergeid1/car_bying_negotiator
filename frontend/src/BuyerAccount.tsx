import { createContext, useEffect, useRef, useState } from "react";
import type { Credentials } from "./CasePortability";
import { MailboxConnection } from "./MailboxConnection";
import { Passkeys } from "./Passkeys";
import { Passwords } from "./Passwords";
import { openSidePanel } from "./SidePanel";
import { SignIn, type SignInMode } from "./SignIn";
import * as format from "./i18n/format";
import { useLanguageChoice, useMessages } from "./i18n/LanguageProvider";
import { isLanguage, readStoredLanguage } from "./i18n/languageChoice";

type Contact = { email: string; firstName: string | null; lastName: string | null; phone: string | null };
type AttachedCase = {
  caseId: string; attachedAt: string; state: string; startedAt?: string | null; vehicle?: string | null; dealer?: string | null;
};
type Account = {
  contact: Contact; createdAt: string; sessionExpiresAt: string; cases: AttachedCase[]; preferredLanguage?: string | null;
};
type Receipt = { state: "DELETION_PENDING" | "DELETED"; failureCode?: string | null; cases: { caseId: string; state: string }[] };
type Problem = { status: number; code?: string; detail?: string };

export const AccountSignedInContext = createContext<boolean | undefined>(undefined);

export type AccountControls = { email?: string; busy: boolean; signOut: () => void };
export function AccountHeader({ controls, onOpen }: { controls?: AccountControls; onOpen: () => void }) {
  const { m: { account: { buyerAccount: t } } } = useMessages();
  return <div className="header-account" aria-label={t.controls}>
    {controls?.email ? <>
      <button className="btn-ghost header-email" title={controls.email} aria-haspopup="dialog" onClick={onOpen}>{controls.email}</button>
      <button className="btn-ghost" disabled={controls.busy} onClick={controls.signOut}>{t.signOut}</button>
    </> : <button className="btn-ghost" aria-haspopup="dialog" onClick={onOpen}>{t.signIn}</button>}
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
  const { m: { account: { buyerAccount: t } }, fill, fillNodes } = useMessages();
  const STATE_LABELS: Record<string, string> = t.states;
  const LINK_MESSAGES: Record<string, string> = t.linkMessages;
  const ENDED = t.ended;
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
      setError(socialReturn.error === "cancelled" ? t.providerCancelled : t.providerFailed);
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
      const notice = result.provider === "GOOGLE" ? t.signedInWithGoogle : result.provider === "APPLE" ? t.signedInWithApple : t.signedIn;
      setMessage(notice); onSignInComplete?.(notice);
    }, t.providerFailed).then(() => {
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
        if (!value?.contact || !Array.isArray(value.cases)) throw new Error("account-unavailable");
        if (current) { setAccount(value); setLoading(false); }
      })
      .catch((problem: Problem) => {
        if (!current) return;
        setLoading(false);
        if (problem?.status === 401) {
          if (recheck) signedOut(ENDED); else setAccount(undefined);
        } else setError(t.loadFailed);
      });
    return () => { current = false; };
  }, [loads, recheck, socialReturn.state]);
  useEffect(() => { onSignedIn?.(!!account); }, [account]);
  // The language follows a signed-in Buyer: a saved choice applies here, and a choice made here is saved.
  const { adopt, setAccountSaver } = useLanguageChoice();
  const signedInAs = account?.contact.email;
  useEffect(() => {
    if (!signedInAs) return;
    const save = (language: string) => {
      call("/api/v1/account/language", { method: "PUT", body: JSON.stringify({ language }) })
        .catch(() => { /* The choice still applies in this browser. */ });
    };
    if (isLanguage(account?.preferredLanguage)) adopt(account.preferredLanguage);
    else { const stored = readStoredLanguage(); if (stored) save(stored); }
    setAccountSaver(save);
    return () => setAccountSaver(undefined);
  }, [signedInAs]);
  useEffect(() => { onControls?.({ email: account?.contact.email, busy, signOut }); }, [account, busy]);

  function signOut() {
    void run(async () => {
      await call("/api/v1/account/sign-out", { method: "POST" }); signedOut(); setMessage(t.signedOut);
    }, t.signOutFailed);
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
  }, t.linkCheckFailed)}>{t.finishSignIn}</button>;

  if (receipt) return <section id="buyer-account" tabIndex={-1} className="panel" aria-label={t.label}>
    <h2>{t.heading}</h2>
    <p role="status">{receipt.state === "DELETED" ? t.deletionVerified : t.deletionPending}</p>
    {receipt.failureCode && <p>{t.deletionRetry}</p>}
    {receipt.cases.length > 0 && <ul className="account-cases" aria-label={t.deletingCases}>
      {receipt.cases.map(c => <li key={c.caseId}>{fill(t.caseState, { id: c.caseId.slice(0, 8), state: STATE_LABELS[c.state] ?? c.state })}</li>)}
    </ul>}
    <p>{t.backupsUnverified}</p>
    {receipt.state !== "DELETED" && <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setReceipt(await (await call("/api/v1/account/deletion")).json());
    }, t.progressFailed)}>{t.checkProgress}</button>}
    {error && <p role="alert">{error}</p>}
  </section>;

  if (!account) return <section id="buyer-account" tabIndex={-1} className="panel" aria-label={t.label}>
    {finishLink}
    {loading && !error && <p role="status">{t.loading}</p>}
    {error && <button className="btn-secondary" onClick={() => { setError(""); setLoads(loads + 1); }}>{t.retryLoading}</button>}
    {!loading && !linkSecret && <SignIn request={call} mode={signInMode} onMode={onSignInMode} offerPasskeys={showing} onSignedIn={async method => {
      setAccount(await (await call("/api/v1/account")).json());
      setMessage(method === "password" ? t.signedInWithPassword : t.signedInWithPasskey); onSignInComplete?.();
    }} />}
    {signInMode === "signup" && <p className="sign-in-footer">{t.signUpFooter}</p>}
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;

  const attached = caseCredentials && account.cases.some(c => c.caseId === caseCredentials.caseId);
  return <section id="buyer-account" tabIndex={-1} className="panel" aria-label={t.label}>
    <h2>{t.heading}</h2>
    {finishLink}
    <p>{fillNodes(t.signedInAs, { email: <strong className="account-email">{account.contact.email}</strong> })}</p>
    <ContactForm account={account} busy={busy} onSave={(body) => run(async () => {
      setAccount(await (await call("/api/v1/account/contact", { method: "PUT", body: JSON.stringify(body) })).json());
      setMessage(t.contactSaved);
    }, t.contactFailed)} />
    <NotificationPreferences onSessionEnded={() => signedOut(ENDED)} />
    <MailboxConnection onSessionEnded={() => signedOut(ENDED)} />
    <Passkeys request={call} onSessionEnded={() => signedOut(ENDED)} />
    <Passwords key={loads} request={call} onSessionEnded={() => signedOut(ENDED)} />
    <h3>{t.casesHeading}</h3>
    {account.cases.length === 0 ? <p className="muted">{t.noCases}</p>
      : <ul className="account-cases" aria-label={t.casesList}>
        {account.cases.map(c => {
          const name = c.vehicle ?? t.vehicleMissing;
          const started = startedOn(c.startedAt);
          const open = c.state === "ACTIVE";
          return <li key={c.caseId}>
            <span className="account-case-name">{name}</span>
            <span className="account-case-meta">{[c.dealer, started && fill(t.started, { date: started })].filter(Boolean).join(" · ")
              || fill(t.caseShort, { id: c.caseId.slice(0, 8) })}</span>
            {open ? <button className="btn-secondary" disabled={busy} aria-label={started ? fill(t.openNamedStarted, { name, date: started }) : fill(t.openNamed, { name })}
              onClick={() => run(async () => {
                try {
                  const opened = await (await call(`/api/v1/account/cases/${encodeURIComponent(c.caseId)}/open`, { method: "POST" })).json();
                  onCaseOpened?.({ caseId: opened.caseId, token: opened.accessToken });
                  setMessage(t.caseOpened);
                } catch (problem) {
                  if ((problem as Problem)?.status === 404) { setLoads(value => value + 1); throw { status: 0, detail: t.caseUnavailable }; }
                  throw problem;
                }
              }, t.openFailed)}>{t.openCase}</button>
              : <span className="account-case-state">{fill(t.unavailableState, { state: STATE_LABELS[c.state] ?? c.state })}</span>}
          </li>;
        })}
      </ul>}
    {caseCredentials && !attached && <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
      setAccount(await (await call("/api/v1/account/cases", { method: "POST", caseToken: caseCredentials.token,
        body: JSON.stringify({ caseId: caseCredentials.caseId }) })).json());
      setMessage(t.attached);
    }, t.attachFailed)}>{t.attach}</button>}
    <div className="actions">
      <button className="btn-secondary" disabled={busy} onClick={() => run(async () => {
        const url = URL.createObjectURL(await (await call("/api/v1/account/export", { method: "POST" })).blob());
        const link = document.createElement("a"); link.href = url; link.download = "buyer-account-export.zip";
        link.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        setMessage(t.exportReady);
      }, t.exportFailed)}>{t.export}</button>
      <button className="btn-ghost" disabled={busy} onClick={signOut}>{t.signOut}</button>
      <button className="btn-ghost" disabled={busy} onClick={() => run(async () => {
        await call("/api/v1/account/sign-out?everywhere=true", { method: "POST" }); signedOut(); setMessage(t.signedOutEverywhere);
      }, t.signOutFailed)}>{t.signOutEverywhere}</button>
    </div>
    <AccountDeletion busy={busy} onDelete={() => run(async () => {
      const result = await (await call("/api/v1/account/deletion", { method: "POST" })).json();
      setLoading(false); setAccount(undefined); setReceipt(result);
    }, t.deletionFailed)} />
    {message && <p role="status">{message}</p>}{error && <p role="alert">{error}</p>}
  </section>;
}

type NotificationPreference = { type: string; enabled: boolean; mandatory: boolean };

function NotificationPreferences({ onSessionEnded }: { onSessionEnded: () => void }) {
  const { m: { account: { notifications: t } }, fill } = useMessages();
  const NOTIFICATION_LABELS: Record<string, string> = t.labels;
  const [preferences, setPreferences] = useState<NotificationPreference[]>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  useEffect(() => {
    let current = true;
    call("/api/v1/account/notifications").then(response => response.json()).then(value => {
      if (current) {
        if (!Array.isArray(value)) throw new Error("preferences-unavailable");
        setPreferences(value); setError("");
      }
    }).catch((problem: Problem) => {
      if (!current) return;
      if (problem?.status === 401) onSessionEnded();
      else setError(t.loadFailed);
    });
    return () => { current = false; };
  }, [loads]);
  async function change(type: string, enabled: boolean) {
    setBusy(true); setError(""); setNotice("");
    try {
      const value = await (await call("/api/v1/account/notifications", { method: "PUT",
        body: JSON.stringify({ type, enabled }) })).json();
      setPreferences(value); setNotice(t.saved);
    } catch (problem) {
      if ((problem as Problem)?.status === 401) onSessionEnded();
      else setError(t.saveFailed);
    } finally { setBusy(false); }
  }
  return <fieldset>
    <legend>{t.legend}</legend>
    <p>{t.intro}</p>
    {preferences?.map(preference => <label key={preference.type}>
      <input type="checkbox" checked={preference.enabled} disabled={busy || preference.mandatory}
        onChange={event => void change(preference.type, event.target.checked)} />
      {preference.mandatory ? fill(t.required, { label: NOTIFICATION_LABELS[preference.type] }) : NOTIFICATION_LABELS[preference.type]}
    </label>)}
    {error && <><p role="alert">{error}</p>{!preferences && <button className="btn-secondary" type="button"
      onClick={() => setLoads(loads + 1)}>{t.retry}</button>}</>}
    {notice && <p role="status">{notice}</p>}
  </fieldset>;
}

function ContactForm({ account, busy, onSave }: {
  account: Account; busy: boolean; onSave: (body: { firstName: string; lastName: string; phone: string }) => void;
}) {
  const [firstName, setFirstName] = useState(account.contact.firstName ?? "");
  const [lastName, setLastName] = useState(account.contact.lastName ?? "");
  const [phone, setPhone] = useState(account.contact.phone ?? "");
  const { m: { account: { contactForm: t } } } = useMessages();
  return <form aria-label={t.label} onSubmit={event => { event.preventDefault(); onSave({ firstName, lastName, phone }); }}>
    <label>{t.firstName}<input autoComplete="given-name" maxLength={100} value={firstName} onChange={event => setFirstName(event.target.value)} /></label>
    <label>{t.lastName}<input autoComplete="family-name" maxLength={100} value={lastName} onChange={event => setLastName(event.target.value)} /></label>
    <label>{t.phone}<input type="tel" autoComplete="tel" maxLength={32} value={phone} onChange={event => setPhone(event.target.value)} /></label>
    <button className="btn-secondary" disabled={busy}>{t.save}</button>
  </form>;
}

function AccountDeletion({ busy, onDelete }: { busy: boolean; onDelete: () => void }) {
  const [confirmed, setConfirmed] = useState(false);
  const { m: { account: { accountDeletion: t } } } = useMessages();
  return <>
    <h3>{t.heading}</h3>
    <p>{t.explanation}</p>
    <label><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{t.confirm}</label>
    <button className="btn-danger" disabled={busy || !confirmed} onClick={onDelete}>{t.delete}</button>
  </>;
}
