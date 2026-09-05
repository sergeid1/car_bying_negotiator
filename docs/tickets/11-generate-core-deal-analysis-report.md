# 11 — Generate the core Deal Analysis Report

**What to build:** An idempotent analysis request creates an immutable, responsive, accessible Deal Analysis Report that leads with the decision-relevant outcome and traces every material amount to evidence or calculation.

**Blocked by:** 10 — Approve immutable price boundaries.

**Status:** Complete

- [x] The report leads with review status, separately calculated market/deal rating, top three reasons, and next action.
- [x] Review status follows the declared materiality and fail-closed rules and uses “Matches approved deal” rather than legal-safety language.
- [x] Every material amount links to Buyer input, source evidence, or reproducible calculation and is labeled exact, estimated, or unavailable.
- [x] The report states its as-of time, jurisdiction coverage, sources, policy versions, limitations, and excluded considerations.
- [x] Market-provider failure can produce `PARTIAL` with useful cash economics and market analysis explicitly unavailable.
- [x] Core report navigation works responsively with keyboard access, screen-reader labels, adequate contrast, focus handling, and non-color warning cues.
- [x] Repeating analysis with the same idempotency key yields one operation and one immutable report version.
- [x] Release fixtures contain no false `MATCHES_APPROVED_BASELINE` outcome.
- [x] Analysis Report versions and reproduction metadata are persisted through a JPA repository as immutable records with database-enforced `(case, version)` and idempotency uniqueness; retrieving a report does not depend on an open persistence session.
