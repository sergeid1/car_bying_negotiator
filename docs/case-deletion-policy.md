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
Amazon S3 storage inventories the same identifier prefix, including all object versions
and delete markers, with pagination. Deletion removes physical versions and markers and
rechecks their absence; denied inventory/removal leaves the deletion job pending. Writes
use the same case lock and application AES-GCM encryption as local storage, plus SSE-S3.
For ticket 99, provision a private bucket with all four bucket Block Public Access settings
enabled and **versioning never enabled**. This is the preferred deployment policy.
If versioning is enabled or suspended instead, the pilot/production startup guard requires
an enabled bucket-wide noncurrent expiration rule of one day, with no tag/size/prefix
restriction and no retained newer-version count. Suspended versioning still leaves old
versions. The adapter removes versions immediately; lifecycle expiry is a backstop, not
proof of the 24-hour p95 objective. AWS lifecycle eligibility rounds to the next UTC day
and physical expiry is asynchronous; ticket 99 must verify real removal latency, policy,
role permissions and absence of Object Lock/legal holds before live use. Object Lock or
denied version deletion keeps application deletion pending. Replicas, backups and exports
require separately declared erasure/expiry controls and are not covered by bucket deletion.
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

Ticket 42 stores the case's first-party acquisition channel code in the `analysis_cases` row, so
it is removed with the case row and needs no separate step. Before the graph is erased, the plan
adds the case to `acquisition_channel_totals`, an anonymous per-channel counter of removed case
starts, qualified cases and qualified uploads, keyed only by channel code (`''` for no channel).
Like the cohort totals, it holds no case, Buyer, Dealer, document or time reference. Channel
configuration and operator-entered spend are global operator records, not case records.
# Purchase records (ticket 44)

V27 case_purchases and case_purchase_requests belong to the case and are erased by CaseGraphDeletionPlan, including
request keys, offer and price, checkout state, refund time and operator. Before erasure,
purchase_totals retains anonymous removed case, paid purchase and refund counts; the existing
acquisition_channel_totals retains removed paid counts by channel. No purchase ID, case ID,
operator, timestamp, or price survives in these totals. Deletion retries cannot double-count:
counter updates and graph erasure share the deletion transaction.

## Contribution measurements and support time (ticket 46)

V28 `case_support_time` stores only case ID, an idempotency entry UUID and integer
minutes. It belongs to the case and is explicitly erased before the case row.
Support writes and refunds serialize with deletion on the owning case row.
The internal `case_contribution_facts` SQL view reads source scalar facts only and
retains nothing independently; case removal also removes the view's case row.

Before any graph rows are erased, `contribution_totals` retains an anonymous
distribution of removed cases, grouping identical tuples with a case count: UTC calendar day of case start, qualified/verified/
completed flags, real-paid/refunded/development counts, revenue and full refund minor
units and currency, summed known provider/extraction USD cost, unknown-cost call count,
and summed support minutes. It has no primary key, case, purchase, support entry,
Buyer, Dealer, document, channel, variant, operator or precise timestamp reference.
Each anonymous observation contributes its case count to totals; the distribution preserves
the exact median, unknown-cost exclusions and recalculation under versioned pricing.
Unlike earlier counters, monetary values and a UTC day intentionally survive here to
meet the contribution measurement requirement. No price offer/experiment identity or
individual support record survives. Only aggregate reports are exposed to SUPPORT.
Small distributions remain sensitive to outside knowledge and must not be used to
reidentify a Buyer; the service does not expose rows through an operator endpoint.

Usage rows are locked before capture to serialize independently committed meter
completions with erasure. Insertion and all graph removal share one transaction: failures roll both back and
retries cannot double-count. All operations in `provider_usage`, including failed calls
and retries, contribute before removal. Missing cost remains unknown. Older pre-V28
anonymous purchase counters cannot reconstruct dates or financials and are explicitly
reported as unavailable historical coverage, never backfilled or imputed. Buyer
exports and downloaded copies retain their existing scope; this measurement adds no
external retained dataset or backup-erasure claim.

## Negotiation Mandates (ticket 57)

V30 `negotiation_mandates` and `negotiation_mandate_revocations` belong to the case. Each row holds
the approved target, walk-away ceiling, permitted disclosure codes, expiry and approval or
revocation time. Mandates approved before ticket 84 also keep the autonomy mode they were approved
with; since V45 new mandates store none, because the platform never sends a dealer message
itself. CaseGraphDeletionPlan erases revocations, then mandates, before the
Deal Plan snapshots they reference. Approval, revocation and expiry audit events are erased with
`audit_events`. Nothing about a mandate is kept in any anonymous total. Both tables are included
in the case export.

## Dealer messages and the reply-intake address (tickets 58, 82 and 84)

V32 `dealer_relay_identities`, `dealer_relay_messages`, `dealer_relay_attachments` and
`dealer_relay_send_attempts` belong to the case. Since ticket 84 the platform sends no dealer email:
the rows are the case's private reply-intake address, messages the Buyer handed off and sent from
their own email app (`send_path = 'OWN_EMAIL_APP'`), dealer replies the Buyer pasted or forwarded,
and messages the former platform relay sent before ticket 84 (`send_path = 'RELAY'`), which stay
listed, exported and erased exactly like the rest. No new send attempts are recorded. The
reply-intake address stops accepting mail as soon as the case is being deleted, has expired, or its
Buyer Account is being deleted; mail to it is then refused unstored, exactly like mail to an address
that never existed.
CaseGraphDeletionPlan erases attachments, send attempts, messages and the identity first, before the
source documents the attachments point to. Message subjects and bodies are AES-GCM ciphertext and are
erased at the case's document retention deadline (`dealer-relay.retention-sweep`, default `PT5M`);
delivery metadata stays until case deletion. A dealer opt-out recorded on a relay identity before
ticket 84 is kept with the identity and goes with the case; new addresses have no opt-out. Nothing is kept in any anonymous total. Metadata from all four
tables is in `case-data.json`, and retained message content is in `relay/messages.json` of the
case export, and so of the account export.

## Trade-in and written trade offers (ticket 100)

V50 `trade_vehicles`, `trade_offers` and `trade_case_journeys` hold the Buyer's trade-in. A Trade
Vehicle belongs to its Buying Journey (`journey_id`, `account_id`) or, for a case without a journey,
to that case (`case_id`). A written trade offer's photo or PDF is a `source_documents` row in the case
it was uploaded through, with `purpose = 'TRADE_OFFER_EVIDENCE'`: it takes the ordinary inspection,
malware scan, encryption, page normalization and retention, is never extracted for Deal Terms, and is
never listed or compared as dealer paperwork. `trade_case_journeys` links a case started at the Desk
Check for another car to the journey whose trade it uses.
CaseGraphDeletionPlan erases the case's offers (including journey offers whose evidence is in the
case), its Desk Check journey link and a case-held Trade Vehicle before the source documents.
Journey deletion and account deletion erase the journey's Trade Vehicle, offers and links, verify
their absence, and bring the offers' evidence retention deadline forward so the ordinary retention
sweep erases the files; removing a single offer or the whole trade-in does the same. The case export
holds the trade the case uses, its offers and its link; the account export holds each journey's
trade-in. Nothing is kept in any anonymous total.
