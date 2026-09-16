# 20 — Harden security, resilience, performance, and accessibility

**What to build:** The complete workflow meets the declared private-alpha and public-pilot safety, reliability, performance, recovery, and accessibility requirements under adversarial and failure testing.

**Blocked by:** 17 — Expire and delete the complete Buying Case lifecycle; 18 — Operate the service without broad document access.

**Status:** in-progress — resumed on 2026-09-16; current evidence below

- [x] Threat modeling covers case-link access, tenant isolation, object keys, signed URLs, uploads, parsing, worker boundaries, providers, support access, and deletion.
- [ ] Automated checks cover dependencies, containers, source, secrets, MIME confusion, malicious files, decompression limits, rate limits, and cross-case isolation.
- [x] Backup restoration and deletion behavior are exercised and accurately reflected in recovery and retention documentation.
- [ ] Retries, timeouts, queue redelivery, provider outages, partial failures, and report-generation failures preserve valid case state and remain idempotent.
- [ ] Deterministic recalculation and document-processing latency are measured against their declared percentile targets.
- [ ] Core flows pass automated accessibility checks plus keyboard and screen-reader walkthroughs with no critical WCAG 2.2 AA violations.
- [ ] An incident runbook, escalation path, access policy, and release rollback criteria are exercised before pilot approval.
- [x] JPA behavior is covered by integration tests for transaction rollback, optimistic or pessimistic locking, idempotency races, tenant predicates, N+1 queries, bounded fetch sizes, and migration-to-entity validation; measured hot paths may use documented native SQL or JDBC.

## Current checkpoint — 2026-09-16

Continued on the existing ticket branch; changes remain uncommitted.

- Renamed the workflow to `.github/workflows/ci.yml`, display name `CI`; CI database,
  image tag and evidence artifact use neutral names too.
- Verified the restore orphan-artifact fix with all nine restore/deletion tests, and removed
  the unused reconciler dependency. Updated retention policy and README links.
- Startup analysis and document recovery now fetch at most 100 operations using immutable-ID
  cursors, processing each batch in one recovery task per workflow. A regression fixture
  traverses more than two batches while stages change, proving rows are not skipped.
- `ProviderMeter` enforces a configurable deadline and a fixed concurrency ceiling without
  a waiting queue. Timeout/saturation fail safely; late results are discarded. Tests include
  an adapter ignoring interruption, provider failures, null results and caller interruption.
- Resilience/operations tests passed. All seven persistence tests passed against PostgreSQL
  18.4, including locking and recovery. All 21 migrations validated; the bundled Flyway warns
  that its tested PostgreSQL support stops at 17, a remaining compatibility decision.
- Full backend suite and package build passed: 118 tests, zero failures/errors/skips. All
  Surefire reports were refreshed by this run. The four local Semgrep rules found zero issues;
  workflow YAML parsed successfully and `git diff --check` passed.
- Local percentile evidence: cash recalculation p95 0.012125 ms (1,000 warm samples); document
  p50 121 ms / p95 128 ms (20 sequential two-page PDFs, H2, test scanner, deterministic
  extraction). These are fixture measurements, not production load or parser-isolation proof.
  Corrected the performance fixture's output description to say in-process PDF normalization.
- Frontend: all 17 tests and production build passed. Docker remains unavailable locally.

Remaining: parser process confinement and adversarial CPU/heap/decompression tests; deployed
worker/load and kill-during-work tests; container scan and remote CI execution; browser contrast,
keyboard and screen-reader walkthrough; operator roster and production restore/rollback/paging
drills; supported framework/Flyway decisions. Provider adapters still need transport deadlines
and must honor interruption; arbitrary in-process code cannot be forcibly stopped safely.

## Historical checkpoint — 2026-09-12

The following records the previous handoff; superseded next actions are retained as history.

Branch: `feature/security-resilience-performance-accessibility`. All changes are **uncommitted**,
including new untracked files. User explicitly requested stopping for the day. Do not restart
implementation from scratch or mark this ticket complete.

Implemented so far:

- Peer-address API rate limiting with bounded buckets, 429/Retry-After, security response headers
  and HTTP connection/header limits; spoofed forwarding-header regression test.
- Bounded deletion batches ordered by last attempt/request time.
- Completed synthetic operations cannot be downgraded by late workers; persistence tests for
  replay, rollback, concurrent idempotency, optimistic locking, tenant-scoped report reads and
  bounded report query counts. Tests support `HARDENING_DATABASE_URL` for PostgreSQL.
- Restore-mode API fence and separate UUID deletion-ledger reconciliation, with backup/restart
  acceptance tests. **The latest orphan-artifact fix still needs verification (see below).**
