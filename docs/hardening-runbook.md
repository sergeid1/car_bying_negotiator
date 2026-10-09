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

## Buyer client response headers

Serve `frontend/dist` over HTTPS and apply the single header definition in
[`frontend/client-headers.json`](../frontend/client-headers.json) to client HTML (including
SPA fallback routes) and static assets. Configure the deployment's static host/ingress to
read or translate that name/value map; copying the build alone does not install headers.
Keep `/api` routing separate and preserve the service's existing no-store, no-referrer,
nosniff and correlation headers, including the export download's sandboxed CSP. Do not
replace API headers with the client policy. The browser calls the API through its own origin.

The client CSP restricts scripts, styles, fonts and connections to the site's origin,
blocks inline scripts and eval, forbids objects, base changes and framing, and restricts
forms to the site's origin. Images allow `'self' blob:` because authenticated document
previews fetch normalized pages with a case header and create local blob image URLs.
Only trusted page scripts create these URLs; same-origin-only connections constrain the
fetches. The allowance does not extend to scripts, frames or workers, and `data:` images
and external image origins remain blocked. React assigns source outlines/price markers
through the CSSOM, which works without `unsafe-inline`. Theme initialization and fonts
remain external same-origin assets. See [the header rationale](../frontend/client-headers.md).

The map also defines one-year HSTS, nosniff, no-referrer and a restrictive Permissions-Policy.
HSTS is honored only over HTTPS; verify HTTPS redirects and headers at the deployed edge
before enrollment. HSTS intentionally omits `includeSubDomains` and `preload`, which require
a separate inventory and commitment for all subdomains. Verify framing refusal and a
synthetic case's document preview in the deployed browser as well as the local tests.

`npm run build` runs `check:client-security` against emitted HTML, CSS and JavaScript, rejecting
non-origin resource URL literals, inline HTML scripts/styles and dynamic code execution.
React namespace identifiers and its error-documentation URL are narrowly exempted as
non-resource identifiers; text download links are also exempted. This static guard cannot
prove arbitrary computed URLs safe: the enforced CSP remains the runtime boundary.
CI already runs this build and Playwright. `client-content-security-policy.spec.ts` checks
the exact headers and requires zero violations on intake, upload, verification, saved
reports/evidence/download controls, recovery and stored theme reloads in both themes.
Vite preview uses the same header map; Vite development/HMR remains unrestricted by this
production policy. Re-run these gates whenever adding a script, resource or client SDK.

## Request email quotas

Sign-in requests, case recovery enrollment (`POST /cases/{id}/recovery-email`) and public
case recovery requests share these per-process limits:

| Property | Default | Meaning |
| --- | --- | --- |
| `security.request-email.peer-requests` | `5` | Requests per connection peer, shared across the three routes |
| `security.request-email.peer-window` | `PT15M` | Fixed peer window beginning with its first request |
| `security.request-email.max-peers` | `10000` | Maximum peer buckets; new peers are refused when full |
| `security.request-email.hourly-limit` | `100` | Combined sign-in/recovery delivery attempts per one-hour window |

All values must be positive at startup. Only the connection peer is used; forwarding headers
are ignored. A reverse proxy therefore shares a bucket for its clients. Peer exhaustion returns
429 with `Retry-After`; hourly exhaustion preserves each route's acknowledgement but sends no
mail and creates no link. SMTP failures consume capacity, while idempotent replays and requests
without an eligible delivery do not. The hourly window begins with the first delivery attempt.

Route the WARN event `REQUEST_EMAIL_HOURLY_LIMIT_REACHED` from
`com.dealanalyzer.recovery.RequestEmailLimits` to Sergei's monitored alert destination. It
contains no address, credential, peer or request content and occurs only once per exhausted
window, on the first suppressed attempt. Verify alert delivery and acknowledgment before
enrollment. Investigate aggregate traffic and adapter failures, then pause affected enrollment
at ingress if abuse continues; do not raise the ceiling just to clear the warning.

Limits are held in memory and reset on restart. Configure shared ingress quotas and global
caps for replicas, distributed clients and restart resets, plus lower upload/write quotas.
Account contact edits cannot change email, and notifications use the verified account address
with their existing hourly quota. Dealer mail is sent only by the Buyer from their own email
app, or through the Gmail or Outlook account the Buyer connected (ticket 83); the platform never
emails a dealer from an address of its own (ticket 84). Connected-mailbox sends are capped per
dealer, per Buying Case and per account in a rolling window (`MAILBOX_CAPS_PER_DEALER`,
`MAILBOX_CAPS_PER_CASE`, `MAILBOX_CAPS_PER_ACCOUNT`, `MAILBOX_CAPS_WINDOW`; 10, 20 and 40 per
24 hours by default) and never retried automatically. Keep `MAILBOX_SENDER=none` until the
Google OAuth verification for `gmail.send` and the Microsoft publisher verification for
`Mail.Send` are complete; `live` then needs `MAILBOX_GOOGLE_CLIENT_ID`/`_SECRET`,
`MAILBOX_MICROSOFT_CLIENT_ID`/`_SECRET`, `MAILBOX_REDIRECT_URI` registered with both providers,
and a random `MAILBOX_TOKEN_ENCRYPTION_KEY` of at least 32 bytes, separate from the document and
case keys. The fake sender fails startup under a pilot or production declaration.

