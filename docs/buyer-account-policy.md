# Buyer Account

A Buyer Account is optional. Creating, analyzing, exporting and deleting a Buying Case
works without one, by case link, exactly as before (spec §8.1 CASE-01, §8.8 ACCT-01).

## Sign-in

There are no passwords. `POST /api/v1/account/sign-in-requests` with `{"email"}` and an
`Idempotency-Key` creates the account on first use and emails a single-use link
(`#sign-in=<token>`, 256 bits of entropy) through the shared `RecoveryEmail` adapter with
purpose `ACCOUNT_SIGN_IN`. The acknowledgement is identical for new, existing and
pending-deletion addresses. Links expire after `account.sign-in.link-lifetime` (default
15 minutes) and are stored only as a scoped HMAC. `POST /api/v1/account/sign-in/verify`
consumes a link once and sets the session cookie; its JSON returns only the expiry, never the token. A reused link gets 410
`SIGN_IN_LINK_USED`, an expired one 410 `SIGN_IN_LINK_EXPIRED`, an unknown one 404
`SIGN_IN_LINK_UNKNOWN`, each with a fixed Buyer-facing `detail`. At most
`account.sign-in.max-active-links` (default 3) unused, unexpired links exist per address;
further requests send nothing and return the same acknowledgement, so the public endpoint
cannot flood a mailbox. Using a link or letting it expire frees a slot. Concurrent first
requests for a new address create one account in its own transaction; the losing request
reads the winner's row and gets the same acknowledgement. Unverified sign-ups whose
link expired are removed by the account worker.

Sign-in and both case recovery email routes also share a per-network-peer quota (default five
requests per 15 minutes), keyed only by the connection peer. Forwarding headers cannot change
it. Peer exhaustion returns 429 with `Retry-After`. A shared per-process hourly ceiling
(default 100 delivery attempts) suppresses further mail and link creation while preserving
the exact acknowledgement. It logs one address-free warning per exhausted window. Configure
these through `security.request-email.peer-requests`, `peer-window`, `max-peers` (default
10,000), and `hourly-limit`; all must be positive. Delivery failures consume hourly capacity;
replays, per-address link caps and ineligible recovery requests do not. Accounts created while
mail is suppressed remain unverified and are purged by the same abandoned-sign-up lifecycle.
These limits reset on restart and are not shared across replicas; ingress still needs shared
quotas. See the [operator procedure](hardening-runbook.md).

## Sessions

Account, notification, journey, quote-comparison and account-authenticated test-drive
endpoints read the `account_session` cookie. The old `Account-Session-Token` header is
ignored. The cookie has `HttpOnly; Secure; SameSite=Strict; Path=/api/v1` and a Max-Age
matching the session expiry. Sessions are stored server-side as a scoped HMAC and expire
after `account.session.lifetime` (default 14 days). The browser sends the cookie on
same-origin requests, so sign-in survives a reload; the client discovers sign-in with
`GET /api/v1/account` and stores no account data in localStorage or sessionStorage.
HttpOnly prevents page scripts from reading the credential. Secure confines it to HTTPS,
and SameSite=Strict limits its use across sites.

POST, PUT and DELETE requests using this cookie also require `Account-Request: 1`.
The shared resolver refuses requests without that exact header with 403
`ACCOUNT_REQUEST_REQUIRED`; a cross-site HTML form cannot add it. GET requests need no
custom header. The header is a public request marker, readable and set by page scripts;
it contains no credential. Only the cookie credential is unreadable by page scripts.
Case-link requests and anonymous case creation are unchanged.

`POST /api/v1/account/sign-out` revokes the current session server-side and clears the
cookie; `?everywhere=true` revokes every session. Expired or revoked sessions get 401
`SESSION_ENDED` and clear the cookie, except while its deletion receipt is pending (below).
`account.session.cookie-secure=false` permits local HTTP development only; secure is the
default, and pilot, prod and production profiles or deployment settings refuse the switch.

## Contact details

`PUT /api/v1/account/contact` accepts names and phone only; it cannot change the email address
or send verification mail. Notification preferences likewise cannot supply a recipient.

Email, optional first and last name, and optional phone are stored once, in
`buyer_accounts.contact_ciphertext`, encrypted with AES-GCM under a key derived from
`DOCUMENT_STORAGE_ENCRYPTION_KEY` for this purpose and bound to the account ID. Sign-in finds
an account by a keyed HMAC of the normalized address. Contact details are returned only to the
signed-in Buyer (`GET /api/v1/account`, the account export). They are not logged, not recorded
in pilot events or analytics, not shown in operator tooling, and not released to any dealer.
`Contact.toString()` is redacted. Release to a dealer (ACCT-03) is not built yet and will
require an explicit, audited, per-dealer Buyer action.

## Cases

`POST /api/v1/account/cases` with `{"caseId"}` and the case's `Case-Access-Token` attaches a
case opened by its link. The case link keeps working. A case belongs to at most one account
(409 `CASE_ATTACHED_ELSEWHERE`). Case expiry or deletion removes the attachment first
(`CaseGraphDeletionPlan`).

## Export and deletion

`POST /api/v1/account/export` returns a ZIP built on request and never stored: `account.json`
plus, for each attached case, the same case archive a case export produces. A case with saved
reports that still require purchase is listed as `PURCHASE_REQUIRED`, matching the case export
gate; an expired or pending case is `UNAVAILABLE`.

