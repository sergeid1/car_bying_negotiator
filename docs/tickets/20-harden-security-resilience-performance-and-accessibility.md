# 20 — Harden security, resilience, performance, and accessibility

**What to build:** Implement predeployment security, resilience, performance, recovery, and accessibility hardening with automated and local verification. Deployment-dependent release validation belongs to the later pilot-release work and follow-up tickets.

**Blocked by:** 17 — Expire and delete the complete Buying Case lifecycle; 18 — Operate the service without broad document access.

**Status:** implementation complete pending green PR CI — deployment validation and pilot approval remain open in ticket 22

- [x] Threat modeling covers case-link access, tenant isolation, object keys, signed URLs, uploads, parsing, worker boundaries, providers, support access, and deletion.
- [x] Automated checks cover dependencies, containers, source, secrets, MIME confusion, malicious files, decompression limits, rate limits, and cross-case isolation locally; remote CI must pass before merge.
- [x] Backup restoration and deletion behavior are exercised and accurately reflected in recovery and retention documentation.
- [x] Local failure tests cover retries, timeouts, queue redelivery, provider outages, partial failures, and report-generation failures; deployed crash/load behavior remains a release gate.
- [x] Deterministic recalculation and document-processing latency are measured against local fixture percentile targets; deployed-load targets remain a release gate.
- [x] Core flows have automated accessibility checks and keyboard affordances; manual keyboard, contrast, reflow, and screen-reader walkthroughs remain a release gate.
- [x] An incident runbook, escalation path, access policy, and rollback criteria are documented; operator drills remain a release gate.
- [x] JPA behavior is covered by integration tests for transaction rollback, optimistic or pessimistic locking, idempotency races, tenant predicates, N+1 queries, bounded fetch sizes, and migration-to-entity validation; measured hot paths may use documented native SQL or JDBC.

Ticket 20 closes the implementation work, not pilot approval. The post-deployment exercises and
any resulting bugs are separate follow-up work; ticket 22 retains the pilot release gate. No unrun
walkthrough or drill is represented as a passing test.

## Current checkpoint — 2026-09-17

Branch: `feature/security-resilience-performance-accessibility`. The 2026-09-16 checkpoint
below is historical and its instructions about not resuming have been superseded by the user's
request to continue.

### Follow-up verification on 2026-09-17

- PR #18 ran the remote scans successfully. Its first `tests` job found timestamp-precision
  comparisons across database reloads, a fake ClamAV server that closed with unread input,
  and a 200 ms retention fixture that could expire before CI document processing completed.
  Follow-up fixes are under verification; do not merge until CI is green.

- Corrected the document percentile fixture to configure container dispatch in the launched
  application from an immutable `parser.performance.image` test property, and record that image
  ID in its evidence. CI now runs this fixture against its just-built parser image. The local
  20-document H2/test-provider fixture passed: process p50 2458 ms / p95 2541 ms; confined
  container p50 3170 ms / p95 3560 ms. The latter used image
  `sha256:48ef7189c5d1ccfed5fac79fee0baab71e1256dc4f62713bfa06a5c462432e3c`.
  These sequential local values do not establish deployed concurrent-load latency.
- PostgreSQL 17.11 migration startup and all seven `PersistenceHardeningTest` cases passed
  against a disposable local container. The rebuilt parser image scan with refreshed Trivy
  databases found zero high/critical vulnerabilities and zero secrets. The updated application
  image parser smoke check passed. `git diff --check` passed.
- Final `mvn -q verify` exited 0 after these changes: 124 Surefire tests and one packaged-JAR
  Failsafe test passed, with zero failures/errors/skips. The three opt-in container tests were
  skipped in this ordinary run; they passed separately earlier against immutable image IDs.

### Implemented this session

- Added `ContainerParserRuntime` and a `container` parser mode. The `pilot` profile rejects
  local process mode. A request launches an immutable local image ID with no network or host
  mounts, read-only root, UID/GID 10001, no capabilities, no-new-privileges, 1536 MiB total
  memory/no swap, one CPU, 64 processes, and a 128 MiB temporary filesystem. Every exit
  force-removes the named container and verifies its absence. Failed cleanup retains the
  concurrency slot. The in-container deadline also applies if the application dies.
