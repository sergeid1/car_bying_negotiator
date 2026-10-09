# 95 — Sign in with a passkey

**What to build:** Let a signed-in Buyer add one or more passkeys (WebAuthn platform or roaming
authenticators) to their account and later sign in with a passkey instead of an email link. The email link
stays the default and remains the recovery path. Passkeys fit the Buyer-only stance: nothing reusable can
leak from another site and nothing can be phished to a look-alike domain. Decided by the operator on
2026-10-08 alongside optional passwords (ticket 96).

**Blocked by:** 94 (header and account panel). Migration V48 is reserved for this ticket (V48 was reserved
for 92 and left unused).

**Status:** open — agent-ready after 94.

- [ ] Registration and authentication use a maintained server-side WebAuthn library (for example
      `webauthn4j` or Yubico `java-webauthn-server`), not hand-rolled CBOR/attestation parsing. The relying
      party ID and allowed origins come from configuration; pilot/production refuses localhost origins.
- [ ] Challenges are single-use, bound to the session or sign-in attempt, and expire within minutes;
      replayed, expired or cross-account assertions are refused. Signature counters and backup flags are
      stored and a counter regression is refused and recorded as an account-security event.
- [ ] Only a signed-in account can add a passkey; adding one sends an `ACCOUNT_SECURITY` email.
      The Buyer can list passkeys (nickname, created, last used) and remove any of them; removing the last
      sign-in method other than email is allowed because email always remains.
- [ ] Passkey sign-in creates the same `account_session` cookie as the email link, with the same expiry,
      sign-out and "sign out everywhere" revocation, and does not reveal whether an email has an account.
- [ ] Credential public keys and IDs are stored per account (V48), included in the account export as
      metadata only, and deleted with the account. No biometric or authenticator secret ever reaches the server.
- [ ] Acceptance tests use a software authenticator for register, sign in, replay, wrong origin, removed
      credential and deleted account; Playwright uses a virtual authenticator for the browser path.
- [ ] The Content Security Policy, threat model and `docs/buyer-account-policy.md` are updated.