Production connecting remains disabled. Record every obligation in
[mailbox provider boundaries](mailbox-provider-boundaries.md) before enabling live adapters:
provider app/publisher verification, exact HTTPS redirect registration, least-privilege consent,
secret management, and real send/revocation checks. The mailbox production guard verifies
adapter/key configuration only; it does not establish any of those deployment facts.

## Accepted scan exceptions

`scripts/security-checks.sh` fails on any high or critical finding. A finding may be accepted only
in `.trivyignore`, with the reason it is unreachable, the guard that keeps it unreachable and an
`exp:` date, after which the scan fails again. The current entry is CVE-2026-47884 (Spring
Framework `XsltView`), guarded by the Semgrep rule `no-xslt-view` and removed by ticket 85.

## Document key rotation

### S3 document storage

Development defaults to `DOCUMENT_STORAGE_PROVIDER=file`. Pilot/production refuses file
storage unless `DOCUMENT_STORAGE_ALLOW_FILE=true` explicitly acknowledges an operator-managed
encrypted volume. For AWS set `DOCUMENT_STORAGE_PROVIDER=s3`, `DOCUMENT_STORAGE_S3_BUCKET`
and `DOCUMENT_STORAGE_S3_REGION`; do not put access keys in application configuration.
The AWS SDK v2 uses its [default credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html),
with an instance/task role in deployment. Every original/page is encrypted with the application
key ring before upload and requests SSE-S3 (`AES256`). Keys contain only the existing
case/document identifiers and optional page number; filenames and contact details are excluded.
No local plaintext or ciphertext staging is needed for S3. Requests have bounded connection,
socket, attempt and overall timeouts; failed reads return the safe retryable document-unavailable
outcome. Retention and case erasure retry failed physical deletion without claiming completion.

