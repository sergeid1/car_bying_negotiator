# 95 — Sign in with a passkey

**What to build:** Let a signed-in Buyer add one or more passkeys (WebAuthn platform or roaming
authenticators) to their account and later sign in with a passkey instead of an email link. The email link
stays the default and remains the recovery path. Passkeys fit the Buyer-only stance: nothing reusable can
leak from another site and nothing can be phished to a look-alike domain. Decided by the operator on
2026-10-08 alongside optional passwords (ticket 96).

**Blocked by:** 94 (header and account panel). Migration V48 is reserved for this ticket (V48 was reserved
for 92 and left unused).

**Status:** complete — migration V48, as of 2026-10-09. `PasskeyService` uses Yubico `webauthn-server-core` 2.9.0
with `none` attestation, discoverable credentials and required user verification; `PasskeyConfiguration` reads
`account.passkeys.rp-id`, `.origins` and `.challenge-lifetime` and refuses localhost or non-HTTPS origins under any
production-like declaration. Passkey sign-in reuses `BuyerAccountService.issueSession` and `AccountSessionCookie`.
V48 adds `buyer_account_passkeys`, `buyer_account_passkey_challenges` and `buyer_account_security_events`. The
optional Apache `httpclient5` transport is excluded so Spring's HTTP clients keep their existing retry behavior.
Open: browser coverage is Chromium's virtual authenticator only; no physical authenticator or Safari/Firefox run.
The relying-party name shown in passkey prompts is the current product name and must change with any rebrand.

- [x] Registration and authentication use a maintained server-side WebAuthn library (for example
      `webauthn4j` or Yubico `java-webauthn-server`), not hand-rolled CBOR/attestation parsing. The relying
      party ID and allowed origins come from configuration; pilot/production refuses localhost origins.
- [x] Challenges are single-use, bound to the session or sign-in attempt, and expire within minutes;
      replayed, expired or cross-account assertions are refused. Signature counters and backup flags are
      stored and a counter regression is refused and recorded as an account-security event.
- [x] Only a signed-in account can add a passkey; adding one sends an `ACCOUNT_SECURITY` email.
      The Buyer can list passkeys (nickname, created, last used) and remove any of them; removing the last
      sign-in method other than email is allowed because email always remains.
- [x] Passkey sign-in creates the same `account_session` cookie as the email link, with the same expiry,
      sign-out and "sign out everywhere" revocation, and does not reveal whether an email has an account.
- [x] Credential public keys and IDs are stored per account (V48), included in the account export as
      metadata only, and deleted with the account. No biometric or authenticator secret ever reaches the server.
- [x] Acceptance tests use a software authenticator for register, sign in, replay, wrong origin, removed
      credential and deleted account; Playwright uses a virtual authenticator for the browser path.
- [x] The Content Security Policy, threat model and `docs/buyer-account-policy.md` are updated.
