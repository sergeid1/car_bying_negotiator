import { useEffect, useState } from "react";

type Request = (path: string, init?: RequestInit) => Promise<Response>;
type Metadata = { id: string; nickname: string; createdAt: string; lastUsedAt: string | null };
type Options = { attemptId: string; publicKey: Record<string, any> };
const ROOT = "/api/v1/account/passkeys";
export const passkeysSupported = () => typeof PublicKeyCredential !== "undefined" && !!navigator.credentials;
function bytes(value: string): ArrayBuffer {
  return Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/")), char => char.charCodeAt(0)).buffer;
}
function encoded(value: ArrayBuffer) {
  return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function credentialJSON(value: Credential | null) {
  if (!value) throw new Error("Passkey cancelled");
  const key = value as PublicKeyCredential;
  const response = key.response;
  const data = response instanceof AuthenticatorAttestationResponse
    ? { clientDataJSON: encoded(response.clientDataJSON), attestationObject: encoded(response.attestationObject), transports: response.getTransports() }
    : { clientDataJSON: encoded(response.clientDataJSON), authenticatorData: encoded((response as AuthenticatorAssertionResponse).authenticatorData),
      signature: encoded((response as AuthenticatorAssertionResponse).signature), userHandle: (response as AuthenticatorAssertionResponse).userHandle
        ? encoded((response as AuthenticatorAssertionResponse).userHandle!) : null };
  return JSON.stringify({ id: key.id, rawId: encoded(key.rawId), type: key.type, response: data,
    clientExtensionResults: key.getClientExtensionResults(), authenticatorAttachment: key.authenticatorAttachment });
}
/** Whether the browser can offer saved passkeys in the email box's autofill, without a separate button. */
export async function passkeyAutofillAvailable() {
  try {
    return passkeysSupported() && typeof PublicKeyCredential.isConditionalMediationAvailable === "function"
      && await PublicKeyCredential.isConditionalMediationAvailable();
  } catch { return false; }
}
/** With `autofill`, the browser waits for the Buyer to pick a passkey from the email box; `signal` cancels the wait. */
export async function signInWithPasskey(request: Request, { autofill = false, signal }: { autofill?: boolean; signal?: AbortSignal } = {}) {
  const options: Options = await (await request(`${ROOT}/sign-in/options`, { method: "POST", signal })).json();
  const publicKey = { ...options.publicKey, challenge: bytes(options.publicKey.challenge),
    allowCredentials: options.publicKey.allowCredentials?.map((key: any) => ({ ...key, id: bytes(key.id) })) };
  const credential = credentialJSON(await navigator.credentials.get({ publicKey: publicKey as PublicKeyCredentialRequestOptions,
    signal, ...(autofill ? { mediation: "conditional" as CredentialMediationRequirement } : {}) }));
  await request(`${ROOT}/sign-in`, { method: "POST", body: JSON.stringify({ attemptId: options.attemptId, credential }) });
}
export function Passkeys({ request, onSessionEnded }: { request: Request; onSessionEnded: () => void }) {
  const [keys, setKeys] = useState<Metadata[]>();
  const [nickname, setNickname] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loads, setLoads] = useState(0);
  function failed(problem: unknown) {
    if ((problem as { status?: number })?.status === 401) onSessionEnded();
    else setError("Passkey request could not be completed. Retry or use an email sign-in link.");
  }
  useEffect(() => {
    let current = true;
    request(ROOT).then(response => response.json()).then(value => {
      if (!Array.isArray(value)) throw new Error("Passkeys unavailable");
      if (current) { setKeys(value); setError(""); }
    }).catch(problem => { if (current) failed(problem); });
    return () => { current = false; };
  }, [loads]);
  async function run(action: () => Promise<void>) {
    setBusy(true); setError(""); setNotice("");
    try { await action(); } catch (problem) { failed(problem); } finally { setBusy(false); }
  }
  return <fieldset>
    <legend>Passkeys</legend>
    <p>Use your device or security key to sign in. Email links remain available if you lose access to a passkey.</p>
    {keys && (keys.length ? <ul className="account-cases" aria-label="Your passkeys">{keys.map(key => <li key={key.id}>
      <strong className="account-case-name">{key.nickname}</strong>
      <span>Created {new Date(key.createdAt).toLocaleDateString()}. {key.lastUsedAt
        ? `Last used ${new Date(key.lastUsedAt).toLocaleDateString()}.` : "Not used to sign in yet."}</span>
      <button className="btn-secondary" disabled={busy} onClick={() => void run(async () => {
        setKeys(await (await request(`${ROOT}/${encodeURIComponent(key.id)}`, { method: "DELETE" })).json());
        setNotice("Passkey removed. Email sign-in links remain available.");
      })} aria-label={`Remove ${key.nickname}`}>Remove passkey</button>
    </li>)}</ul> : <p>No passkeys added yet.</p>)}
    <form onSubmit={event => { event.preventDefault(); void run(async () => {
      const options: Options = await (await request(`${ROOT}/registration/options`, { method: "POST" })).json();
      const publicKey = { ...options.publicKey, challenge: bytes(options.publicKey.challenge), user: { ...options.publicKey.user, id: bytes(options.publicKey.user.id) },
        excludeCredentials: options.publicKey.excludeCredentials?.map((key: any) => ({ ...key, id: bytes(key.id) })) };
      const credential = credentialJSON(await navigator.credentials.create({ publicKey: publicKey as PublicKeyCredentialCreationOptions }));
      setKeys(await (await request(`${ROOT}/registration`, { method: "POST",
        body: JSON.stringify({ attemptId: options.attemptId, nickname, credential }) })).json());
      setNickname(""); setNotice("Passkey added. Check your account-security email.");
    }); }}>
      <label>Passkey nickname<input maxLength={80} required value={nickname} onChange={event => setNickname(event.target.value)} /></label>
      <button className="btn-secondary" disabled={busy || !passkeysSupported()}>Add a passkey</button>
    </form>
    {!passkeysSupported() && <p>This browser does not support passkeys. Use an email sign-in link.</p>}
    {error && <><p role="alert">{error}</p><button className="btn-secondary" disabled={busy} onClick={() => setLoads(value => value + 1)}>Reload passkeys</button></>}
    {notice && <p role="status">{notice}</p>}
  </fieldset>;
}
