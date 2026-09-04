# 19 — Capture privacy-minimized feedback and pilot metrics

**What to build:** Buyers can provide optional usefulness and outcome feedback while operators measure funnel, quality, cost, reliability, and safety without collecting unnecessary personal information.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 18 — Operate the service without broad document access.

**Status:** ready-for-agent

- [ ] A Buyer can rate whether a finding was accurate, understandable, and useful.
- [ ] A Buyer can separately and optionally report revised or final deal terms and the action taken.
- [ ] Analysis completion, corrections, conversion, errors, provider usage, deletion, and feedback events use redacted schemas.
- [ ] Metrics cover the declared completion, latency, extraction quality, review safety, market coverage, usefulness, cost, deletion, and product-market-fit measures.
- [ ] Affiliate or pricing-experiment eligibility is excluded from assessment, rating, finding, comparable ordering, and recommendation inputs.
- [ ] Secondary research use follows the separate consent state and does not retain cross-Buyer dealer intelligence by default.
- [ ] Pilot thresholds and experiment variants can be frozen before outcome data is reviewed.
- [ ] Feedback, outcome, experiment-assignment, and redacted event metadata use JPA repositories; aggregate metrics use projections or documented native queries without hydrating complete Buying Case graphs.
