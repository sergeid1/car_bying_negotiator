# 96 — Add an optional account password

**What to build:** Let a signed-in Buyer set, change or remove an optional password and then sign in with
email and password. The email link stays the default, creates accounts and resets passwords; there is no
password-only sign-up. Accounts hold dealer paperwork and may hold a connected mailbox's send permission,
so password sign-in needs the usual defenses against reuse and guessing. Decided by the operator on
2026-10-08 alongside passkeys (ticket 95).

**Blocked by:** 94. Migration V49 is reserved for this ticket. Coordinate with 95 on the account panel;
run them in sequence or merge carefully, since both change `BuyerAccount.tsx` and the sign-in endpoints.

**Status:** complete — migration V49, as of 2026-10-09. `PasswordService` hashes with Spring Security's
`BCryptPasswordEncoder` at cost 12 (12 code points minimum, 72 UTF-8 bytes maximum, no composition rules);
`spring-security-crypto` is the only new library and no filter chain is enabled. `BreachedPasswords` queries the
Pwned Passwords range API with padding and fails open with a fixed warning. `PasswordAttempts` backs off per
account (after 5) and per client address (after 20), from one second to fifteen minutes. Sessions record their
sign-in method; password sessions cannot start or complete a mailbox connection or register a passkey, starting
a mailbox connection needs an email-link or passkey sign-in within 10 minutes, and disconnecting stays open to any
session. Open: the attempt counters are per process and key on the socket address
(`server.forward-headers-strategy=none`), so behind Cloudflare and a load balancer every Buyer shares one client
bucket until ticket 99 trusts the forwarded client address from those proxies only.

- [x] Passwords are hashed with Argon2id (or bcrypt with a documented cost) through Spring Security's
      password encoder, never logged, exported or returned. Minimum 12 characters, no composition rules,
      maximum length bounded.
- [x] Setting a password checks it against known breached passwords using the k-anonymity range API
      (only a 5-character hash prefix leaves the server) or a bundled list; the provider used is added to
      `docs/provider-inventory.md`. A check outage fails open with a logged warning, not a lockout.
- [x] Sign-in attempts are limited per account and per client address with backoff; responses and timing
      do not reveal whether the email has an account or a password.
- [x] Setting, changing or removing a password requires a recent sign-in (re-authentication within a short
      window) and sends an `ACCOUNT_SECURITY` email. Changing or resetting it revokes other sessions.
- [x] "Forgot password" sends the existing email sign-in link; finishing it lets the Buyer set a new
      password. No separate reset token type unless the link flow cannot carry it.
- [x] Password sign-in creates the same `account_session` cookie with the same expiry and revocation.
- [x] Connecting or changing a mailbox (ticket 83) after a password-only sign-in requires a fresh email
      link or passkey; decide and document this step-up in `docs/buyer-account-policy.md`.
- [x] Acceptance tests cover set, sign in, wrong password, throttling, breached password, change, remove,
      reset by link, session revocation and account deletion; Playwright covers set and sign in at 360 px.
- [x] The threat model is updated with credential stuffing and the controls above.
