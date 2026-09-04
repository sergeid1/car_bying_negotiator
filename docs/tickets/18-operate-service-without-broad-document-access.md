# 18 — Operate the service without broad document access

**What to build:** Operators can diagnose failures, monitor providers and costs, inspect rule versions, and process separately consented reviews without unrestricted access to Buyer documents.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case; 06 — Extract Deal Terms with field-level evidence; 11 — Generate the core Deal Analysis Report.

**Status:** ready-for-agent

- [ ] A redacted diagnostic view exposes operation stages, safe error details, correlation identifiers, and policy/provider versions without raw document text or unrestricted artifacts.
- [ ] Provider status and schema or contract failures can be distinguished from Buyer-input and internal failures.
- [ ] Provider/model usage records operation, units, cost, status, and license metadata per Buying Case without sensitive payloads.
- [ ] Only an active human-review consent can place a case into the manual-review queue, and revocation removes unstarted access.
- [ ] Review access is least-privilege, time-bounded, and audit-recorded.
- [ ] Ordinary logs, analytics, support views, and error trackers exclude raw documents, raw extracted text, access links, identity-plus-VIN, and financing details.
- [ ] Operators can inspect deletion progress and completion evidence without restoring broad document access.
- [ ] Diagnostic, Provider Usage, review-access, and audit metadata are queried through least-privilege JPA projections that exclude sensitive entity fields and do not trigger lazy loading of documents or extracted text.
