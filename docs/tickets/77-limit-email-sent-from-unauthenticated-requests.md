# 77 — Limit email sent from unauthenticated requests

**What to build:** No one can use the service to send mail to strangers. `POST /api/v1/account/sign-in-requests` is unauthenticated, creates an account for any address and emails it a sign-in link. A cap of three active links per address keeps one mailbox from being flooded, but nothing limits how many different addresses one client can reach apart from the general 600 requests per minute per network peer. One client could have the service email hundreds of strangers a minute and damage the sending domain's reputation, and the threat model leaves the stricter quota to an ingress that does not exist yet. Found in the 2026-10-05 codebase security review.

**Blocked by:** none.

**Status:** ready-for-agent — filed on 2026-10-05 from a codebase security review.

- [ ] Sign-in requests have a per-peer limit far below the general API limit (configurable, with a default suited to a person signing in from one device), using the same peer identity as `ApiRateLimitFilter` and never a client-supplied header.
- [ ] The service has a global ceiling on sign-in emails per hour, configurable and required to be positive. Above it, requests get the same acknowledgement but no mail is sent, and the operator is alerted once per window without any address in the alert.
- [ ] The Buyer-facing acknowledgement does not change when a limit applies, so it still reveals neither whether an address has an account nor that a limit was hit; a rate-limited peer gets a 429 with `Retry-After`.
- [ ] Every other path that emails an address supplied in the request (recovery enrollment, case recovery requests, notification address changes) is checked for the same pattern and limited the same way where it applies. The status line lists what was checked.
- [ ] Accounts created by a sign-in request that is never verified are still purged as today, and the limits are covered by acceptance tests, including concurrent requests from one peer.
- [ ] The threat model's abuse row records the new limits and what still belongs to the ingress.
