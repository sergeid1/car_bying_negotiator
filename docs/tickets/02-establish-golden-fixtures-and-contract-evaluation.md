# 02 — Establish golden fixtures and contract evaluation

**What to build:** A reusable verification harness that makes calculation correctness, report reproducibility, extraction quality, provider behavior, idempotency, and review safety measurable as subsequent product slices are added.

**Blocked by:** 01 — Prove the synthetic cash-deal walking skeleton.

**Status:** complete

- [x] Golden cash-deal fixtures assert monetary results, declared rounding, calculation steps, and version metadata.
- [x] Contract fixtures can exercise provider success, timeout, stale data, partial data, duplicates, and schema drift without paid live calls.
- [x] Report replay demonstrates that the same frozen inputs and policy versions reproduce the same structured output.
- [x] The harness can score extraction fields and distinguish presence recall from numeric exact match.
- [x] Duplicate workflow requests can be tested for one operation and one resulting resource version.
- [x] Review fixtures fail if an unsafe `MATCHES_APPROVED_BASELINE` result is produced.

## Completion notes — 2026-08-27

- `99d2777` establishes the initial golden fixtures and all six ticket checks.
- `5abf63f` adds the shared HTTP harness, rate-limit and missing-VIN provider scenarios, weighted
  per-field extraction scores with a 99% material-presence gate, and expanded review-safety cases.
- `006db0c` makes unexpected extraction fields measurable, applies field weights to the material
  gate, constrains replay difference declarations, and closes the HTTP helper review finding.
- Verification passed: 14 backend tests, one frontend test, and the production frontend build.
- The final two-axis review against `4704d92` found no documented standards violations and no Spec
  findings. One non-blocking judgement call remains: replay difference field names are materialized
  twice inside `ReportReplayEvaluator`.
