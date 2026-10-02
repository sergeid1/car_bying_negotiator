# Optional feedback and pilot measurement

Ticket 19 implements DEVELOPMENT_SPEC.md §19. It provides measurement infrastructure;
tickets 21–22 still own jurisdiction approval, pilot qualification, final preregistration,
release evidence, paid collection, and the pilot release decision.

## Buyer feedback

The saved report's explanations offer three independent answers: accurate,
understandable, and useful (`YES`, `NO`, `UNSURE`).
`POST /api/v1/cases/{id}/feedback/findings` accepts a report version and a
`findingPointer` from that version's saved grounded-language items. Arbitrary report
pointers and absent versions are rejected. No finding prose is copied into feedback.

The separate outcome form and `POST /api/v1/cases/{id}/feedback/outcomes` accept
`REVISED` or `FINAL` stage and any optional subset of action, selling price, OTD,
USD currency, APR, term, understood decision, usefulness, trust, live use,
disappointment, and willingness to pay. At least one answer is required; omitting
feedback entirely is always permitted. Monetary inputs use minor units. A response
with terms is explicitly `BUYER_REPORTED_UNVERIFIED`, never verified savings or a
replacement Dealer Offer. There are no free-text, contact, VIN, listing, or Dealer fields.
Willingness to pay requires a saved experiment assignment; the Buyer can view the
assigned one-time price before answering. It does not make a purchase or authorize a charge.

Both endpoints and `GET /api/v1/cases/{id}/feedback` require `Case-Access-Token`.
Finding feedback is unique per case/report/finding; outcomes are unique per case/stage.
Identical retries return the saved response; changing an existing response returns
409. The UI explains that submissions are final. A new report version can receive a
new finding rating, and revised and final outcomes remain separate. Unknown JSON
fields are discarded rather than persisted. Framework errors use the existing redacted
problem schema. Case writes serialize with deletion and consent changes on the case row.

## Freeze and enroll

Every `/api/v1/operator/pilot` endpoint requires a configured `SUPPORT` operator
Bearer credential. Buyer credentials and reviewers cannot use these endpoints.
There is no raw outcome or dealer-intelligence endpoint for operators.

`POST /freeze` freezes the following protocol once, recording the operator and timestamp:

```json
{
  "completionMinimum": 0.70,
  "usefulnessMinimum": 0.80,
  "veryDisappointedMinimum": 0.40,
  "numericErrorMaximum": 0.01,
  "criticalPrivacyMaximum": 0,
  "variants": {"A": 1900, "B": 2900}
}
```

These are the spec's directional thresholds and **illustrative price variants**, not
approved pilot prices. Operators must supply their approved values before enrolling
any pilot cases. Fractional thresholds are in [0,1]; material numeric error is strictly
below the frozen maximum; critical privacy incidents must be zero. Two or three
variant codes (`A`, `B`, `C`) accept USD minor-unit prices. `GET /protocol` returns the
frozen definition and first review timestamp. There is no update/unfreeze endpoint.
A later pilot requires a separately designed protocol/version migration, not editing
this protocol after observing results.

`POST /cases/{id}/enrollment` with `{"qualified": true}` records that the operator
has checked the separately approved pilot qualification criteria. Enrollment is
rejected after a report or outcome exists to prevent selecting cases based on results.
It assigns one variant uniformly using server randomness, persists it, and returns the
same assignment on retry. This is qualification attestation, not automatic jurisdiction
or release approval. Price/affiliate eligibility is absent from assessment, Deal Rating,
GroundedLanguage, comparable selection, and recommendation inputs. CoreReportService
only emits finalized status telemetry; it never reads assignments or feedback.

`GET /metrics` and `GET /research` return 409 before freeze and record the first review
when enabled. No automatic gate declares a pilot ready or successful.

## Metric definitions and evidence

