# Mailbox provider boundaries

Production connecting remains disabled: set `MAILBOX_SENDER=none`. Ticket 92 adds code
validation and local contract evidence; it does not authorize a live rollout or record
Google OAuth consent-screen verification or Microsoft publisher verification.

## Identity validation

Only the confidential-client authorization-code flow accepts an ID token, directly from
the adapter's hard-coded HTTPS token endpoint. The JDK validates the TLS certificate and
hostname, and redirects are disabled. No callback-supplied ID token, discovery URL, issuer
URL or token endpoint is accepted. Package-local endpoint overrides exist only in test
subclasses for loopback contract tests.

We use the direct-token-endpoint TLS exception in [OpenID Connect Core §3.1.3.7](https://openid.net/specs/openid-connect-core-1_0-errata2.html#IDTokenValidation):
the token's JWS signature is not independently verified against downloaded signing keys.
This decision applies only to this back-channel flow. It depends on the trusted TLS endpoint,
client credentials, and the endpoint issuing an authentic token; TLS does not validate the
identity claims. Compact JWT shape, nonempty signature encoding and an RS256 header are
required, but these structural checks are not signature verification. Changing to an
implicit/hybrid flow, accepting tokens from a browser, adding configurable endpoints, or
weakening TLS requires a new signature-validation design before release.

Both providers require a nonempty string subject, integer unexpired `exp`, and an exact
client audience (a string or a singleton string array). Extra audiences are refused because
no other client is trusted. `azp`, when present, must be the configured client ID. A supplied
`nbf` must be an integer no later than now. The authorization request includes a nonce equal
to the S256 PKCE challenge; exchange recomputes it from the encrypted, single-use verifier
and requires an exact `nonce` claim. No new nonce column or callback parameter is needed.
There is no clock-skew allowance; deployment clocks must be synchronized.

- Google accepts only `https://accounts.google.com` or `accounts.google.com`, following
  [Google's OpenID Connect documentation](https://developers.google.com/identity/openid-connect/openid-connect).
  The sending address must be a string email with a boolean `email_verified=true`.
- Microsoft accepts only v2 tokens with a GUID `tid` and issuer exactly
  `https://login.microsoftonline.com/{tid}/v2.0`, including the personal-account tenant.
  `common`, v1, mismatched tenants and other authorities are refused. The audience is the
  registered application ID. See [Microsoft ID tokens](https://learn.microsoft.com/en-us/entra/identity-platform/id-tokens)
  and [issuer/tenant validation](https://learn.microsoft.com/en-us/entra/identity-platform/access-tokens).
  A string `email`, or `preferred_username` if email is absent, supplies the display address;
  this is not an authorization identity or an email-verification claim. Graph sends as `me`.

Token replies require string access/refresh/identity tokens, Bearer token type and a positive
integer access lifetime of at most 24 hours. Missing or malformed replies fail with redacted
failure kinds. The service additionally refuses grants missing send permission or containing
scopes outside the provider's send/identity allowlist and withdraws refused grants best effort.
The exact configured redirect URI is used unchanged in authorization and token exchange.
State is account-bound, expiring and single-use; expired state is consumed without exchange.

## Sends and uncertain outcomes

`MailboxProviderContractTest` runs both real adapters against local HTTP servers. It asserts
authorization/PKCE/nonce/redirect and refresh forms, identity validation, malformed replies,
Gmail's RFC 5322 payload and Graph's exact recipient/subject/plain-text body. It covers 401,
invalid_grant, quota, timeout, lost connection and 5xx. Every send has one HTTP dispatch;
there is no application retry. SENT means API acceptance, never confirmed inbox delivery.

`ConnectedMailboxAcceptanceTest` uses latch-controlled fakes for concurrent requests,
preparation supersession, slow provider calls, stale completion and persisted restart.
A claim commits DELIVERY_UNKNOWN on the handoff and SENDING in the meter before dispatch.
A fresh SENDING attempt blocks another request. A call already dispatched holds account,
case and connection locks through outcome persistence, even beyond the in-flight window.
After a crash, startup does not send. Only an explicit Buyer request can expire the old
meter entry to DELIVERY_UNKNOWN and claim another attempt. Dispatch verifies its own
SENDING entry; completion updates that entry conditionally under the case lock, preventing
a superseded attempt from dispatching or overwriting a newer result. The restart fixture
interrupts after committed claim and reopens the same database; it is not an OS-kill probe.

Buyer send-again retains exact stored text, counts toward caps and records
DEALER_MESSAGE_SEND_AGAIN. It can duplicate a previously delivered UNKNOWN message.
No exactly-once external-delivery guarantee is made. Own-email-app decisions remain
Buyer-controlled, and a late mailbox result cannot change a handoff moved to that path.

## Required deployment evidence before enabling live connecting

These obligations remain unrecorded by this ticket. Keep connecting disabled until the
operator records dated results, application IDs, deployment identity and reviewer approval:

1. Google OAuth consent-screen verification for gmail.send and Microsoft publisher verification.
2. Exact registered HTTPS redirect URI on both applications and successful deployed PKCE callbacks.
3. Least-privilege consent for send plus required identity/offline scopes; no mailbox read grants.
4. Client secrets and a random dedicated mailbox encryption key in the secret manager, with
   access controls, rotation/retirement, backup protection and synchronized deployment clocks.
5. Controlled real Gmail and Outlook sends confirming recipient, exact text and Sent folders;
   real withdrawal/invalid_grant, local disconnect and account-deletion checks, with redacted logs.
6. Provider timeout/quota monitoring and a restart/crash drill in the deployed database environment.

`DeploymentStageGuardTest` proves fake refusal, live missing/short-key refusal, and disabled
configuration across pilot/production declarations. A keyed live adapter can pass that guard;
the guard does not inspect verification status, consent, redirects or secret management.
