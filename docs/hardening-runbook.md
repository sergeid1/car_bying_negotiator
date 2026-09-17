# Recovery, incidents and release gates

This is a runnable procedure, not pilot approval. Sergei is the sole operator and owns
incident response, engineering, privacy decisions, database recovery and release approval.
These are responsibilities, not separate staffing requirements. Before enrollment, configure
an alert destination Sergei monitors and test delivery and acknowledgment. If unavailable,
pause enrollment and affected processing until service can be operated safely. No production
notification or rollback exercise has been claimed.

## Incident procedure

1. Pause enrollment and affected writes at ingress. Keep deletion processing running where
   safe. For suspected cross-case disclosure, block the affected read/download routes too.
2. Record detection time, release/image digest, affected route, safe correlation IDs, aggregate
   counts and failure category. Do not copy documents, tokens, emails or provider payloads into
   incident tools. Use the restricted access policy in `service-operations-policy.md`.
3. Alert Sergei immediately for disclosure, parser escape,
   unexpected credential use, lost immutable reports, or an unverified restore. Escalate to
   the privacy owner for suspected disclosure/deletion failures and the database operator for
   migration, lock or recovery failures. Sergei handles each of these responsibilities.
   Record the alert destination and a tested acknowledgment target before pilot enrollment.
4. Contain the failing provider/parser or release. Never bypass malware scanning or consent
   to restore throughput. Preserve safe diagnostics and the current deletion ledger outside
   the affected backup lineage. A provider outage may yield unavailable evidence, never an
   invented favorable finding.
5. Roll back application code only to an image proven compatible with the current schema.
   Never blindly undo Flyway or restore an older database over the live system. An incompatible
   migration requires a forward repair or the quarantined restore procedure below.
6. Re-run isolation, replay, deletion, migration and applicable performance checks against the
   candidate. Resume only after the incident commander and relevant owner record evidence.

Stop/rollback criteria: any cross-case disclosure, failed signature/malware enforcement,
changed historical report, unexpected schema validation failure, stalled deletion backlog,
critical accessibility violation, or unexplained lock/query regression. Treat p95 recalculation
at/above 500 ms, document p50 at/above 60 seconds or p95 at/above 180 seconds as a hold until
the measured workload, queue backlog and failed operations are understood. Never exclude
failed/stuck operations to make a percentile pass. Deletion completion p95 must remain within
24 hours, accompanied by the overdue/pending counts.

## Quarantined restoration

1. Keep ingress closed. Disable analysis/document workers. Take a consistent database and
   encrypted-object backup using the infrastructure's documented snapshot procedure. Restore
   to a new isolated database and object location, preserving the old environment.
2. Obtain the latest deletion ledger from a separately retained record that includes requests
   after the backup cutoff, including still-pending requests. Export one lowercase case UUID
   per line using `SELECT case_id FROM case_deletion_jobs ORDER BY case_id`. Protect the file
   as operational metadata. Verify its cutoff and completeness with the backup operator.
   A UUID file cannot prove its own freshness. Never use only the ledger inside an old backup.
3. Start the restored application with the original compatible pepper/encryption keys and:

   ```text
   --recovery.restore-mode=true
   --recovery.deletion-ledger=/protected/latest-deletions.txt
   --analysis.worker.enabled=false
   --document.worker.enabled=false
   ```

   Restore mode returns 503 for every API request, even if startup has not finished. Startup
   rejects missing/malformed ledgers, ledgers over 4 MB or enabled analysis/document workers.
   Reconciliation revokes restored case credentials and queues ledger IDs for verified removal.
   Original persisted expiry deadlines remain in force. An empty ledger is valid only if the
   operator independently confirms that no deletion requests exist.
4. Let deletion batches drain. Check `case_deletion_jobs` for pending/failing work, verification
   receipts and absent artifacts. Re-run reconciliation safely after interruption. A missing
   encryption key, unavailable volume, provider erasure failure or unknown ledger cutoff must
   keep the restore quarantined. Split a larger ledger into reviewed batches under the same
   ingress fence; do not enlarge the in-memory cap casually.