`pilot-metrics.v1` includes numerators, denominators, and fractional rates. Zero
denominators produce `UNAVAILABLE` with a null rate, never a passing zero-error result.
Completion includes all enrolled cases, including anonymous completion counters recorded
at deletion. Feedback measurements use retained respondents; deleted response attrition
is visible in `cohort.deleted`. Repeated reports do not multiply case denominators.
Unless specified below, workflow metrics describe retained data, not historical totals
before retained records existed. Core report status events begin with this migration; no sensitive report payloads are read for backfill. Synthetic completions use a separate event type and are excluded from qualified core-report completion.

| Output | Definition / source |
| --- | --- |
| qualifiedCompletion | Qualified cases with at least one COMPLETE core report / all enrolled cases. PARTIAL does not count. |
| understoodDecisionWithoutUnresolvedDiscrepancy | Completed qualified cases with a final understood-decision response, current complete/unblocked case, and latest review matching the approved baseline / all enrolled cases. This is the conservative primary measure; self-report alone cannot clear a discrepancy. At deletion, the qualifying state is counted anonymously. |
| uploadToVerifiedExtraction | Retained qualified cases with a verified offer / retained qualified cases with an accepted upload. |
| verifiedExtractionToReport | Retained qualified cases with a verified offer and COMPLETE report / retained qualified cases with a verified offer. |
| latency | Event sample count, mean, and continuous p95 milliseconds. Extraction runs from accepted upload to persisted extraction; report runs from core analysis entry to persistence; provider and error duration use their recorded timestamps. Includes retained service cases, and deletion receipts. |
| correctionRate | Retained qualified cases with a correction / retained qualified cases with extracted data. It is a case incidence measure, not field accuracy. |
| humanReviewRate | Retained qualified cases with a human-review request / all enrolled cases; deletion attrition is reported separately. |
| findingRatings | Joint counts of accurate, understandable (clarity), and useful responses; one rating per report/finding. |
| marketCoverage | Retained qualified cases whose latest report has market evidence / retained qualified cases with a report. |
| marketConfidence | Latest assessment per retained qualified case, grouped by confidence and availability. |
| usefulness, trust | Final YES responses / final answered responses among retained qualified cases, including UNSURE in the denominator. |
| veryDisappointed | Very-disappointed final respondents / final disappointment respondents reporting real purchase use. Non-live use is excluded. |
| actionTaken, revisedOfferRate | Final action counts; retained qualified cases with more than one verified offer / all enrolled cases. |
| willingnessToPay | Final yes/no counts by frozen variant and one-time price; survey evidence, not paid conversion. |
| providerCost | Calls, calls with known cost, and summed known USD cost by status. |
| providerCostPerCompletedCase / providerCostPerFailedCase | Case count, call count, known-cost call count, total known cost, and mean known cost for currently COMPLETE / FAILED retained service cases. Unknown cost remains null; partial cost is not an all-in cost estimate. |
| deletion | Counts, mean and p95 completion time by state, completed fraction within 24 hours, and overdue pending count. Backup expiry is not counted as verified deletion. |
| verifiedMeasurements | Explicitly recorded evaluation and operational rates described below. Missing batches remain unavailable. |
| verifiedOutcomeDelta | Number of batches with signed USD delta, total baseline-minus-final delta in minor units, and matched verified-case denominator. Missing deltas remain null. Buyer-reported terms are excluded. |

`POST /observations` accepts an immutable aggregate evidence batch, for example:

```json
{
  "evidenceId": "b6d24dcc-1b78-4674-bb2c-e393f329f401",
  "metric": "NUMERIC_EXACT_MATCH",
  "numerator": 99,
  "denominator": 100,
  "source": "LABELED_EVALUATION"
}
```

Evaluation sources must use `LABELED_EVALUATION`: FIELD_PRECISION (correct observed
fields / observed fields), FIELD_RECALL (correctly recovered / expected fields),
NUMERIC_EXACT_MATCH (exact numeric fields / expected numeric fields),
MATERIAL_NUMERIC_ERROR (material numeric errors / evaluated material numeric fields),
SEEDED_DISCREPANCY_FALSE_POSITIVE (false positives / true negative seeds),
SEEDED_DISCREPANCY_FALSE_NEGATIVE (missed discrepancies / positive seeds), and
COMPARABLE_RELEVANCE (relevant / evaluated comparables). Use frozen labeled fixtures
and the existing extraction/review evaluation harnesses; production corrections are
not a substitute for labeled truth.