Ticket 99 must create the private bucket in the declared region, enable all four bucket
Block Public Access settings, and preferably leave versioning never enabled. The production
guard checks bucket existence, Block Public Access, nonpublic policy and ACL, and versioning.
An enabled/suspended bucket additionally needs a bucket-wide one-day noncurrent expiry rule
without retained-version counts or conditional filters; see the [deletion policy](case-deletion-policy.md).
The role needs `s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:DeleteObjectVersion`,
`s3:ListBucketVersions`, `s3:ListBucket`, `s3:GetBucketVersioning`, `s3:GetLifecycleConfiguration`,
`s3:GetBucketPublicAccessBlock`, `s3:GetBucketPolicyStatus`, and `s3:GetBucketAcl` on the proper
bucket/object resources. Missing inspection permissions fail startup. The guard is read-only.
It cannot establish actual role isolation, region residency, account/access-point policies,
Object Lock absence, replica/backup expiry or real AWS removal latency; verify those in ticket 99.
Recheck controls after policy changes, and never enable replication or Object Lock without a
compatible erasure design. AWS [noncurrent lifecycle expiration](https://docs.aws.amazon.com/AmazonS3/latest/userguide/intro-lifecycle-rules.html)
does not prove timely physical purge by itself.

Take a consistent database and encrypted-object backup with all writers stopped/fenced.
An S3 bucket is not an independent backup: unversioned deletion is permanent, and versioned
objects are explicitly purged by the erasure adapter. Keep the complete application key ring
in a separate protected secret backup and record the region, bucket, object manifest/checksums,
database cutoff and independent deletion-ledger cutoff. AWS SSE-S3 does not replace the
application keys. Backup rotation/physical expiry remains `UNKNOWN` until measured.
Restore encrypted objects to a new private isolated bucket under the same guard, restore a
compatible database and key ring, and use the quarantined restoration procedure below to replay
the latest independent deletion ledger, including orphaned prefix objects and old versions.
Keep ingress fenced until retention and erasure have drained and synthetic reads/exports and
deleted-case absence are verified. Never restore an old bucket snapshot directly into live service.

The offline rotation command also accepts `DOCUMENT_STORAGE_PROVIDER=s3` plus bucket/region
and the same default role credentials. S3 replacement writes one complete encrypted object;
old versions remain subject to the policy above and physical case deletion. Preserve old
key generations while any retained version or independent backup still needs them.
For file rotation in pilot/production, explicitly set `DOCUMENT_STORAGE_ALLOW_FILE=true`.

### Application key ring

Configure `DOCUMENT_STORAGE_ACTIVE_KEY_ID` as a stable, nonsecret ID (1–64 ASCII letters,
digits, underscores or hyphens; `legacy` is reserved). `DOCUMENT_STORAGE_KEYS` is a comma-separated
list `id=base64,id=base64` containing the active key and all retained generations. Each value
must encode at least 32 cryptographically random bytes; generate it with `openssl rand -base64 32`
and deliver it through the deployment secret manager. Never put material in command arguments,
logs or tickets. Printable passphrases and obvious repeated material are rejected. These checks
cannot prove randomness; a cryptographic random generator is an operator requirement.
Never reuse an ID for different material. New originals and normalized pages carry a versioned,
authenticated header with their key ID. The storage filesystem must support atomic replacement;
an unsupported filesystem fails the write/rotation rather than risking a partial replacement.
The database reference also records the ID; the blob header is authoritative for decryption.

Keep `DOCUMENT_STORAGE_ENCRYPTION_KEY` unchanged for headerless legacy blobs. Its historical
SHA-256-of-string derivation remains compatible. Development accepts existing long secrets;
pilot/production refuses passphrases and requires the legacy string itself to be base64 of
random bytes when it is supplied. If a development passphrase was used, migrate those objects
in development before moving to pilot. Without an active ID, legacy development writes still
work and now record the reserved `legacy` ID in a header. A configured key ring is validated
at startup; the existing pilot/production missing-key startup refusal remains in force.

1. Add a fresh key ID/material while keeping the old key in `DOCUMENT_STORAGE_KEYS`, then
   switch the active ID. Restart with the complete ring. Verify retained synthetic originals,
   pages and case exports. A missing key returns the safe document-unavailable response (503,
   `DOCUMENT_KEY_UNAVAILABLE`), never a generic 500 or crypto diagnostics.
2. Stop every application replica, upload/normalization worker, retention/deletion worker and
   other writer to this database and object volume. Fence ingress. Take a consistent backup
   and protect the full key ring independently. Rotation is an **offline** command; the explicit
   `--service-stopped` argument acknowledges this operational requirement, it does not enforce
   a distributed lock. Do not run it alongside the service.
3. On the application runtime classpath (including the JDBC driver), run:

   ```text
   java -cp 'backend/target/classes:backend/target/dependency/*' com.dealanalyzer.document.DocumentKeyRotation --service-stopped 100
   ```

   Prepare a fresh dependency directory with
   `mvn -f backend/pom.xml compile dependency:copy-dependencies -DexcludeGroupIds=commons-logging`;
   exclude the conflicting `commons-logging` jar so Spring's logging bridge does not print a
   discovery warning. Do not enable debug/trace logging for this command.
   Supply `DOCUMENT_STORAGE_PATH` for file storage or the S3 provider/bucket/region variables,
   the complete key variables above, and
   `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` through
   the environment. Preserve the deployment declaration (`SPRING_PROFILES_ACTIVE` or
   `EVIDENCE_DEPLOYMENT`) so the same startup material rules apply; development legacy
   passphrases can be migrated only under a development declaration. No server or scheduled
   task starts. The command fetches at most the specified 1–1000 objects per database batch, replaces
   each blob atomically with fresh AES-GCM encryption and the same case/document/page AAD,
   then updates original metadata. It includes expired/quarantined objects until physical
   erasure. Output contains only scanned/rotated counts; failures exit nonzero with a safe
   message. A restart scans from the beginning, verifies and skips already active objects,
   and repairs a metadata update interrupted after blob replacement. No plaintext checkpoint
   or plaintext temporary object is written. Do not remove keys after an incomplete run.
4. Require a complete run and a second run with zero rotated objects, then verify synthetic
   export/read, retention and deletion before restarting all replicas with the new active ID.
   The command covers database-registered originals/pages; inventory and erase unreferenced
   artifacts and interrupted encrypted temporary files separately before dropping their keys.
5. Remove a retired key only when no retained live blob needs it **and** every backup/snapshot
   that needs it has expired, been securely erased, or been separately migrated and verified.
   Keep legacy secrets while any headerless object, retained export snapshot or relay ciphertext
   needs them. This command rotates document files only: export/relay encryption still uses
   the legacy variable. Never change that variable as part of this document-file rotation.
   Restore older backups under the quarantine procedure with their original key generations.

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
