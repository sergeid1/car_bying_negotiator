# 77 — Limit email sent from unauthenticated requests

**What to build:** No one can use the service to send mail to strangers. `POST /api/v1/account/sign-in-requests` is unauthenticated, creates an account for any address and emails it a sign-in link. A cap of three active links per address keeps one mailbox from being flooded, but nothing limits how many different addresses one client can reach apart from the general 600 requests per minute per network peer. One client could have the service email hundreds of strangers a minute and damage the sending domain's reputation, and the threat model leaves the stricter quota to an ingress that does not exist yet. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** complete — request-email limits in `ApiRateLimitFilter` and `com.dealanalyzer.recovery.RequestEmailLimits`
(no migration), as of 2026-10-05. Sign-in requests, case recovery enrollment and public recovery requests share a
per-peer quota (default five per 15 minutes, `security.request-email.*`) on the API limiter's peer identity, with 429
and `Retry-After`; their combined delivery attempts have a positive hourly ceiling (default 100) above which the
acknowledgement is unchanged, no link or mail is created, and one address-free `REQUEST_EMAIL_HOURLY_LIMIT_REACHED`
warning is logged per window. Checked: `PUT /account/contact` (names and phone only, no email), notification
preferences and `BuyerNotifications` (verified account address only, existing hourly quotas), and dealer message
hand-off and relay (case-authorized, Buyer-approved, existing per-case and per-dealer daily caps); none needed the
limit. Open: limits are per process and reset on restart, so shared ingress quotas across replicas remain a deployment
obligation; the operator must route the warning to a monitored destination.

- [x] Sign-in requests have a per-peer limit far below the general API limit (configurable, with a default suited to a person signing in from one device), using the same peer identity as `ApiRateLimitFilter` and never a client-supplied header.
- [x] The service has a global ceiling on sign-in emails per hour, configurable and required to be positive. Above it, requests get the same acknowledgement but no mail is sent, and the operator is alerted once per window without any address in the alert.
- [x] The Buyer-facing acknowledgement does not change when a limit applies, so it still reveals neither whether an address has an account nor that a limit was hit; a rate-limited peer gets a 429 with `Retry-After`.
- [x] Every other path that emails an address supplied in the request (recovery enrollment, case recovery requests, notification address changes) is checked for the same pattern and limited the same way where it applies. The status line lists what was checked.
- [x] Accounts created by a sign-in request that is never verified are still purged as today, and the limits are covered by acceptance tests, including concurrent requests from one peer.
- [x] The threat model's abuse row records the new limits and what still belongs to the ingress.
