# 18 — Operate the service without broad document access

**What to build:** Operators can diagnose failures, monitor providers and costs, inspect rule versions, and process separately consented reviews without unrestricted access to Buyer documents.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case; 06 — Extract Deal Terms with field-level evidence; 11 — Generate the core Deal Analysis Report.

**Status:** implemented

- [x] A redacted diagnostic view exposes operation stages, safe error details, correlation identifiers, and policy/provider versions without raw document text or unrestricted artifacts.
- [x] Provider status and schema or contract failures can be distinguished from Buyer-input and internal failures.
- [x] Provider/model usage records operation, units, cost, status, and license metadata per Buying Case without sensitive payloads.
- [x] Only an active human-review consent can place a case into the manual-review queue, and revocation removes unstarted access.
- [x] Review access is least-privilege, time-bounded, and audit-recorded.
- [x] Ordinary logs, analytics, support views, and error trackers exclude raw documents, raw extracted text, access links, identity-plus-VIN, and financing details.
- [x] Operators can inspect deletion progress and completion evidence without restoring broad document access.
- [x] Diagnostic, Provider Usage, review-access, and audit metadata are queried through least-privilege JPA projections that exclude sensitive entity fields and do not trigger lazy loading of documents or extracted text.

Implementation, operator configuration, review scope and privacy boundaries:
[restricted service operations policy](../service-operations-policy.md).

Validation: full Maven suite (97 tests), frontend suite (13 tests), production
frontend build and `git diff --check` passed. HTTP acceptance tests cover role
separation, safe error responses/logs, provider contract failures and costs,
consent revocation/re-grant, reviewer assignment, grant expiry, scoped audited
content reads, deletion evidence and emitted SQL projection boundaries.
