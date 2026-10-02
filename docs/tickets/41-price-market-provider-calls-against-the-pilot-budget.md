# 41 — Price market provider calls against the pilot budget and measure coverage

**What to build:** The parts of ticket 27 that need no licensed provider. An operator can see what
market and listing provider calls cost and how long they take, against a configured pilot budget, and
how well market evidence covers the supported jurisdiction, reported with denominators. Everything is
computed from records the service already keeps, so the licensed source plugs into the same
measurement when ticket 27 integrates it. Ticket 27 keeps the licensed-source integration, executed
terms, and the measured coverage of that source.

**Blocked by:** 09 — Produce a transparent Market Assessment; 38 — Make market and listing evidence
provider-ready without a licensed source.

**Status:** ready-for-agent

- [ ] Per-call cost and latency for `MARKET_RETRIEVAL` and `LISTING_RETRIEVAL` are reported per
      provider and status from `provider_usage` — call counts, calls with known cost, known cost, mean
      and p95 latency — with denominators, over a stated window. Calls without a known cost are counted
      and never treated as free.
- [ ] A pilot budget for market and listing evidence (per-case cost ceiling and p95 latency ceiling)
      is configuration with no default. Startup fails closed under the `pilot`/`production` profiles or
      `evidence.deployment=production` when it is absent; development runs report the budget as
      unset rather than inventing a figure.
- [ ] The report compares observed cost per case and p95 latency with the budget and states over,
      within, or not measurable (unknown cost, no calls); no automatic release decision is taken.
- [ ] Market coverage and comparable relevance are measured for each supported jurisdiction
      (`jurisdiction_policies`), from stored `market_assessments` and `MARKET_COVERAGE` events: cases
      attempted, assessments available, unavailable, and too thin to conclude, and comparable counts —
      reported as counts with denominators, never as a summary adjective.
- [ ] The measurements are exposed only to a `SUPPORT` operator credential, carry no case, Buyer,
      Dealer, VIN, or document identifiers, and follow the privacy-minimized metrics policy.
- [ ] Tests cover unknown-cost calls, the missing-budget startup guard, empty windows, and
      jurisdiction denominators; the development adapters exercise the measurement end to end.
- [ ] `docs/provider-inventory.md` and `docs/pilot-metrics-policy.md` describe the measurement and
      state that its figures describe development adapters until ticket 27 integrates a licensed source.
