# Buying Case retention and deletion

Original documents default to at most 30 days (`DOCUMENT_RETENTION`, default
`PT720H`; startup rejects longer, zero or negative values). Case access defaults
to 30 days. Each upload also bounds case expiry to its document deadline.
`POST /api/v1/cases/{id}/retention` accepts `{"days": 1..30}` and shortens existing
case, document and export deadlines, measured from case creation. It never extends
an existing deadline. New uploads inherit the earlier configured/document/case
limit. Recovery cannot extend retention. If a selected deadline is already past,
access expires immediately. These choices are available in the Buyer interface.

`POST /api/v1/cases/{id}/deletion` with `Case-Access-Token` returns a 202 receipt.
Within one case-locked transaction it creates the JPA Deletion Job, changes case
lifecycle to `DELETION_PENDING`, clears the case credential and expires case access.
Recovery verification/issuance and export creation share that lock. A scoped HMAC
of the old credential permits only deletion replay and
`GET /api/v1/cases/{id}/deletion`; it cannot reopen the case. Wrong credentials get
404. Receipts survive physical case removal and contain no original credential,
email, filenames, object keys, financial terms or provider response content.

The scheduled worker (`case.deletion.delay`, default five minutes) also discovers
expired cases. Its request timestamp is the original expiry deadline, so queue
lag counts toward deletion latency. Independent expired export snapshots are
removed even while their case remains active. The existing document-retention
worker still records per-original/page removal; document lifecycle now uses
`DELETION_PENDING` instead of `DELETION_IN_PROGRESS`.

## Removal and evidence

The worker commits each attempt before external I/O. Failed attempts retain their
safe failure code across restarts and successful retries. `STARTED` with no finish
means interrupted or still running, not successful. Concurrent redundant attempts
can finish `SUPERSEDED`. A job is `DELETED` only after all configured artifact
participants verify absence and the database deletion transaction commits.

Local encrypted storage inventories the complete case UUID prefix, including
originals, normalized pages, orphaned outputs and temporary encrypted writes.
Missing storage with undeleted originals or normalized pages, and inaccessible storage, fail closed. Storage writes acquire the
same database case lock and reject expired/pending/missing cases, preventing late
normalization/upload work from recreating artifacts after verification. Retrying
an already removed file is safe. A failed database transaction rolls back the graph
and leaves external deletion safely retryable. No broad ORM cascades are used.

`CaseArtifactErasure` is the supported external/provider deletion boundary: every
configured participant must return only after verified removal, otherwise throw
and leave the job pending. Current OCR/extraction, listing and market adapters are
local deterministic implementations with no separately retained remote copies.
OCR text, extracted terms, provider snapshots and derived personal data live in
the graph below. Adding a provider that retains copies requires a corresponding
idempotent, write-fenced erasure participant and a provider contract fixture.
SMTP has no supported recall/deletion API: delivered recovery emails and recipient
mailbox copies cannot be erased by this service. Links are invalidated immediately.
Buyer-downloaded copies are outside the active system. No server case-content cache
exists; case responses use `no-store`, and the UI removes displayed case panels
when deletion is accepted. This does not erase previously downloaded copies.

Backup evidence is deliberately `UNKNOWN` with a null `backup_expires_at`.
This repository does not establish a backup rotation or physical purge deadline.
Active-system deletion is not a backup-erasure claim. Restore mode fences all API
access while a separately retained, current UUID deletion ledger is replayed and
original retention deadlines are applied. Ledger IDs are processed even when the
restored database has no case row, so orphaned encrypted artifacts are removed too.
`BackupRestoreAcceptanceTest` verifies this behavior against an older file-backed
H2 snapshot and application restart. Production PostgreSQL backup tooling, ledger
freshness, RPO/RTO and physical backup expiry remain infrastructure obligations.
Follow the [quarantined restoration procedure](hardening-runbook.md) before opening
ingress. Do not advertise a backup expiry date without verified infrastructure evidence.

## Explicit repository deletion plan

`CaseGraphDeletionPlan` is a fixed, parameterized native SQL plan. All identifiers
are checked-in constants; only case IDs are query parameters. Each step deletes
children before parents and checks zero remaining rows while parent IDs still
exist for indirect relationships. Foreign keys reject surviving referenced rows.
The final case-row removal prevents later child inserts. Global jurisdiction
policies and Flyway history are retained; JPA deletion jobs/attempts have no FK to
the deleted case and remain as minimal lifecycle evidence.

Ordered plan:

1. Recovery tokens, delivery requests, recovery contacts, export jobs.
2. Negotiation versions and revised-offer reviews.
3. Financing comparisons, outside finance offers, financing results, finance offers.
4. Price plans, market assessments, comparable snapshots, market-provider snapshots.
5. Cash economics, audit events, term verifications, Dealer Offers, term corrections.
6. Validation issues, quote lines, candidate terms, document extractions.
7. Document retries, processing operations, pages, source documents.
8. Vehicle conflicts, listing snapshots, dealers, vehicles, consents.
9. Reports, operations, the case itself.

Before removal, same-case `previous_offer_id` links are nulled to support the
self-referencing immutable offer chain. This occurs only inside the deletion
transaction. Every step records aggregate affected rows; the job stores total
rows and graph duration in milliseconds. Bulk SQL avoids loading sensitive JSON
and unbounded ORM graphs. There are 35 deletion steps, 35 zero-count checks and one
self-link update per case, independent of version count. Adding a case-owned table
requires updating this plan, its order and the complete-removal fixture. The
revised-offer and finance fixtures inspect **all** application tables after
removal, so a newly introduced orphan table cannot silently escape the fixture.

## Measurement and validation

The declared objective is active-system removal within 24 hours at p95. It is an
objective, not a demonstrated production SLO. `DeletionMeasurements.snapshot(now)`
returns completed count, nearest-rank p95 completion latency, pending count,
overdue pending count, oldest pending age and the 24-hour threshold through a JPA
repository. Never assess p95 without the backlog: failed or stuck jobs must not
vanish from the denominator operationally. Monitor the scheduler and expired
cases not yet enqueued as well. The aggregate is internal, not a public case-data
endpoint. At pilot scale it scans lifecycle jobs; introduce bounded/windowed
repository projections before large-scale metrics collection.

Fixtures cover immediate case/export/recovery revocation, request replay, full
versioned revised-offer and finance graph removal, automatic case expiry, shorter
retention, and a real filesystem partial failure followed by successful restart
retry. Physical table/file checks supplement access checks because a 404 alone
cannot prove removal. Graph timings emitted by these fixtures are local H2
measurements, not PostgreSQL or production latency claims.

Local full-graph fixture measurements (2026-09-09): revised-offer history removed
137 rows in 6 ms; financing history removed 111 rows in 3 ms, each in one attempt
(H2, this workstation). Correctness, Standards and Spec review of the fixed
working-state snapshot found a missing-storage/normalized-page verification gap;
a regression fixture reproduced false completion and the check was corrected.
The explicit deletion order and documented standards review had no findings.

Ticket 19 adds finding/outcome feedback, pilot events, and experiment assignments to the
verified deletion graph. Only anonymous cohort totals remain, without Buyer, case,
Dealer, price, or variant references; see [the pilot metrics policy](pilot-metrics-policy.md).
