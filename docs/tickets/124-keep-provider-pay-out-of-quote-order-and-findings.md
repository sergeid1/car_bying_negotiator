# 124 — Keep provider pay out of quote order and findings

**What to build:** The structure that enforces credo rule 7 (compensation-blind) before any quote reaches a
Buyer. Provider compensation lives in its own module, which quote ordering, findings, the report and the
Desk Check cannot depend on, and one general "how otdog makes money" line is linked wherever quotes
appear, as comparison sites do. What each provider pays is not shown (operator decision, 2026-10-14).
Spec 2.3 sections 1.3 and 8.12 (PAY-01 to PAY-03).

**Blocked by:** none for the structure and tests; the line's final wording needs counsel (ticket 123).

**Status:** open — the "How otdog makes money" page (`?page=how-otdog-makes-money`), its footer link, and the
footer and partner-kit wording ("No dealer pays otdog for anything. No company pays for placement") are in, as
of 2026-10-14. Remaining: the compensation package, the architecture and ranking-integrity tests, the dealer
guard, the claim checks for "best rate", "cheapest" and "guaranteed", and the Terms and Privacy updates.

## Scope

- A `compensation` package holding provider fees, versioned and dated. Quote comparison code receives
  provider quotes without any compensation field. An architecture test (ArchUnit or equivalent) fails if
  quote ordering, findings, report or desk code imports the compensation package.
- Ranking-integrity tests in the style of `CandidateRankingIntegrityTest`: random quote sets with
  randomly changed compensation, participation flags and provider names give identical order, inclusion
  and wording; a Buyer-entered quote from a non-participating provider is ordered on the same terms
  (PAY-02).
- A guard that refuses to register a dealer, dealer group, or dealer-arranged financing source as a paying
  provider (PAY-03).
- A short "How otdog makes money" page, linked from the footer and from one line beside every quote list:
  otdog is free because companies shown may pay otdog, and that never changes the order or what otdog
  says. It lists no per-provider amounts.
  Copy passes `PilotCopyClaimsTest`, which gains checks for "best rate", "cheapest" and "guaranteed".
- Terms of Service and the Privacy Policy updated for the new revenue model and data flow (drafts for
  counsel's review in ticket 123).

## Done when

- [ ] The architecture test and ranking-integrity tests pass, and fail when a deliberate dependency or
      compensation-based ordering is introduced.
- [ ] The page is in the axe sweep and passes the 360 px gate.
