# 88 — Open and save Buying Cases from the Buyer Account

**What to build:** A signed-in Buyer can open any Buying Case attached to their account and continue it, without
the private case link, and a case they start while signed in is saved to their account. Accounts stay optional: a
Buyer who never signs in creates, finishes, exports and deletes a case by its link exactly as today. Ticket 50
built the account, but its case list is plain text ("Case 3f9a1c2e: Active") and attaching is a manual button, so
the account cannot yet do the one thing a Buyer expects from it: sign in and pick up where they left off. Filed on
2026-10-06.

**Blocked by:** none (50 and 74 complete).

**Status:** ready-for-agent — filed on 2026-10-06. Touches the hot files `frontend/src/App.tsx` and
`frontend/src/Accessibility.test.tsx`; do not run it in parallel with another client-journey ticket.

- [ ] Each case in the account's list opens the case workspace with one action. The list names a case by something
      the Buyer recognizes (vehicle and dealer when known, and the date started), not only an ID prefix, and shows
      expired or deleted cases as unavailable instead of opening them.
- [ ] Opening a case from the account is authorized by the account session and the case's attachment to that
      account, and the case's existing link keeps working. Opening never reveals or reissues another account's
      case, and a revoked, expired or deletion-pending case cannot be opened this way.
- [ ] A case created while the Buyer is signed in is attached to the account at creation, and the intake says so
      before the case is created. A case created while signed out is not attached, and the manual "Attach this
      Buying Case" action still works for it.
- [ ] Case isolation still holds: `CaseIsolationInventoryAcceptanceTest` classifies any new endpoint, and tests
      prove that a session for a different account, a signed-out request, or a case attached elsewhere is refused.
- [ ] Signing out, session expiry and account deletion end access through the account, and the case link continues
      to work for a case that is not being deleted.
- [ ] The start screen still says no account is required, and offers sign-in as an option to keep cases in one
      place.
- [ ] The account case list works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
- [ ] `mvn clean verify`, `npm test`, `npm run build` and `npx playwright test` pass.