`POST /api/v1/account/deletion` returns a 202 receipt. In one transaction it revokes every
session and unused sign-in link, marks the account `DELETION_PENDING`, and queues each attached
case on the case deletion lifecycle (`docs/case-deletion-policy.md`), revoking those case links
at once. `AccountDeletionWorker` deletes and count-verifies the account graph, then marks the
job `DELETED` with verification `ACCOUNT_GRAPH_AND_CASES_ABSENT_V1` once every attached case is
verified `DELETED`. The deletion request leaves the cookie in place for
`GET /api/v1/account/deletion`, which reads the receipt through the existing receipt hash
even though the session is revoked. While that receipt is pending, other account endpoints
return 401 without clearing the cookie, preserving the Buyer's Check deletion progress flow.
A receipt response in terminal state `DELETED` clears the cookie with Max-Age=0 and the same
attributes. The receipt holds no contact details or credentials. Backup expiry is reported
as `UNKNOWN`, as for cases.

## Notifications

`BuyerNotifications.notify(accountId, type, dedupeKey)` emails the account's verified address
through the `RecoveryEmail` adapter, after the producer's own transaction commits. Types are
`DEALER_REPLY`, `DRAFT_NEEDS_APPROVAL`, `MANDATE_EXPIRING`, `APPOINTMENT` and the mandatory
`ACCOUNT_SECURITY`. Each email is a fixed prompt plus the journey URL. It holds no amounts,
dealer names, VINs, document content, case credential or sign-in token, so reading the journey
still requires sign-in.

`GET`/`PUT /api/v1/account/notifications` (session cookie) read and set each optional
type. Every optional type is on until turned off. `ACCOUNT_SECURITY` cannot be turned off.

Each `(account, type, dedupeKey)` is attempted at most once. The key is stored only as a keyed
hash. Optional and security email each have a rolling-hour quota
(`account.notifications.hourly-limit`, default 10). Every attempt records its delivery state
(`DELIVERY_UNKNOWN`, `SENT`, `FAILED`, `OPTED_OUT` or `RATE_LIMITED`); no address, body or link is
stored or logged. `SENT` means the adapter accepted the message. Failed and uncertain attempts
are not retried.

`MandateExpiryNotifier` sends `MANDATE_EXPIRING` once per mandate version
(`mandate-expiring:<mandate id>`), when an active mandate on an account-attached case comes
within `account.notifications.mandate-expiring-notice` (default `PT24H`) of expiry. It checks
every `account.notifications.mandate-expiring-sweep` (default `PT5M`). A mandate whose whole
lifetime fits inside the notice window gets no warning. The other producers arrive with tickets
60, 61 and 65. Account export includes preferences and delivery states, and account deletion
removes both notification tables.

## Buying Journeys

A signed-in Buyer starts a Buying Journey under `/api/v1/journeys`, authorized only by
the session cookie; a case token never opens a journey. A journey starts in `SHOPPING`
(the other spec §6 journey states arrive with later tickets) and is created with Shopping Brief
version 1. `POST /api/v1/journeys` needs an `Idempotency-Key`; a replay returns the same journey.

A Shopping Brief version is an immutable row in `shopping_brief_versions`. An edit
(`POST /api/v1/journeys/{id}/briefs` with `basedOnVersion`) inserts the next version; a stale
`basedOnVersion` is refused with 409 `BRIEF_CHANGED`, and a retried save of the same edit returns
the version it already made. `GET /api/v1/journeys/{id}/briefs/{version}` reads any earlier version.

Make, model, trim and feature choices are identifiers from the versioned reference list
`backend/src/main/resources/shopping/vehicle-reference-v1.json`, served with the field bounds by
`GET /api/v1/journeys/brief-options`; each version records the list version it used. The client's
free-text lookup only filters that list and is never sent. Every field problem is returned at
once as `errors[{field, message}]` (400 `BRIEF_INVALID`) with authored text that never echoes
input. The ZIP is resolved with `PostalJurisdictionResolver` and the jurisdiction must have a
policy pack, as for a Buying Case; otherwise 422 `LOCATION_NOT_SERVED` explains why with a
`reason` (`UNRECOGNIZED_POSTAL_CODE`, `POSTAL_CODE_CROSSES_STATE_LINE` with the candidate
states, or `JURISDICTION_NOT_SUPPORTED`) and nothing is stored.

`DELETE /api/v1/journeys/{id}` removes the journey and every brief version in one transaction and
count-verifies their absence. A journey has no Buying Cases yet (ticket 56 links them). Account
export lists every journey with every brief version under `buyingJourneys`, and account deletion
removes both journey tables with the account graph.

Reference Vehicles (ticket 52) are VINs the Buyer adds to a brief, at most five per version.
`POST /api/v1/journeys/{id}/reference-vehicles` with `{basedOnVersion, vin}` checksum-validates the VIN
before any decoder call (400 `VIN_INVALID`, never echoing the VIN), decodes it, and inserts the next
brief version with the vehicle; a VIN the brief already holds returns the journey unchanged. An
unavailable decoder is 503 `VIN_DECODER_UNAVAILABLE` and saves nothing; an undecodable VIN is kept
with no attributes. `PUT .../reference-vehicles/{referenceId}/relevance` records which decoded
attributes matter, and `DELETE .../reference-vehicles/{referenceId}?basedOnVersion=` removes one; both
insert the next brief version. A brief edit carries the vehicles forward unchanged.
`GET .../briefs/{version}/reference-vehicles` reads an earlier version's. Reference VINs are personal
data: journey deletion and account deletion remove `reference_vehicles` before the brief versions,
and account export lists them per brief version under `referenceVehicles`.
