# 09 — Produce a transparent Market Assessment

**What to build:** The Buyer receives a versioned market range backed by a frozen Comparable set, visible selection decisions, and calibrated confidence rather than an unexplained point estimate.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 04 — Establish Vehicle and Listing evidence.

**Status:** Complete

- [x] Licensed or deterministic recorded provider responses are normalized without exposing vendor-specific types to the domain.
- [x] Duplicate, incompatible, stale, and implausible observations are excluded using versioned rules with visible reasons.
- [x] Included Comparables show material match factors and adjustments without inventing unsupported precision.
- [x] The Market Assessment records range, geography, vintage, sample size, method version, provider metadata, and confidence.
- [x] Sparse or divergent observations widen the range, lower confidence, or return `INSUFFICIENT_DATA` according to declared rules.
- [x] Provider failure preserves the Buying Case and leaves market analysis explicitly unavailable for partial reporting.
- [x] Contract fixtures cover timeout, stale data, duplicates, insufficient samples, incompatible variants, and schema drift.
- [x] Comparable Snapshots, exclusion decisions, provider snapshot metadata, and immutable Market Assessment versions are stored through JPA repositories with query projections or explicit fetch plans for report-sized reads.
