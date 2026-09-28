# 27 — Replace development-only market and listing adapters with a licensed source

**What to build:** Market and listing evidence comes from a licensed provider under executed commercial terms, with measured coverage and visible uncertainty, replacing the deterministic and fake adapters recorded in the provider inventory.

**Blocked by:** 04 — Establish Vehicle and listing evidence; 09 — Produce a transparent Market Assessment.

**Status:** ready-for-agent

- [ ] Executed commercial terms, permitted use, attribution requirements, and retention limits are recorded in the provider inventory before any production enablement.
- [ ] The licensed source is integrated behind the existing replaceable adapter interface, with conformance tests that every adapter must pass.
- [ ] Coverage and comparable relevance are measured for the pilot jurisdiction and reported with denominators, not as a summary adjective.
- [ ] The report displays data freshness and the uncertainty of the assessment, and states when comparables are too thin to support a conclusion.
- [ ] Provider unavailability produces a stated unavailability in the report; synthetic or cached-stale values are never substituted silently.
- [ ] Per-call cost and latency are tracked against the pilot budget.
- [ ] Deterministic and fake adapters cannot be selected under production configuration, and a test fails if they are.
