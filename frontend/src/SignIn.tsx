import { useEffect, useRef, useState } from "react";
import { passkeyAutofillAvailable, passkeysSupported, signInWithPasskey } from "./Passkeys";
import "./SignIn.css";

type Request = (path: string, init?: RequestInit & { key?: string }) => Promise<Response>;
type Step = "email" | "sent" | "password";
export type SignInMethod = "password" | "passkey";

const SENT = "Check your email. The link works for 15 minutes.";

/**
 * One email box and one Continue button. Continue emails a sign-in link, and the answer is the same whether or not
 * an account exists. A passkey is offered from the email box's autofill; a password is one step away.
 */
export function SignIn({ request, onSignedIn, offerPasskeys = true }: {
  request: Request; onSignedIn: (method: SignInMethod) => Promise<void>;
  /** Whether the panel is showing, so a closed panel does not ask the server for a passkey sign-in. */
  offerPasskeys?: boolean;
}) {
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
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
          setError("Passkey sign-in could not be completed. Try again or continue with your email.");
      }
    })();
    return () => waiting.abort();
  }, [step, offerPasskeys]);

  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (problem) { setError((problem as { detail?: string })?.detail ?? failure); }
    finally { setBusy(false); }
  }
  function sendLink(notice = SENT) {
    return run(async () => {
      await request("/api/v1/account/sign-in-requests", { method: "POST", key: crypto.randomUUID(), body: JSON.stringify({ email }) });
      setPassword(""); setStep("sent"); setNotice(notice);
    }, "The sign-in link could not be sent. Retry safely.");
  }
  function differentEmail() { setStep("email"); setPassword(""); setError(""); setNotice(""); }
  const passkeyButton = passkeysSupported() && <button type="button" className="btn-link" disabled={busy} onClick={() => void run(async () => {
    await signInWithPasskey(request); await onSignedIn("passkey");
  }, "Passkey sign-in could not be completed. Try again or use an email link.")}>Sign in with a passkey</button>;
  const changeEmail = <button type="button" className="btn-link" onClick={differentEmail}>Use a different email</button>;

  return <div className="sign-in">
    {step === "email" && <form aria-label="Sign in" onSubmit={event => { event.preventDefault(); void sendLink(); }}>
      <label>Email<input type="email" autoComplete="username webauthn" required maxLength={254} value={email}
        onChange={event => setEmail(event.target.value)} /></label>
      <button disabled={busy}>Continue</button>
    </form>}
    {step === "sent" && <div className="sign-in-step">
      <p role="status" tabIndex={-1} ref={sentNotice}>{notice}</p>
      <p className="sign-in-email">{email}</p>
      <div className="sign-in-links">
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink()}>Send again</button>
        {changeEmail}
        <button type="button" className="btn-link" disabled={busy} onClick={() => { setStep("password"); setError(""); setNotice(""); }}>Sign in with a password</button>
        {passkeyButton}
      </div>
    </div>}
    {step === "password" && <form aria-label="Password sign-in" className="sign-in-step" onSubmit={event => {
      event.preventDefault();
      void run(async () => {
        try { await request("/api/v1/account/password/sign-in", { method: "POST", body: JSON.stringify({ email, password }) }); }
        finally { setPassword(""); }
        await onSignedIn("password");
      }, "Sign-in could not be completed. Check your details or use an email link.");
    }}>
      <p className="sign-in-email">{email}</p>
      <input type="email" autoComplete="username" value={email} readOnly hidden />
      <label>Password<input type="password" autoComplete="current-password" required maxLength={72} autoFocus
        value={password} onChange={event => setPassword(event.target.value)} /></label>
      <button disabled={busy}>Sign in</button>
      <div className="sign-in-links">
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink(`${SENT} After you sign in, you can set a new password.`)}>Forgot password?</button>
        <button type="button" className="btn-link" disabled={busy} onClick={() => void sendLink()}>Email me a link instead</button>
        {changeEmail}
      </div>
    </form>}
    {error && <p role="alert">{error}</p>}
  </div>;
}