- Added `backend/Dockerfile.parser` with `heif-dec` and its HEVC decoder plugin. The real
  application dispatch now processes synthetic PNG, PDF and HEIC in confined containers.
  A test-only image runs a native HEIC decoder probe for filesystem, network, capability,
  cgroup and temporary-space restrictions. `ContainerParserIT` also tests timeout cleanup
  and successful processing afterward. CI builds and checks both images; the release image
  excludes the probe. See `docs/document-parser-policy.md` for deployment requirements.
- CI now uses PostgreSQL 17, matching `compose.yaml` and the bundled Flyway support range,
  and scans the parser image. Updated the incident runbook to reflect that the user is the
  sole operator, with all incident, privacy, recovery and release responsibilities.

### Verified evidence and limits

- A completed `mvn -q verify` **before the container-mode additions** passed 122 Surefire
  tests plus `DocumentParserPackagingIT` (one Failsafe test), with zero failures/errors/skips.
  The frontend passed 17 tests and its production build.
- The focused parser tests and packaged-JAR integration test passed. The container integration
  suite passed all three tests, including real synthetic HEIC decode, native quota probe and
  timeout recovery. No `document-parser-*` containers remained after the suite.
- Trivy 0.74.0 with a refreshed database reported zero high/critical vulnerabilities and zero
  secrets for `deal-analyzer-parser:ticket20`. `git diff --check` passed.
- Local fixture measurements in `backend/target/hardening`: 20 sequential two-page PDFs had
  p50 2440 ms and p95 2970 ms using disposable local parser JVMs, H2, a test scanner and
  deterministic extraction. Cash recalculation p95 was 0.005667 ms for 1,000 warm samples.
  These are not confined-container, deployed-load or production-provider measurements.
- The final `mvn -q verify` completed with exit code 0 after the container-mode changes.
  It ran 124 Surefire tests with zero failures/errors/skips, plus the packaged-JAR Failsafe
  test. The three opt-in container tests were skipped in that ordinary run and passed
  separately against immutable local image IDs. Captured log: `/tmp/ticket20-final-verify.log`.
- Browser keyboard inspection reached and focused the skip link. Further Chrome control was
  denied, so no complete keyboard, contrast, reflow or screen-reader walkthrough is claimed.
  At that checkpoint, remote CI and PR creation had not yet run; PR #18 now tracks the code.
  No deployment, restore/rollback exercise or pilot approval has run.

### Release handoff

The local confined-container percentile fixture, PostgreSQL 17 checks, rebuilt-image scan,
and smoke check passed. PR #18 must pass remote CI before merge. Deployment architecture,
daemon/worker crash recovery, concurrent load, native HEIC and quota checks, manual
accessibility walkthroughs, operator alert/restore/rollback drills, and the Spring Boot
support decision are later pilot-release validation and follow-up work. This implementation ticket can close
without claiming those release gates have passed or approving a pilot.

## Current checkpoint — 2026-09-16

Checkpoint committed as `96c5c9a` ("Add security controls, bounded recovery, and CI validation").
The subsequent parser/container work below is **uncommitted**, including untracked files.
Branch: `feature/security-resilience-performance-accessibility`. User explicitly requested
stopping work and updating this ticket. Do not resume implementation until asked.

### Stop point and verified evidence

- Stopped the final `mvn -q verify` run (exec session 10089) with Ctrl-C; it exited 130.
  **The current parser changes do not have a completed final full-suite pass.** Surefire
  reports contain a mix of runs; their aggregate is not evidence of one passing full run.
- The first full run executed 122 tests and found two regressions: a rejection message lacked
  retry guidance, and the document performance fixture's 50 ms polling hit the API rate limit.
  The message was fixed; the fixture now uses the Buyer's 250 ms polling cadence.
- The subsequent focused run passed the parser and performance checks but had one localhost
  connection failure on the initial create-case POST in
  `DealerDocumentAcceptanceTest.signaturesDetermineSupportedMediaAndPdfPageWarningsPreserveOrder`.
  That exact test passed on rerun, along with the packaged parser integration test. No
  speculative change was made to the HTTP harness; investigate if the connection failure recurs.
- All four `DocumentParserProcessTest` adversarial tests passed. `DocumentParserPackagingIT`
  passed against the executable Boot JAR. Both are now included in `mvn verify`.
- Completed performance fixtures in the interrupted final run measured document p50 **2443 ms**
  and p95 **2459 ms** (20 sequential two-page PDFs, H2, test scanner, disposable parser JVMs,
  deterministic extraction), and cash recalculation p95 **0.006125 ms** (1,000 warm samples).
  These are local fixture results, not production/load guarantees.
