import { useEffect, useRef, useState } from "react";
import { passkeyAutofillAvailable, passkeysSupported, signInWithPasskey } from "./Passkeys";
import "./SignIn.css";
import { useMessages } from "./i18n/LanguageProvider";

type Request = (path: string, init?: RequestInit & { key?: string }) => Promise<Response>;
type Step = "email" | "sent" | "password";
export type SignInMethod = "password" | "passkey";
export type SignInMode = "login" | "signup";
type Provider = "GOOGLE" | "APPLE";

/** Brand names, the same in every language. */
const PROVIDER_NAMES: Record<Provider, string> = { GOOGLE: "Google", APPLE: "Apple" };

/**
 * One email box and one Continue button. Continue emails a sign-in link, and the answer is the same whether or not
 * an account exists. A passkey is offered from the email box's autofill; a password is one step away.
 */
export function SignIn({ request, onSignedIn, offerPasskeys = true, mode = "login", onMode }: {
  request: Request; onSignedIn: (method: SignInMethod) => Promise<void>;
  mode?: SignInMode; onMode?: (mode: SignInMode) => void;
  /** Whether the panel is showing, so a closed panel does not ask the server for a passkey sign-in. */
  offerPasskeys?: boolean;
}) {
  const { m: { account: { signIn: t } }, fill, language } = useMessages();
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [providers, setProviders] = useState<Provider[]>([]);
  const emailInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!offerPasskeys) { setProviders([]); return; }
    let current = true;
    request("/api/v1/account/social-sign-in/providers").then(response => response.json()).then(value => {
      if (current) setProviders((["GOOGLE", "APPLE"] as Provider[]).filter(provider => value?.providers?.includes(provider)));
    }).catch(() => { if (current) setProviders([]); });
    return () => { current = false; };
  }, [offerPasskeys]);
  useEffect(() => { setStep("email"); setPassword(""); setError(""); setNotice(""); }, [mode]);
  // The email box goes away after Continue, so focus moves to what replaced it.
  const sentNotice = useRef<HTMLParagraphElement>(null);
  useEffect(() => { if (step === "sent") sentNotice.current?.focus(); }, [step]);

  useEffect(() => {
    if (step !== "email" || !offerPasskeys) return;
    const waiting = new AbortController();
    void (async () => {
      if (!await passkeyAutofillAvailable() || waiting.signal.aborted) return;
      let chosen = false;
      try {
        await signInWithPasskey(request, { autofill: true, signal: waiting.signal, onChosen: () => { chosen = true; } });
        await onSignedIn("passkey");
      } catch (problem) {
        // Only a passkey the Buyer picked can fail; a background request that fails first stays quiet.
        if (chosen && !waiting.signal.aborted && (problem as { status?: number })?.status)
          setError(t.passkeyAutofillFailed);
      }
    })();
    return () => waiting.abort();
  }, [step, offerPasskeys]);

  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (problem) { setError((problem as { detail?: string })?.detail ?? failure); }
    finally { setBusy(false); }
  }
  function sendLink(notice: string = t.sent) {
    return run(async () => {
      await request("/api/v1/account/sign-in-requests", { method: "POST", key: crypto.randomUUID(), body: JSON.stringify({ email, language }) });
      setPassword(""); setStep("sent"); setNotice(notice);
    }, t.linkFailed);
  }
  function differentEmail() { setStep("email"); setPassword(""); setError(""); setNotice(""); }
  const passkeyButton = passkeysSupported() && <button type="button" className="btn-secondary sign-in-provider" disabled={busy} onClick={() => void run(async () => {
    await signInWithPasskey(request); await onSignedIn("passkey");
  }, t.passkeyFailed)}>{t.continueWithPasskey}</button>;
  const changeEmail = <button type="button" className="btn-link" onClick={differentEmail}>{t.differentEmail}</button>;
  const providerButtons = providers.length > 0 && <div className="sign-in-providers">
    {providers.map(provider => <button key={provider} type="button" className={`btn-secondary sign-in-provider provider-${provider.toLowerCase()}`}
      disabled={busy} onClick={() => void run(async () => {
        const result = await (await request("/api/v1/account/social-sign-in", { method: "POST", body: JSON.stringify({ provider }) })).json();
        window.location.assign(result.authorizationUrl);
      }, fill(t.providerFailed, { provider: PROVIDER_NAMES[provider] }))}>
      <ProviderMark provider={provider} />{fill(t.continueWithProvider, { provider: PROVIDER_NAMES[provider] })}
    </button>)}
  </div>;
  const divider = providers.length > 0 && <div className="sign-in-divider"><span>{t.or}</span></div>;

  return <div className="sign-in">
    {onMode && <p className="sign-in-switch">{mode === "login" ? t.noAccount : t.haveAccount}{" "}
      <button type="button" className="btn-link" disabled={busy} onClick={() => onMode(mode === "login" ? "signup" : "login")}>
        {mode === "login" ? t.signUp : t.logIn}
      </button></p>}
    {step === "email" && mode === "signup" && <>{providerButtons}{divider}</>}
    {step === "email" && <form aria-label={t.form} onSubmit={event => { event.preventDefault(); void sendLink(); }}>
      <label>{t.email}<input ref={emailInput} type="email" autoComplete="username webauthn" required maxLength={254} value={email}
        onChange={event => setEmail(event.target.value)} /></label>
      {mode === "login" && <button type="button" className="btn-link sign-in-password" disabled={busy} onClick={() => {
        if (!emailInput.current?.reportValidity()) return;
        setStep("password"); setError("");
      }}>{t.usePassword}</button>}
      <button disabled={busy}>{t.continueWithEmail}</button>
    </form>}
    {step === "email" && mode === "login" && <>{divider}{providerButtons}{passkeyButton}</>}
    {step === "sent" && <div className="sign-in-step">
      <p role="status" tabIndex={-1} ref={sentNotice}>{notice}</p>
      <p className="sign-in-email">{email}</p>
      <div className="sign-in-links">
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink()}>{t.sendAgain}</button>
        {changeEmail}
        <button type="button" className="btn-link" disabled={busy} onClick={() => { setStep("password"); setError(""); setNotice(""); }}>{t.signInWithPassword}</button>
        {passkeyButton}
      </div>
    </div>}
    {step === "password" && <form aria-label={t.passwordForm} className="sign-in-step" onSubmit={event => {
      event.preventDefault();
      void run(async () => {
        try { await request("/api/v1/account/password/sign-in", { method: "POST", body: JSON.stringify({ email, password }) }); }
        finally { setPassword(""); }
        await onSignedIn("password");
      }, t.passwordFailed);
    }}>
      <p className="sign-in-email">{email}</p>
      <input type="email" autoComplete="username" value={email} readOnly hidden />
      <label>{t.password}<input type="password" autoComplete="current-password" required maxLength={72} autoFocus
        value={password} onChange={event => setPassword(event.target.value)} /></label>
      <button disabled={busy}>{t.signInButton}</button>
      <div className="sign-in-links">
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink(t.sentForgotPassword)}>{t.forgotPassword}</button>
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink()}>{t.emailLinkInstead}</button>
        {changeEmail}
      </div>
    </form>}
    {error && <p role="alert">{error}</p>}
  </div>;
}

// Google Identity branding assets and Apple's Sign in with Apple logo; kept inline for the page's CSP.
function ProviderMark({ provider }: { provider: Provider }) {
  return provider === "GOOGLE" ? <svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true">
    <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5Z" />
    <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65Z" />
    <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19Z" />
    <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48Z" />
  </svg> : <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path d="M17.05 12.54c.03 3.1 2.72 4.13 2.75 4.14-.02.07-.43 1.47-1.42 2.92-.86 1.25-1.75 2.5-3.15 2.53-1.37.03-1.82-.81-3.39-.81-1.57 0-2.07.79-3.37.84-1.35.05-2.38-1.35-3.24-2.6-1.76-2.55-3.11-7.2-1.31-10.34.89-1.56 2.49-2.54 4.22-2.56 1.32-.03 2.57.89 3.38.89.81 0 2.33-1.1 3.92-.94.67.03 2.55.27 3.76 2.04-.1.06-2.25 1.31-2.23 3.89ZM14.47 4.92c.72-.87 1.2-2.08 1.07-3.29-1.04.04-2.3.69-3.05 1.56-.67.77-1.26 2-1.1 3.18 1.16.09 2.35-.59 3.08-1.45Z" />
  </svg>;
}
