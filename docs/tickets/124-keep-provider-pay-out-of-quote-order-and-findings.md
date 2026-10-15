# 124 — Keep provider pay out of quote order and findings

**What to build:** The structure that enforces credo rule 7 (compensation-blind) before any quote reaches a
Buyer. Provider compensation lives in its own module, which quote ordering, findings, the report and the
Desk Check cannot depend on, and one general "how otdog makes money" line is linked wherever quotes
appear, as comparison sites do. What each provider pays is not shown (operator decision, 2026-10-14).
Spec 2.3 sections 1.3 and 8.12 (PAY-01 to PAY-03).

**Blocked by:** none for the structure and tests; the line's final wording needs counsel (ticket 123).

**Status:** complete — as of 2026-10-15; the line's final wording and the Terms and Privacy drafts await counsel
(ticket 123). The "How otdog makes money" page, its footer link and the footer and partner-kit wording landed on
2026-10-14. `com.dealanalyzer.compensation.ProviderFees` holds versioned, dated fees (`CURRENT` is empty: no
signed fee schedule yet), and its `Fee` constructor is the PAY-03 guard: only insurers, direct lenders and
licensed comparison partners can be registered, never a dealer, dealer group, dealer-arranged financing, dealer
reserve or desk product. `CompensationArchitectureTest` scans every compiled production class outside the
package for a reference to it, and proves the scan rejects a compiled fixture that has one.
`QuoteRankingIntegrityTest` shows that random fees, participation and placement fields leave insurance and
pre-approval order, inclusion and wording unchanged, that renaming a provider moves it only among exact ties,
that the partner's order has no effect, and that a Buyer-entered quote is ordered on the same terms (PAY-02).
Equal-cost quotes are broken by provider name (`CoverageComparison`, `PreApprovalContract`), never by the
partner's order. `PilotCopyClaimsTest` now refuses "best rate" and "cheapest" alongside "guaranteed". Drafts for
counsel: `docs/terms-of-service-draft.md` and `docs/privacy-policy-draft.md`. The line beside each quote list
ships with the lists themselves in tickets 126 and 129.

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

- [x] The architecture test and ranking-integrity tests pass, and fail when a deliberate dependency or
      compensation-based ordering is introduced.
- [x] The page is in the axe sweep and passes the 360 px gate.
