# 126 — Shop car insurance quotes

**What to build:** The Buyer journey for insurance. From a Buying Case, or on its own, the Buyer fills in
one application, agrees to the licensed partner's consent, and gets quotes from many insurers, the way
comparison sites work. Quotes come back side by side for the coverage the
Buyer chose, ordered by premium, with each quote's yearly difference from what the Buyer pays now. The
Buyer continues with one insurer on that insurer's site, or with none. Spec 2.3 section 5.10, INS-01 to
INS-04.

**Blocked by:** 124 (compensation-blind structure), 125 (adapter and fake). Live insurers need 123 and 127.

**Status:** complete against the ticket 125 fake, as of 2026-10-16. `com.dealanalyzer.insurance` adds the
application (V58: `insurance_applications`, `insurance_quote_requests`, `insurance_quotes`), case routes under
`/api/v1/cases/{caseId}/insurance` and journey routes under `/api/v1/journeys/{journeyId}/insurance`, each with
`/quote-requests` and `/own-quotes`. A case in a journey uses the journey's application, as trades do. Quotes go to
the partner only with the current consent version agreed; results use 125's `CoverageComparison` and
`YearlyDifference`, with Buyer-entered quotes compared on the same terms and expired quotes listed apart. Records are
exported and deleted with their case, journey or account. The client panel (`InsuranceQuotes.tsx`) sits below the case
steps and on a journey's page. Open for 123 and 127: otdog stores age, ZIP and state, not name, date of birth,
licence number or street address, so a partner that needs them needs a hand-off to its hosted form; the consent
text is a draft (`otdog-insurance-consent.draft-1`) until the partner's terms and counsel settle it.

## Scope

- Application: drivers (name, date of birth, licence state, years licensed, incidents), garaging address,
  the vehicle (from the case when there is one), usage and mileage, the coverage wanted with plain
  explanations of each part, and optionally the current insurer, coverage and premium. Identity details
  the partner needs go in the partner's hosted form when ticket 123 chooses that route.
- The partner's consent, shown in plain words before the application goes to the partner, and the
  "how otdog makes money" line from ticket 124 beside the quotes.
- The Buyer can add a quote from a non-participating insurer (for example, their current one), compared on
  the same terms.
- Results: same-coverage quotes ordered by premium; differing coverage labelled; declines and timeouts
  shown plainly; expiry shown.
- Retention, export and deletion: applications and quotes belong to the account or case and are exported
  and deleted with it; `docs/case-deletion-policy.md` and `docs/case-portability-policy.md` updated.

## Done when

- [x] Backend acceptance tests cover consent, sending only to the partner, ordering,
      coverage labelling, the yearly difference, Buyer-entered quotes, export and deletion.
- [x] No application field appears in logs (test).
- [x] Client tests, the axe sweep and the 360 px gate pass for each step.
