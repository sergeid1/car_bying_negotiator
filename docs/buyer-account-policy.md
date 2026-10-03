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
consumes a link once and returns a session token; a reused link gets 410
`SIGN_IN_LINK_USED`, an expired one 410 `SIGN_IN_LINK_EXPIRED`, an unknown one 404
`SIGN_IN_LINK_UNKNOWN`, each with a fixed Buyer-facing `detail`. At most
`account.sign-in.max-active-links` (default 3) unused, unexpired links exist per address;
further requests send nothing and return the same acknowledgement, so the public endpoint
cannot flood a mailbox. Using a link or letting it expire frees a slot. Concurrent first
requests for a new address create one account in its own transaction; the losing request
reads the winner's row and gets the same acknowledgement. Unverified sign-ups whose
link expired are removed by the account worker.

## Sessions

Every account endpoint takes `Account-Session-Token`. Sessions are stored as a scoped HMAC,
expire after `account.session.lifetime` (default 14 days), and survive a page reload because
the browser keeps the token in `localStorage` after sign-in only. `POST
/api/v1/account/sign-out` revokes the current session server-side; `?everywhere=true`
revokes every session. Ended sessions get 401 `SESSION_ENDED`.

## Contact details

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
verified `DELETED`. `GET /api/v1/account/deletion` with the old session token reads the receipt,
which holds no contact details or credentials. Backup expiry is reported as `UNKNOWN`, as for
cases.
