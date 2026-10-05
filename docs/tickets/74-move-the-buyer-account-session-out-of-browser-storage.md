# 74 — Move the Buyer Account session out of browser storage

**What to build:** The Buyer Account session is no longer readable by page scripts. Today the session token sits in `localStorage` and travels in the `Account-Session-Token` header. Instead, the server sets it as an `HttpOnly`, `Secure`, `SameSite=Strict` cookie scoped to `/api/v1`. State-changing account requests must also carry a required custom header, which blocks cross-site request forgery. The site is served from one origin, so no CORS change is needed. This is the open item that ticket 50 left. Spec §8.8 ACCT-01.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account.

**Status:** complete — the Buyer Account session is an `account_session` cookie (`HttpOnly`, `Secure`,
`SameSite=Strict`, `Path=/api/v1`) resolved once by `AccountSessionResolver`, with `Account-Request: 1` required on
account-authenticated POST, PUT and DELETE (no migration), as of 2026-10-05. A pending account deletion keeps its
revoked cookie as the progress receipt until the deletion completes. Open: `Account-Request` is a public marker, so
`SameSite=Strict` is the main cross-site defence; deletion progress is lost if the browser drops the cookie; browser
tests stub the API rather than run the backend behind the Vite proxy.

- [x] Verifying a sign-in link sets the session cookie (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v1`, expiring with the session). The response body no longer contains the session token.
- [x] Every endpoint that accepted `Account-Session-Token` (account, notifications, journeys, quote comparison, test drive) authenticates from the cookie instead. The header is no longer accepted.
- [x] A state-changing request (POST, PUT, DELETE) authenticated by the session cookie is refused unless it carries the agreed custom request header. A cross-site form post with only the cookie is refused, and a test proves it.
- [x] Signing out and completing account deletion revoke the session server-side and clear the cookie. An expired or revoked cookie is treated as signed out.
- [x] The client stores nothing account-related in `localStorage` or `sessionStorage`. It learns whether the Buyer is signed in from the account endpoint, and a session still survives a page reload.
- [x] Case-link access (`Case-Access-Token`) and creating a case without an account are unchanged and still covered by tests. The sign-in and account screens still pass the axe sweep and work at 360 px in both themes.
- [x] The Buyer Account policy describes the cookie, the request header, and why neither is readable by page scripts.