5. Verify an authorized retained synthetic case and historical report, denied access for every
   sampled deleted/expired case and absent encrypted artifacts. Run Flyway then Hibernate
   validation. Record backup cutoff, ledger cutoff, restored row/artifact counts, checksums,
   elapsed restoration time and unresolved differences. Production RPO/RTO targets must be
   declared and measured by the infrastructure owner before approval.
6. Restart normally only after verification and owner approval, then open ingress. Restore
   mode is an explicit operator setting; omitting it from an unverified restore is unsafe.
   Preserve the ledger across rollback. Never extend case retention to compensate for downtime.

`BackupRestoreAcceptanceTest` exercises an older file-backed H2 snapshot, a later deletion,
an API fence, artifact removal (including an artifact with no restored case row), and
retained-case access after restart. This verifies application
behavior, not PostgreSQL physical backup tooling, external provider copies or backup expiry.
`backupStatus=UNKNOWN` and null `backupExpiresAt` remain truthful. Backup rotation and physical
purge guarantees must not be advertised until independently measured.

## Accessibility walkthrough to record before pilot

Use the released browser build with a supported screen reader (VoiceOver/Safari or NVDA/Firefox),
then repeat keyboard-only at 200% zoom and 320 CSS pixels wide. Record OS/browser/reader versions,
release digest, date, tester, steps, findings and remediation evidence.

Walk entry/recovery, opening a saved case, price-boundary approval, report findings and evidence,
negotiation editing, financing, download, shortened retention and confirmed deletion. Verify
Tab/Shift-Tab order, skip-link focus, no trap, visible/unobscured focus, labels, error/status
announcements, headings, non-color warnings, contrast and reflow. Verify that a removed control
does not strand focus and that irreversible actions have an explicit confirmation. Include
failure and pending states. Upload/verification routes also need a supported Buyer UI before
a complete end-to-end accessibility claim is possible.

The automated axe suite covers DOM rules tagged WCAG A/AA through 2.2 and initial core controls.
It disables color-contrast because jsdom has no layout engine. It does not certify WCAG conformance.

## Exercise record

Local automated drills on 2026-09-12 cover scanner outage, failed normalization and retry,
provider unavailable state, transactional report failure, stale JPA writes, idempotency races,
completed-operation redelivery, bounded deletion and restore fencing. The acceptance suites
are executable drill scripts. Real operator paging, compatible image rollback, production
restore, parser confinement and screen-reader exercises remain open release gates.

On 2026-09-16 the restore/deletion suites passed with the orphan-artifact regression.
Provider deadline, interrupted-caller and saturation tests passed, including an adapter
that ignores interruption. The seven persistence tests passed against PostgreSQL 18.4,
including 205-document recovery across bounded batches and optimistic locking. Flyway
validated all 21 migrations and Hibernate validated the entities; Flyway also warned that
this release's tested PostgreSQL support stops at 17. Resolve that support mismatch before
pilot approval. Frontend validation passed 17 tests and a production build; this remains
automated DOM evidence, not a recorded screen-reader walkthrough.

The resumed full backend suite/package build passed 118 tests with zero failures, errors
or skips. Four repository Semgrep rules passed with zero findings. The local warm cash
calculation fixture measured p95 0.012125 ms across 1,000 samples; 20 sequential two-page
PDFs measured p50 121 ms and p95 128 ms using H2, a test scanner and deterministic extraction.
These measurements exclude production provider latency and adversarial or concurrent load.
Those historical measurements predate disposable parsing. On 2026-09-16 Docker became
available: the release image ran as UID/GID 10001, the restricted PNG smoke check passed,
and Trivy found zero high/critical vulnerabilities and secrets in the image. Remote CI
execution was still outstanding. Current parser measurements must be used for capacity planning.

The parser continuation uses disposable JVMs for inspection, rendering and text extraction;
see [the parser policy](document-parser-policy.md). Adversarial fixtures cover compressed PDF
content, child heap exhaustion and endless native decoder execution, including cleanup and
successful subsequent processing. The release still needs OS-level filesystem/network
confinement and native resource quotas. The 20-document fixture now uses the Buyer's 250 ms
poll cadence; the old 50 ms loop could trigger the real rate limiter after subprocess startup
increased processing time. Do not compare its earlier in-process timing to production capacity.
