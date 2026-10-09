# Buyer Account

A Buyer Account is optional. Creating, analyzing, exporting and deleting a Buying Case
works without one, by case link, exactly as before (spec §8.1 CASE-01, §8.8 ACCT-01).

## Sign-in

### Optional password (ticket 96)

Email links remain the default and the only account-creation path. A signed-in Buyer can read
`GET /api/v1/account/password` (only `{configured}`), set/change with `PUT` and `{password}`,
or remove with `DELETE`. Passwords use Spring Security's bcrypt encoder at cost 12 with a random
salt. This deliberately expensive verification needs no extra crypto or HTTP-client dependency;
see [Spring's password storage documentation](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
There are no composition rules: at least 12 Unicode code points, at most 72 UTF-8 bytes, checked
before encoding or matching to avoid bcrypt truncation. Hashes never appear in responses, export,
security events or logs. Removing the account row removes its hash.

Set/change/remove requires a session created within the last 10 minutes. Sign out and sign in
again when prompted. Each actual mutation records an account-security event and schedules the
existing mandatory `ACCOUNT_SECURITY` notification after commit, under its existing delivery
quota. Setting, changing and removing revoke every other session while retaining the current one.
Forgot password uses the existing email sign-in link: finish signing in, then set a new password.
There is no separate reset token or password-only sign-up.

`POST /api/v1/account/password/sign-in` accepts `{email,password}` and issues the same HttpOnly
`account_session` cookie and expiry as email/passkey sign-in. Unknown, inactive, password-free
and wrong-password accounts receive the same 401 detail. Each performs one cost-12 bcrypt check
(a dummy hash when no usable password exists). Backoff keys are keyed hashes of normalized email
and the socket client address; forwarded headers are ignored. Per account the first five attempts
are admitted; per address the first twenty are admitted. The last admitted attempt starts a
one-second backoff, doubling per later admitted attempt up to 15 minutes. Blocked attempts return
the same 429 for every account state and do not perform bcrypt. Counters expire after an hour,
are capped at 20,000 entries, and fail closed at capacity without evicting live counters. These
are per-process counters, cleared on restart, like the API backstop; ingress must enforce shared
account and client limits across replicas. Admitted successes also count against backoff.

Setting/changing checks Have I Been Pwned Pwned Passwords with only the five-character SHA-1
prefix and `Add-Padding: true`. SHA-1 is only the range lookup, never stored as a password hash.
`account.password.breach-check.enabled` defaults to `true`, `.url` to
`https://api.pwnedpasswords.com/range/`, and `.timeout` to `PT2S` (positive, at most five seconds).
Redirects are refused. Provider failure logs a fixed warning without credential or digest data
and permits the change. Disable explicitly for offline operation; tests use loopback stubs.

V49 adds `password_hash` to the account and `sign_in_method` to its sessions. Methods are
`EMAIL_LINK`, `PASSKEY` and `PASSWORD`; pre-migration sessions default to `EMAIL_LINK`.
Starting authorization to connect or replace a mailbox requires an email-link/passkey session
created within the last 10 minutes. OAuth completion does not recheck session age: consent started
inside the window can finish afterward, subject to the authorization state's own expiry. State is
bound to the account, not the exact session, so completion still refuses password-only sessions
with 403 `MAILBOX_STEP_UP`. Disconnecting remains available to any active session, including
password-only and older sessions, because withdrawing send permission is a protective action.
Existing reads and approved sends remain governed by their existing rules.
Password sessions also cannot register new passkeys: otherwise they could manufacture their own
mailbox step-up. Use an email link or an existing passkey to register one.

### Try it locally

Start the database and development-only mail catcher with one command:

```sh
docker compose --profile development up -d database mailpit
```

Use the usual local backend secrets and document setup described in README, then start the
backend with `mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=development`
and the frontend with `npm run dev` in `frontend`. The explicit development profile defaults
`SPRING_MAIL_HOST` to `127.0.0.1`, `SPRING_MAIL_PORT` to `1025`, and `CASE_RECOVERY_FROM` to
`deal-xray@localhost.test`, and permits the account cookie over local HTTP. These defaults
do not apply without that profile. Set `CASE_RECOVERY_PUBLIC_URL` if the frontend is not at
`http://localhost:5173/`. If changing `MAILPIT_SMTP_PORT`, also set `SPRING_MAIL_PORT`.

Click **Sign in** in the header, enter an email, then open the message at
`http://localhost:8025` and follow its link. Click **Finish signing in**; the header shows
your email and **Sign out**. Entering an email creates an account if needed. Analysis still
works without an account: a private case link opens one case, while sign-in opens saved cases.
Case recovery and account notifications use the same SMTP adapter and arrive in Mailpit too;
opt into case recovery or trigger an enabled notification to inspect those messages.

Mailpit exposes only loopback ports and is started only by the Compose development profile.
Pilot/production declarations (profiles or any shared deployment setting) refuse enabled
recovery mail with a missing, loopback, Docker-host or named catcher SMTP host. Do not combine
the development profile with a live deployment; configure a real mail host, sender, HTTPS
public URL and secure account cookie for those environments.

The Playwright account-flow fixture captures an email link inside the test runner and follows
it at desktop and 360 px widths. It uses stubbed account APIs, rather than a live SMTP server;
there is no mail-reading test hook or secret-retrieval endpoint in the application.

Email links are the default and recovery sign-in method. `POST /api/v1/account/sign-in-requests` with `{"email"}` and an
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

## Passkeys

A signed-in Buyer may add up to 20 discoverable passkeys, name each one, list creation and
last-used dates, and remove any of them. Email recovery remains available after removing the
last passkey. Platform and roaming authenticators are supported, with resident credentials and
user verification required. Yubico `webauthn-server-core` 2.9.0 verifies attestation, RP ID, exact
origin, challenge, presence, verification, ownership, signature and backup eligibility. No
biometric data or authenticator private key reaches the server.
Registrations request and accept only privacy-preserving `none` attestation. Legacy Android
SafetyNet attestation and its HTTP transport are excluded; passkeys require no remote verifier.

Set `account.passkeys.rp-id` (default `localhost`), comma-separated `account.passkeys.origins`
(default `http://localhost:5173`) and `account.passkeys.challenge-lifetime` (default `PT5M`,
positive and at most five minutes). Origins must be exact origins without paths and belong
to the RP ID. Pilot/production declarations through `DeploymentStage` require HTTPS and refuse
localhost/loopback RP IDs and origins. Configure the public client domain before deployment.

Routes under `/api/v1/account/passkeys`:

- `GET` lists metadata; `DELETE /{id}` removes only an owned credential.
- `POST /registration/options` and `POST /registration` require the existing session and
  `Account-Request: 1`. Completion accepts `{attemptId, nickname, credential}`, with the browser
  response serialized as a JSON string. Attempts bind the account and exact session hash.
  Adding a key sends `ACCOUNT_SECURITY` after commit under existing quotas and delivery tracking.
- `POST /sign-in/options` takes no email, returning a random attempt and discoverable options
  without account identifiers or credential lists. `POST /sign-in` accepts `{attemptId, credential}`
  and issues the same cookie, expiry and revocation behavior as email sign-in.

V48 stores credential IDs, public COSE keys, counters and backup flags per account. Persisted
attempts are consumed atomically once, including failed verification; invalid attempts receive
fixed `PASSKEY_REFUSED` copy. Counter regression, including a nonzero counter resetting to zero,
is refused after signature verification and records `PASSKEY_COUNTER_REGRESSION` in the account's
security events. Authenticators reporting zero throughout are supported. Account locks serialize
assertion updates, removal and deletion. Expired attempts are purged on issuance and by the worker.
Export includes metadata IDs, nicknames, dates and security event types/times, excluding credential
IDs, keys, challenges and session hashes. Account deletion removes and count-verifies all three
tables; pending deletion immediately prevents passkey sign-in.

The client policy allows `publickey-credentials-create` and `publickey-credentials-get` only for
`self`. CSP retains same-origin scripts and API calls: passkeys need no remote script, frame or
broader `connect-src`. Test-only software and CDP authenticators are never deployed bypasses.

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

`POST /api/v1/cases` saves the new case to the account at creation when the request carries the
session cookie and `Account-Request: 1`; the response's `savedToAccount` says whether it did. The
intake sends the header only when the Buyer is signed in, and says so before the case is created.
Without the header, or with an ended session, the case is created unattached exactly as before.

`GET /api/v1/account` lists each attached case with its state, the date it started and, when known,
the vehicle (year, make, model and trim, or the last six VIN characters) and the dealer (the
listing's dealer name, or the dealer ZIP). `POST /api/v1/account/cases/{caseId}/open` opens an
attached case for the current session. A case attached to another account, an unknown case, and a
revoked, expired, deleted or deletion-pending case all get the same 404 `CASE_UNAVAILABLE`.

Opening never returns the case link. It returns an account case pass, `acct.<caseId>.<expiry>.<mac>`,
which the client sends as `Case-Access-Token` and keeps only in memory. The MAC binds the pass to
the case, its current case-link hash and the session, so a pass is accepted only on a request
that also carries that session's cookie, while the session is active, the account is active and
the case is still attached to it. It expires after `account.case-pass.lifetime` (default eight
hours), or earlier with the session or the case. Signing out, session expiry and account deletion
therefore end access through the account at once, and the case link keeps working for a case that
is not being deleted. Nothing about a pass is stored server-side.
Malformed pass fields, including expiry values outside the supported timestamp range, receive
the same unavailable 404 response as an unknown case on case reads and writes.

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

## Mailbox lifecycle

Mailbox lifecycle (ticket 90): starting a new OAuth attempt supersedes every earlier attempt.
Disconnect removes pending authorization rows and increments the account's mailbox generation,
even when no connection exists. A callback exchanging outside the transaction must recheck that
generation and the active account before storing a grant. A superseded, disconnected or deleted
account's newly exchanged grant is withdrawn through its provider; withdrawal failure never
restores local tokens. Replacement withdraws the previous grant through the previous provider.

At send dispatch, the service locks the active account, case and exact connection in that order,
rechecks current sending permission, and holds those locks through the provider call. Disconnect
and account deletion that commit first prevent a prepared token from being dispatched. If dispatch
wins, disconnect/deletion waits for the provider call to return: that call cannot be recalled.
Provider withdrawal remains best effort, and email already delivered remains with its recipients.
`ConnectedMailboxAcceptanceTest` exercises these boundaries with latches and fake providers.

Ticket 92 validates live provider identity claims and HTTP contracts with loopback stubs.
The send meter fences expired claims and stale completion; outcome persistence remains under
the dispatch locks for slow sends. Startup never retries a persisted SENDING claim. After the
in-flight window, an explicit Buyer send-again request converts that attempt to UNKNOWN,
creates a new metered attempt and records its audit action; it may duplicate an earlier delivery.
See [mailbox provider boundaries](mailbox-provider-boundaries.md) for provider-specific rules,
the trusted token-endpoint/signature decision and the evidence required before live enablement.

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