VERIFIED_OUTCOME_IMPROVEMENT (cases with lower comparable final costs / verified matched
cases), PAID_CONVERSION (paid / offered cases), REFUND (refunded / paid cases), COMPLAINT
and SUPPORT_CONTACT (affected / qualified cases), CRITICAL_PRIVACY_INCIDENT and
LICENSING_INCIDENT (affected / audited cases) require `VERIFIED_PILOT_RECORDS`.
Only VERIFIED_OUTCOME_IMPROVEMENT may include optional signed `totalDeltaMinor`, the
sum of verified baseline-minus-final OTD changes in USD minor units. Do not claim that
the product caused a change. A zero-incident batch must still include an audited-case
denominator. These records are operator attestations, not automatic adjudication.

Use a distinct evidence UUID for each non-overlapping batch/metric and keep the source
artifact under the controlled evaluation/release evidence process; do not upload it to
telemetry. Identical UUID retries do not double count; changed retries return 409.
Do not submit cumulative overlapping batches. Aggregate observations carry only numeric
counts, controlled metric/source enums, operator identity, and time; no document values,
Dealer identities, or Buyer identifiers. Paid conversion is unavailable until actual
paid evidence is supplied. Frozen thresholds should be reviewed against these explicit
rates, their sample sizes and missing evidence, not proxy metrics.

## Redacted storage, projection and deletion

Flyway V21 adds JPA repositories for findings, outcomes, experiment assignments,
protocols, observations, and pilot events. `pilot_workflow_events` is a fixed SQL view
mapped by an immutable JPA entity/repository. Its schema contains an internal correlation
ID, case ID, fixed event type/category, duration and time. It covers report completion,
review status, market coverage, feedback, corrections, verified-extraction conversion,
uploads, extraction completion, processing errors, provider usage and deletion. It
never selects original wording, normalized corrected values, provider payloads,
idempotency keys, access tokens, filenames, contact details or Dealer identities.

`PilotMetrics` uses documented static native aggregate queries and the workflow JPA
projection. It runs under repeatable-read isolation and never hydrates complete Buying
Case graphs. Percentiles use `percentile_cont`; elapsed durations use SQL epoch
extraction. These operations run in the database, not by loading rows into Java.
Returned operator metrics contain no case identifiers or raw terms. Quality evidence
uses sums of numerators and denominators, not averages of percentages. Case-cost
queries aggregate per case before computing a mean, avoiding duplicate case denominators
from provider joins. Tests inspect SQL and responses at the public metrics boundary.

The Buyer ZIP export includes all their finding/outcome/event/assignment records;
credentials remain excluded. Deletion erases and verifies those tables as part of the
case graph in the same transaction. The globally aggregated cohort counters retain only
all-enrolled, removed, removed-completed and removed-understood counts, without a
case, variant, Buyer, Dealer, price or document reference. They cannot be joined back
to a case. This prevents denominator loss from inflating completion after deletion.
Other deleted feedback does not survive as a secondary dataset. Global frozen protocol
and independently supplied aggregate evaluation evidence are not Buyer graph records.

## Separate research consent

Operational measurement is part of service operation; it does not grant research use.
The research endpoint recomputes aggregate final usefulness responses only for active,
unexpired cases with the **latest** ANONYMIZED_RESEARCH and SERVICE_PROCESSING decisions
granted. Decline, revocation, expiration or deletion removes eligibility. Consent is not
inferred from feedback or willingness to pay. No case-level research export, Dealer
ranking, cross-Buyer Dealer intelligence, materialized research table or external
analytics transmission exists. Granting consent does not change assessment inputs.

Ticket 29 measures report comprehension with an optional total-entry check. Each report mount opens
an authenticated session tied to the immutable report version. The server compares the entered USD
minor units (or an explicit unknown answer) with that version's leading out-the-door amount. Incorrect
answers retain no amount and leave the session open; the first correct answer records elapsed browser
monotonic time since report mount, capped at 24 hours. Repeated correct submissions do not add samples.
`reportTotalIdentification` reports opens, correct identifications, mean and p95 milliseconds over
retained sessions, including partial reports and repeat opens. Opens without a correct answer are in
the denominator but not latency samples. These are optional, client-timed comprehension checks, not
independently observed purchase outcomes. Session events follow existing case deletion/retention;
submitted amounts and edited question text are never retained in pilot events.

