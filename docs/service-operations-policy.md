# Restricted service operations

Ticket 18 adds an operator HTTP surface. Operator credentials are independent of
Buyer case credentials. It is disabled until accounts are configured. Provision
one account per person through deployment secrets, with a stable non-sensitive
identifier (letters, digits, hyphens and underscores; at most 64 characters):

```properties
operations.accounts.support-alex.token=${SUPPORT_ALEX_TOKEN}
operations.accounts.support-alex.role=SUPPORT
operations.accounts.reviewer-sam.token=${REVIEWER_SAM_TOKEN}
operations.accounts.reviewer-sam.role=REVIEWER
```

Use independent random tokens of at least 32 characters, sent only in the
`Authorization: Bearer …` header over HTTPS. Do not put credentials in URLs or
source control. Remove/rotate the deployment credential to remove operator
access. Accounts have one role; neither role is accepted as a Buyer credential.

## Support views

All paths below start with `/api/v1/operator`. Every collection is bounded to
100 rows. Case histories return recent rows; the review queue returns oldest
eligible requests first. There is no bulk document export or artifact URL in
this surface.

| Method and path | Result |
| --- | --- |
| `GET /cases/{caseId}` | Analysis/document stages, operation correlation IDs, safe failure categories, current policies and report-specific policy snapshots |
| `GET /cases/{caseId}/usage` | Provider calls, units, costs, status and registered provider/model/schema/license metadata |
| `GET /providers` | Observed call counts/status, units, known costs, known-cost counts and last observation, grouped by provider/version/operation/status |
| `GET /cases/{caseId}/audit` | Review access and Buyer verification audit metadata |
| `GET /cases/{caseId}/deletion` | Deletion progress, attempt outcomes and verification evidence, including after case removal |

These routes require `SUPPORT`. Provider observations measure actual calls;
they are not proactive health probes. Totals cover retained cases and disappear
when those cases are deleted. Compare `callsWithKnownCost` with `calls` before
interpreting `knownCostUsd` as a total.

Provider usage is durably started before the call and finished independently of
the calling transaction. `STARTED` without a finish means an interrupted or
still-running call, not a successful call. Provider exceptions are
`PROVIDER_FAILURE`, declared unavailability is `PROVIDER_UNAVAILABLE`, and
schema/contract validation failures are `CONTRACT_FAILURE`. Document and analysis
stages preserve these categories without forwarding exception causes. Unsupported listing
input is `BUYER_INPUT`; internal operation failures have their own category.
Neither exception messages nor remote metadata are copied into telemetry.

The code-owned adapter catalog identifies the currently shipped local
extraction, listing and market providers. Their cost is exactly zero. Units are
attempted pages for extraction and attempted requests otherwise. Unregistered
adapters have unknown model/version/license/cost metadata (`costUsd: null`),
never an invented zero price. Register reviewed version, license and billing
metadata before adding a paid adapter. The schema field records the expected
contract; failure status identifies drift without echoing an untrusted schema
string.

Report policy metadata is copied when each immutable report is written. Reports
created before migration V20 expose an empty policy map; the operator query
never opens an old report payload to backfill it. Pre-migration failed analysis
operations carry `UNCLASSIFIED` rather than an inferred failure category.

## Separately consented human review

A Buyer uses their normal `Case-Access-Token` to:

1. Grant `HUMAN_REVIEW` using the existing `/api/v1/cases/{caseId}/consents` API.
2. `POST /api/v1/cases/{caseId}/human-reviews` with `extractionId` and
   `candidateId`. The candidate must belong to that extraction and case.
3. `GET /api/v1/cases/{caseId}/human-reviews` to inspect review progress and
   completed outcomes.

A review request authorizes one immutable extracted candidate field. It does
not authorize the document original, other fields, financing history, other
extractions, reports or case access credentials. Requests require current
service-processing and human-review consent, an active unexpired case and an
active unexpired source document. Human review remains optional for service use.

Only `REVIEWER` accounts can use these operator paths:

| Method and path | Result |
| --- | --- |
| `GET /reviews` | Eligible, unstarted review metadata |
| `POST /reviews/{id}/start` | Assign the request to the authenticated reviewer; return expiry |
| `GET /reviews/{id}/content` | That field's original wording, normalized value/unit, page and confidence |
| `POST /reviews/{id}/complete` | Save an outcome: `CONFIRMED`, `NEEDS_BUYER_CORRECTION` or `UNRESOLVED` |

Completion takes `{"outcome":"NEEDS_BUYER_CORRECTION"}`. Outcomes do not change
Buyer-verified terms or approve a deal. No free-text operator notes are stored.
Every successful assignment, content read and completion records the configured
reviewer identity, request ID, case ID, action and time. The audit view contains
no reviewed values.

A grant lasts at most 15 minutes. `operations.review-duration` may shorten that
interval. Every read and completion rechecks reviewer identity, state, expiry,
consent and case/document eligibility. Completed and expired grants cannot be
restarted. A Buyer may submit a new request if another review is needed.
Consent mutations and review access serialize on the same case-row lock.
Changing human-review consent invalidates existing queued and started requests;
revoking service consent does likewise. Later consent grants cannot revive old
requests because each request is tied to its original consent version.
Deletion or expiry also denies access immediately. Already delivered content
cannot be recalled.

## Data and logging boundaries

Support diagnostics, usage, review metadata, audit and deletion queries use
explicit scalar JPA projections. They never materialize case/document/report
entities. The sole sensitive operator read is the separately authorized,
one-field content projection. Acceptance tests inspect emitted SQL to enforce
this separation, in addition to testing HTTP authorization and response bodies.

API responses use `Cache-Control: no-store`, `Referrer-Policy: no-referrer` and a
server-generated `X-Correlation-Id`. Framework parse/validation errors and
unexpected exceptions receive generic responses; exception messages and
rejected values are not logged by the handler. Raw request logging, servlet
access logs, SQL display, SQL bind logging and database exception logging are
disabled in application defaults. The repository does not configure an external
analytics or error-tracking SDK.

Apply the same policy to reverse proxies, APM, support tools and future analytics:
never capture request/response bodies, authorization/case headers, recovery or
access links, uploaded documents, extracted text, identity-plus-VIN or financing
values. Feed those tools the redacted operator projections and correlation IDs.
Do not enable framework body/SQL logging to investigate production failures.

Provider usage, review requests and review audits are part of the complete
case-graph deletion plan. A late provider completion updates only its existing
usage record; it cannot recreate deleted metadata. Deletion tombstones and
attempt evidence retain their existing policy, excluding the Buyer receipt
credential. Backup expiry remains explicitly unknown unless verified separately.
