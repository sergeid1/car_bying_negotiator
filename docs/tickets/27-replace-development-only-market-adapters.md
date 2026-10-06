# 27 — Replace development-only market and listing adapters with a licensed source

**What to build:** Market and listing evidence comes from a licensed provider under executed commercial terms, with measured coverage and visible uncertainty, replacing the deterministic and fake adapters recorded in the provider inventory.

**Blocked by:** 04 — Establish Vehicle and listing evidence; 09 — Produce a transparent Market Assessment.

**Status:** blocked on executed market-data provider terms (relabelled on 2026-10-05; it was never agent-ready without them) — narrowed on 2026-10-02: ticket 38 delivered the production guard, the
adapter conformance suite, freshness/uncertainty/thin-comparable wording, and stated unavailability.
This ticket keeps the licensed-source integration, executed terms, measured coverage, and per-call
cost against the pilot budget, and still needs an executed provider contract before it can start.
Narrowed again on 2026-10-02: ticket 41 builds the per-call cost and latency budget report and the
jurisdiction coverage measurement from existing records; this ticket applies them to the licensed source.
On 2026-10-06 MarketCheck was chosen as the candidate source; ticket 86 evaluates it and settles whether its
terms permit the per-case evidence snapshots this application stores.

- [ ] Executed commercial terms, permitted use, attribution requirements, and retention limits are recorded in the provider inventory before any production enablement.
- [ ] The licensed source is integrated behind the existing replaceable adapter interface, with conformance tests that every adapter must pass.
- [ ] Coverage and comparable relevance are measured for the pilot jurisdiction and reported with denominators, not as a summary adjective.
- [x] The report displays data freshness and the uncertainty of the assessment, and states when comparables are too thin to support a conclusion.
- [x] Provider unavailability produces a stated unavailability in the report; synthetic or cached-stale values are never substituted silently.
- [ ] Per-call cost and latency are tracked against the pilot budget.
- [x] Deterministic and fake adapters cannot be selected under production configuration, and a test fails if they are.