- Recalculation and supported-document percentile tests writing `backend/target/hardening/*.json`.
- axe-core and keyboard tests, skip link and improved button focus outline; frontend tests/build passed.
- Spring Boot upgraded to 3.5.16, Tomcat 10.1.59, Jackson BOM 2.21.4 and PostgreSQL JDBC 42.7.13.
  Trivy initially found 26 high/critical dependency findings; the patched filesystem rescan found
  zero high/critical vulnerabilities, secrets or misconfigurations. Four local Semgrep rules passed.
- Draft CI workflow, non-root backend Dockerfile, scanner script/rules, [threat model](../hardening-threat-model.md)
  and [incident/restore runbook](../hardening-runbook.md). These need final review and release evidence.

Exact stopping point / first next actions:

1. `BackupRestoreAcceptanceTest` was extended with an encrypted artifact whose case row is absent
   from the restored DB. It failed as expected because reconciliation skipped missing case rows.
   The patch to `RestoreDeletionReconciler` **landed and was confirmed at handoff**: it now enqueues
   deletion for every ledger ID, including IDs without case metadata. Its verification command was
   interrupted before running. Remove the now-unused `CaseDeletionService`
   dependency if appropriate. Rerun `mvn -q -Dtest=BackupRestoreAcceptanceTest,CaseDeletionAcceptanceTest test`.
   `/tmp/ticket20-restore-final.log` did not exist at handoff, so do not assume this check ran.
2. `/tmp/ticket20-backend-final.log` completed without `[ERROR]` entries on patched dependencies.
   It predates the final orphan-artifact correction. Earlier full suite passed 112 tests; focused
   upgraded-dependency tests passed, including the added optimistic-locking test. Avoid treating
   aggregated Surefire counts as a single run: focused runs overwrite individual XML reports.
3. Rerun final PostgreSQL persistence tests after the last changes, then appropriate full checks and
   `git diff --check`. An isolated PostgreSQL 18 cluster was started in `/tmp/ticket20-postgres`,
   port 55440, database `postgres`, user `deal_analyzer`, localhost trust authentication. The earlier
   five-test PostgreSQL persistence run passed; the new optimistic-lock test needs that same validation.
4. Finish/update evidence and policies. `docs/case-deletion-policy.md`, README and the ticket's
   checkboxes have not yet been reconciled with the restore implementation. Do not assert backup
   rotation, physical purge deadlines, production RPO/RTO, or pilot approval without evidence.

Remaining substantive gaps identified in the threat model:

- PDFBox and PNG/JPEG parsing remain in the application JVM despite the normalizer's name;
  full process confinement, adversarial PDF CPU/heap/decompression tests remain outstanding.
- Analysis/document recovery fetches remain unbounded; arbitrary provider implementations have
  no generic timeout imposed by `ProviderMeter`. Complete the resilience assessment and fixes.
- Container build/image scan and CI execution have not run: Docker daemon was unavailable.
- Live browser/VoiceOver walkthrough was blocked by missing Computer Use permissions. Automated
  axe tests disable contrast in jsdom; they are not a full WCAG conformance claim.
- Named operator/escalation roster, production restore and rollback/paging drills remain open.
- Boot 3.5.16 is the final public 3.5 release; document the supported-framework upgrade/support
  decision before pilot. Patched dependency scanning does not establish ongoing support.

Useful local evidence/tooling (temporary files may not survive until resumption):

- `/tmp/ticket20-backend-final.log`, `/tmp/ticket20-upgrade.log`, `/tmp/ticket20-postgres-tests.log`
- `/tmp/ticket20-orphan.log` (expected failure), `/tmp/ticket20-restore.log` (earlier restore pass)
- `/tmp/ticket20-security-scan.json`, `/tmp/ticket20-source-scan.json`, `/tmp/ticket20-source-summary.log`
- `/tmp/ticket20-scanners/trivy` v0.74.0; checksum verified against official release checksum file.
- `/tmp/ticket20-semgrep-current/bin/semgrep` v1.177.0 (Python 3.14 temporary environment).
  System Python 3.9 could not install this version. Scanner needed execution permissions for
  system trust certificates. Trivy used `DOCKER_CONFIG=/tmp/ticket20-public-registry` for public
  registry access without private credentials and `/tmp/ticket20-trivy-cache` for its database.
- Frontend dev server may still be running on `127.0.0.1:5173` (exec session 29778); temporary
  PostgreSQL may still be running. Stop only these task-owned processes when no longer needed.

No commit, PR, deployment or pilot approval has been performed.