## Market and listing budget and jurisdiction measurement (ticket 41)

`GET /api/v1/operator/evidence/metrics?from=<ISO instant>&to=<ISO instant>` requires the existing
`SUPPORT` Bearer credential. Reviewers and Buyer case credentials cannot read it. Responses have
`Cache-Control: no-store` and carry no case, Buyer, Dealer, VIN, postal code, listing URL, document
identifier or provider payload. No new retained dataset, external analytics or release decision is
created. Static SQL aggregate projections run in one repeatable-read transaction; deletion removes
source records, so measurements describe retained service records, not historical totals.

`evidence-metrics.v1` states a half-open window (`fromInclusive`, `toExclusive`), selected by record
start/creation time. An attempt begun before the window is excluded even if it finishes within it.
Calls from all retained service cases count; pilot enrollment and freeze are not prerequisites.

| Output | Numerators and denominators |
| --- | --- |
| providers | Per operation (`MARKET_RETRIEVAL`, `LISTING_RETRIEVAL`), provider and status: calls, calls with known cost, calls with unknown cost, summed known USD cost, and mean cost over known-cost calls. Latency sample count, mean and continuous p95 milliseconds use finished calls. Unknown cost is never zero; no known cost yields null sums/means. |
| observed | Combined market/listing calls, distinct cases with calls, known/unknown cost counts, summed known cost, mean known cost divided by distinct cases with calls, and maximum known per-case total. `observedCostPerCaseUsd` (mean) and `maximumObservedCostPerCaseUsd` are null unless every call is priced. Retries add costs but do not multiply case denominators. Partial known totals remain explicitly labeled. |
| budget and comparisons | No default for `evidence.budget.per-case-cost-usd` or `evidence.budget.p95-latency-millis`. Both are required by pilot/production startup; development reports `UNSET` when either is missing. Cost comparison uses the maximum observed combined per-case total, so averaging cannot hide an overage. It is `NOT_MEASURABLE` for missing cost ceiling, unknown cost or no calls. Latency comparison is `NOT_MEASURABLE` for missing latency ceiling, unfinished calls or no calls. Otherwise each independently returns `OVER` or `WITHIN` (equality counts as within). No automatic release decision follows. |
| casesAttempted | Per distinct jurisdiction policy effective before window end: distinct retained cases with a market call, assessment or `MARKET_COVERAGE` event in the window. Missing assessment attempts remain in the denominator. Multiple policy versions do not multiply cases. Jurisdictions with no attempts report zero counts. |
| assessment coverage | One latest assessment version in the window per attempted case. `casesWithAssessment` is the status denominator: `assessmentsAvailable`, `assessmentsUnavailable`, `assessmentsTooThin`. `casesAttempted` is the denominator for `casesWithAssessment` and `casesWithoutAssessment`. |
| report evidence coverage | One latest `MARKET_COVERAGE` event in the window per case (time, then internal ID breaks ties). `reportEvidenceAvailable` and `reportEvidenceUnavailable` use `casesWithCoverageEvent` as denominator; `casesAttempted` is the denominator for having an event. An unavailable report event can coexist with a stored available assessment, for example when it has expired. |
| comparable selection | `comparablesIncluded` and `comparablesExcluded` use `comparablesEvaluated` as denominator, over the provider snapshots of the selected assessments only. `assessmentSampleCount` sums their accepted sample sizes; `casesWithAssessment` is its assessment denominator. These are counts of stored selection decisions, not independently verified relevance. Labeled relevance remains the separate `COMPARABLE_RELEVANCE` observation measure. |

These figures describe **development adapters** until ticket 27 integrates a licensed provider.
Fixture costs and sample coverage do not establish a licensed source's pricing or coverage. No budget
or release threshold is invented here; the deployment owner must supply approved ceilings.
