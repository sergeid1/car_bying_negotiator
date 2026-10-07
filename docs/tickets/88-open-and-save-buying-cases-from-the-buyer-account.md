# 88 — Open and save Buying Cases from the Buyer Account

**What to build:** A signed-in Buyer can open any Buying Case attached to their account and continue it, without
the private case link, and a case they start while signed in is saved to their account. Accounts stay optional: a
Buyer who never signs in creates, finishes, exports and deletes a case by its link exactly as today. Ticket 50
built the account, but its case list is plain text ("Case 3f9a1c2e: Active") and attaching is a manual button, so
the account cannot yet do the one thing a Buyer expects from it: sign in and pick up where they left off. Filed on
2026-10-06.

**Blocked by:** none (50 and 74 complete).

**Status:** complete — no migration, as of 2026-10-07. `POST /api/v1/account/cases/{caseId}/open` returns an
account case pass (`acct.<caseId>.<expiry>.<hmac>`) instead of the case link. Its HMAC covers the case, the expiry,
the account session's protected hash and the case's current access hash, and `CaseAccessService` admits it only on a
request carrying that session's cookie while the session is active and the case is attached, so it ends at sign-out,
session expiry, account deletion, detachment, case expiry, deletion or a recovery that rotates the case link. Lifetime
is `account.case-pass.lifetime` (default 8 hours). `POST /api/v1/cases` with the session cookie and
`Account-Request: 1` attaches the new case and returns `savedToAccount`. The account list names each case by vehicle,
dealer and start date, and shows unavailable cases without an open action. `AccountCaseOpeningAcceptanceTest` and
`CaseIsolationInventoryAcceptanceTest` cover the refusals. Open: the dealer name appears only for cases with a listing
snapshot, otherwise the dealer ZIP; an idempotent retry of case creation reports `savedToAccount: false` because the
retry does not return the token; a case started from the desk-check intake appears in the account list on its next
refresh.

- [x] Each case in the account's list opens the case workspace with one action. The list names a case by something
      the Buyer recognizes (vehicle and dealer when known, and the date started), not only an ID prefix, and shows
      expired or deleted cases as unavailable instead of opening them.
- [x] Opening a case from the account is authorized by the account session and the case's attachment to that
      account, and the case's existing link keeps working. Opening never reveals or reissues another account's
      case, and a revoked, expired or deletion-pending case cannot be opened this way.
- [x] A case created while the Buyer is signed in is attached to the account at creation, and the intake says so
      before the case is created. A case created while signed out is not attached, and the manual "Attach this
      Buying Case" action still works for it.
- [x] Case isolation still holds: `CaseIsolationInventoryAcceptanceTest` classifies any new endpoint, and tests
      prove that a session for a different account, a signed-out request, or a case attached elsewhere is refused.
- [x] Signing out, session expiry and account deletion end access through the account, and the case link continues
      to work for a case that is not being deleted.
- [x] The start screen still says no account is required, and offers sign-in as an option to keep cases in one
      place.
- [x] The account case list works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
- [x] `mvn clean verify`, `npm test`, `npm run build` and `npx playwright test` pass.
