# 113 — Sign in with Google or Apple

**What to build:** A Buyer can create a Buyer Account or sign in to an existing one with their Google or Apple
account, as one more sign-in method next to the email link, passkeys and password. Operator request on
2026-10-13, alongside the Log in / Sign up card in ticket 114. Facebook is out of scope.

**Blocked by:** none. It runs in parallel with 114; this ticket owns the API below, and 114 builds against it
with stubbed responses.

**Status:** complete as of 2026-10-13, against stubbed providers. `SocialSignInService` and
`SocialSignInController` implement the flow above; migration V55 adds the attempts and identities tables. The
complete response also carries `provider` (agreed with 114). Start and callbacks use only the general API limiter,
not the email peer quota, so retries cannot block an emailed link. Open for the operator: register the Google OAuth
client and the Apple Services ID and key (`docs/hardening-runbook.md`); confirm a real Apple sign-in before enabling
it, because Apple's PKCE handling was checked against stubs only.

## How it works

OpenID Connect authorization code flow with PKCE, a `nonce` and a single-use `state`, like the mailbox
connection (`mailbox/OAuthMailboxSender`, `MailboxConnections`). The authorization code is redeemed only by the
server; it never reaches the client's JavaScript.

1. **Start.** `POST /api/v1/account/social-sign-in` with `{"provider": "GOOGLE" | "APPLE"}` and the
   `Account-Request: 1` header. No session needed. The server stores a single-use attempt (keyed hash of the
   `state`, provider, encrypted PKCE verifier and nonce, expiry `account.social-sign-in.attempt-lifetime`,
   default `PT10M`). It also sets a binding cookie (HttpOnly, Secure as the session cookie, `SameSite=Strict`,
   path `/api/v1/account/social-sign-in`) whose keyed hash is stored with the attempt. Response:
   `{"authorizationUrl": "..."}`. An unconfigured provider answers 404 with code `SOCIAL_SIGN_IN_UNAVAILABLE`.
2. **Provider callback.** The redirect URI is the backend:
   `{account.social-sign-in.redirect-base}/api/v1/account/social-sign-in/{google|apple}/callback`.
   Google returns with `GET ?code&state`. Apple asks for `email` scope, so it must use `response_mode=form_post`
   and returns with a cross-site `POST` form. Both callbacks store the code (encrypted) against the attempt and
   answer `303` to `{case.recovery.public-url}#social-sign-in=<state>`. A cancelled or failed consent
   (`error=...`) or an unknown state answers `303` to `#social-sign-in-error=cancelled` or
   `#social-sign-in-error=failed`. The callbacks never set the session cookie: a Strict cookie is not sent on a
   cross-site navigation, so the binding check happens in step 3.
3. **Finish.** `POST /api/v1/account/social-sign-in/complete` with `{"state": "..."}` and `Account-Request: 1`.
   The binding cookie must match the attempt (login-CSRF protection), and the attempt must be unexpired, unused
   and hold a code; it is consumed either way. The server exchanges the code, checks the ID token (issuer,
   audience, expiry, nonce, `sub`), and requires `email_verified` to be true. Response:
   `{"expiresAt": "...", "provider": "GOOGLE" | "APPLE"}`, a response record of this endpoint only (other sign-in
   responses stay `AccountController.SignedIn`), with the same `account_session` cookie as other sign-ins; the
   binding cookie is cleared. Failures use one 400 detail (`SOCIAL_SIGN_IN_FAILED`): "Sign-in with {Google|Apple} could not be
   completed. Try again or continue with your email." Provider unreachable is 503 `SOCIAL_SIGN_IN_UNAVAILABLE`.
4. **Which account.** A new `buyer_account_identities` table links `(provider, keyed hash of sub)` to an account.
   - Known identity: sign in to its account.
   - Unknown identity: find the account by the verified email's keyed lookup, as `requestSignIn` does; create it
     through `AccountEnrollment` if there is none, and set `verifiedAt`. Then link the identity.
   - Linking an identity to an existing account records a `SOCIAL_SIGN_IN_LINKED` security event and sends the
     `ACCOUNT_SECURITY` notification, as password changes do.
   - An account awaiting deletion cannot sign in; the response is the same 400.
   - Apple private-relay addresses are ordinary addresses here.
5. **Discovery.** `GET /api/v1/account/social-sign-in/providers` returns `{"providers": ["GOOGLE", "APPLE"]}`,
   listing only configured providers, so the client hides buttons that cannot work.

## Sessions and other methods

- Sessions record `sign_in_method` `GOOGLE` or `APPLE`. They count as strong sign-ins like email link and
  passkey: they may start a mailbox connection and register passkeys (only `PASSWORD` sessions are limited).
- Account deletion deletes the account's identities; the account export lists linked providers and link dates,
  never the `sub`.
- Unauthenticated start and callback routes stay under the general per-peer API limit in `ApiRateLimitFilter`, outside
  the email routes' stricter quota: they send no email, Google or Apple retries must not block an emailed sign-in link,
  and a 429 on a top-level callback navigation would show raw JSON.

## Configuration

- `account.social-sign-in.redirect-base` (default `${case.recovery.public-url}`).
- Google: `account.social-sign-in.google.client-id` / `.client-secret` (env `SOCIAL_SIGN_IN_GOOGLE_CLIENT_ID`,
  `SOCIAL_SIGN_IN_GOOGLE_CLIENT_SECRET`). Scopes `openid email`. These may reuse the mailbox Google project, but
  they are separate settings.
- Apple: `.apple.client-id` (the Services ID), `.apple.team-id`, `.apple.key-id`, `.apple.private-key` (PEM of
  the Sign in with Apple key). The client secret is a short-lived ES256 JWT the server signs per token request
  (JDK `SHA256withECDSA`, DER converted to JOSE form). Scopes `openid email`; `response_mode=form_post`.
- A provider with blank settings is simply not offered. Production-like stages refuse partial settings for a
  provider and require HTTPS redirect bases.
- Tests stub the provider authorization/token endpoints (local HTTP server or injected `HttpClient`); no fake
  provider ships to production-like stages.

## Docs

- `docs/buyer-account-policy.md`: a "Google and Apple sign-in" section covering the flow, linking by verified
  email, data kept (provider, keyed `sub` hash, link date; no provider tokens are stored after sign-in) and
  configuration.
- `docs/provider-inventory.md`: Google Identity and Sign in with Apple.

## Done when

- [ ] Start, both callbacks and complete work end to end against stubbed providers for a new Buyer, a Buyer whose
      email already has an account, and a returning linked identity.
- [ ] A missing or mismatched binding cookie, a reused, expired or unknown state, a wrong nonce, audience or
      issuer, and `email_verified: false` all fail with the one 400 detail and sign nobody in.
- [ ] Linking to an existing account records the security event and notification.
- [ ] Deletion removes identities; export lists providers without `sub`.
- [ ] Provider discovery lists only configured providers.
- [ ] Migration `V55__create_social_sign_in.sql` (attempts + identities) applies on PostgreSQL and H2.
- [ ] `mvn -Dtest=...` targeted suites pass; the coordinator runs `mvn clean verify` on the integration.
