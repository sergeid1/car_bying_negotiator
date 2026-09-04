# 20 — Harden security, resilience, performance, and accessibility

**What to build:** The complete workflow meets the declared private-alpha and public-pilot safety, reliability, performance, recovery, and accessibility requirements under adversarial and failure testing.

**Blocked by:** 17 — Expire and delete the complete Buying Case lifecycle; 18 — Operate the service without broad document access.

**Status:** ready-for-agent

- [ ] Threat modeling covers case-link access, tenant isolation, object keys, signed URLs, uploads, parsing, worker boundaries, providers, support access, and deletion.
- [ ] Automated checks cover dependencies, containers, source, secrets, MIME confusion, malicious files, decompression limits, rate limits, and cross-case isolation.
- [ ] Backup restoration and deletion behavior are exercised and accurately reflected in recovery and retention documentation.
- [ ] Retries, timeouts, queue redelivery, provider outages, partial failures, and report-generation failures preserve valid case state and remain idempotent.
- [ ] Deterministic recalculation and document-processing latency are measured against their declared percentile targets.
- [ ] Core flows pass automated accessibility checks plus keyboard and screen-reader walkthroughs with no critical WCAG 2.2 AA violations.
- [ ] An incident runbook, escalation path, access policy, and release rollback criteria are exercised before pilot approval.
- [ ] JPA behavior is covered by integration tests for transaction rollback, optimistic or pessimistic locking, idempotency races, tenant predicates, N+1 queries, bounded fetch sizes, and migration-to-entity validation; measured hot paths may use documented native SQL or JDBC.