- Docker became available. Built `deal-analyzer:ticket20`, running as UID/GID `10001:10001`,
  image ID `sha256:4d3fb5f13a6be8eddbc7220a678a7f91101dd7c36266aae3b7e2fa3f3bd99c18`.
  Added `backend/.dockerignore` so the build context contains the packaged JAR rather than
  development storage, test reports or other backend files.
- The built image's PNG parser smoke check passed with networking disabled, read-only root,
  all capabilities dropped, no-new-privileges, and memory/CPU/process/tmpfs quotas. Added
  `scripts/check-parser-container.py` and wired it into `ci.yml`. A subsequent small script
  edit adds named-container cleanup on timeout; that final edit has not been rerun.
- Trivy 0.74.0, with refreshed databases, found **zero high/critical vulnerabilities and zero
  secrets** in the actual image. The repository scan found **zero high/critical vulnerabilities,
  secrets or misconfigurations**. Five local Semgrep rules passed with zero findings, including
  the new parser-placement rule. `npm audit --audit-level=high` reported zero vulnerabilities.
- Remote GitHub CI has not run, and no PR, push, deployment or pilot approval was performed.

### First actions when resuming

1. Preserve the uncommitted parser/container changes and inspect `git status`; do not restart
   from the earlier checkpoint or lose the new untracked files.
2. Run `cd backend && mvn -q verify` to completion. Expected suites include 122 regular tests
   plus the packaged parser integration test; confirm actual reports from the completed run.
   Maven tests and local HTTP servers remain pre-approved. Do not treat the interrupted run
   or mixed reports as a pass.
3. Recheck the final container smoke script and CI configuration, then reconcile the runbook's
   older Docker-unavailable statements with the successful local image validation above.
   Run `git diff --check` before the next commit.
4. Production parser dispatch still launches a JVM as the application OS user. The isolated
   container smoke test proves packaging compatibility, **not integration of deployment
   confinement**. Implement/test filesystem and network isolation and OS-enforced aggregate
   memory, CPU, process and scratch-space quotas, including the native HEIC decoder. The PNG
   container smoke test does not establish HEIC runtime provisioning or containment.
5. Continue deployed crash/recovery/load testing, remote CI, accessibility walkthroughs and
   operator/restore/rollback drills. Framework support and Flyway/PostgreSQL compatibility
   decisions remain open. Do not mark the ticket complete or approve a pilot.

Current evidence locations (temporary files may disappear):

- `backend/target/surefire-reports`, `backend/target/failsafe-reports` — mixed/interrupted-run
  caveat above applies; inspect timestamps and test names.
- `backend/target/hardening/documents.json`, `backend/target/hardening/recalculation.json`.
- `/tmp/ticket20-container-security.json`, `/tmp/ticket20-parser-repository-security.json`,
  `/tmp/ticket20-parser-source.json`.
- `/tmp/ticket20-scanners/trivy`, `/tmp/ticket20-semgrep-current/bin/semgrep`,
  `/tmp/ticket20-trivy-cache`, `/tmp/ticket20-public-registry`.
- The local Docker image is retained. No smoke-test container was left running. The existing
  PostgreSQL cluster/dev-server from earlier work were not stopped as part of this pause.

### Parser continuation

- Moved upload inspection, PDF/image rendering and PDF text extraction into disposable JVMs
  with bounded heap, deadlines, concurrency, protocol lengths and page/text output sizes.
  Removed the in-process parsing paths; worker requests receive no application environment.
- Added incremental PDF page-content expansion limits, safe capacity failures, temporary
  directory cleanup and process/descendant termination. Existing HEIC deadlines still apply.
- Added real adversarial tests for compressed PDF content, child heap exhaustion, endless
  native decoder CPU use, concurrency saturation, environment isolation and recovery after
  failure. Added a Maven verification test for worker launch from the packaged Boot JAR.
- Added a source rule guarding parser calls and documented the limits and remaining OS
  confinement work in [the parser policy](../document-parser-policy.md).

### Earlier validation

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
- Frontend: all 17 tests and production build passed. Docker was unavailable at that earlier checkpoint; current image evidence is recorded above.

Remaining: deployment OS filesystem/network confinement and total native memory/CPU/process/scratch-space quotas; deployed
worker/load and kill-during-work tests; final full-suite verification and remote CI execution; browser contrast,
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

At this historical checkpoint, no commit, PR, deployment or pilot approval had been performed.
