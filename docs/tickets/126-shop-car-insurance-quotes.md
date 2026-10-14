# 126 — Shop car insurance quotes

**What to build:** The Buyer journey for insurance. From a Buying Case, or on its own, the Buyer fills in
one application, sees the participating insurers and what each pays otdog, selects any of them, reviews
exactly what will be sent, and approves sending it. Quotes come back side by side for the coverage the
Buyer chose, ordered by premium, with each quote's yearly difference from what the Buyer pays now. The
Buyer continues with one insurer on that insurer's site, or with none. Spec 2.3 section 5.10, INS-01 to
INS-04.

**Blocked by:** 124 (compensation-blind structure), 125 (adapter and fake). Live insurers need 123 and 127.

**Status:** open — filed on 2026-10-14. The coordinator reserves a migration number for the application
and quote tables.

## Scope

- Application: drivers (name, date of birth, licence state, years licensed, incidents), garaging address,
  the vehicle (from the case when there is one), usage and mileage, the coverage wanted with plain
  explanations of each part, and optionally the current insurer, coverage and premium. Identity details
  the partner needs go in the partner's hosted form when ticket 123 chooses that route.
- Provider selection with the compensation shown for each, and a per-provider consent to send. Nothing is
  sent to a provider the Buyer did not select.
- A review step that shows exactly what each provider will receive, then an explicit "Send to N insurers".
- The Buyer can add a quote from a non-participating insurer (for example, their current one), compared on
  the same terms.
- Results: same-coverage quotes ordered by premium; differing coverage labelled; declines and timeouts
  shown plainly; expiry shown.
- Retention, export and deletion: applications and quotes belong to the account or case and are exported
  and deleted with it; `docs/case-deletion-policy.md` and `docs/case-portability-policy.md` updated.

## Done when

- [ ] Backend acceptance tests cover the selection consent, sending only to selected providers, ordering,
      coverage labelling, the yearly difference, Buyer-entered quotes, export and deletion.
- [ ] No application field appears in logs (test).
- [ ] Client tests, the axe sweep and the 360 px gate pass for each step.
