# 19 — Capture privacy-minimized feedback and pilot metrics

**What to build:** Buyers can provide optional usefulness and outcome feedback while operators measure funnel, quality, cost, reliability, and safety without collecting unnecessary personal information.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 18 — Operate the service without broad document access.

**Status:** implemented

- [x] A Buyer can rate whether a finding was accurate, understandable, and useful.
- [x] A Buyer can separately and optionally report revised or final deal terms and the action taken.
- [x] Analysis completion, corrections, conversion, errors, provider usage, deletion, and feedback events use redacted schemas.
- [x] Metrics cover the declared completion, latency, extraction quality, review safety, market coverage, usefulness, cost, deletion, and product-market-fit measures.
- [x] Affiliate or pricing-experiment eligibility is excluded from assessment, rating, finding, comparable ordering, and recommendation inputs.
- [x] Secondary research use follows the separate consent state and does not retain cross-Buyer dealer intelligence by default.
- [x] Pilot thresholds and experiment variants can be frozen before outcome data is reviewed.
- [x] Feedback, outcome, experiment-assignment, and redacted event metadata use JPA repositories; aggregate metrics use projections or documented native queries without hydrating complete Buying Case graphs.

Implementation, API contracts, frozen protocol, metric definitions, evidence requirements,
consent and retention boundaries: [pilot metrics policy](../pilot-metrics-policy.md).

Validation: full Maven suite (102 tests), frontend suite (15 tests), production
frontend build and `git diff --check` passed. HTTP acceptance tests cover finding
scope and case access, optional independent outcomes, retry deduplication, frozen
protocol and assignments, commercial-input isolation, redacted aggregate SQL,
research consent changes, unavailable quality evidence, deletion cohort integrity,
restart persistence and Buyer ZIP export. Evaluation and paid-outcome measures
remain explicitly unavailable until operators supply verified aggregate evidence.
