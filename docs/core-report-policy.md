# Core Deal Analysis Report v1

POST `/api/v1/cases/{caseId}/analyses` with Case-Access-Token and Idempotency-Key
freezes the persisted Dealer Offer, cash economics, latest Market Assessment,
latest Approved Baseline, Vehicle evidence, and policy versions. The core path
does no provider I/O, completes in one database transaction, and returns a completed
operation. GET `/reports/latest` or `/reports/{version}` retrieves the saved JSON
without an open persistence session. The synthetic demonstration remains supported.

Case locking serializes version allocation. The operation key is unique, report
identity is unique by `(case_id, report_version)`, and `operation_id` is unique on
reports. The same key always returns the same operation. JPA report entities are
immutable and have no update cascade. Regeneration creates a new version and never
rewrites old evidence. Reproduction metadata includes hashes of inputs and analysis;
the report includes complete frozen inputs, tax basis, rate, rounding, classified
lines and source IDs so it does not need a live provider for reproduction.

The review policy is deliberately conservative. Missing or stale verified evidence,
changed Vehicle identity, and unknown charge/tax classifications yield
`UNABLE_TO_VERIFY`. Missing approval, unreconciled quoted totals, price-limit
violations, or any difference in the frozen terms/calculation yield `REVIEW_REQUIRED`.
Only an exact match within both approved ceilings can yield `MATCHES_APPROVED_BASELINE`,
labeled “Matches approved deal,” with scope and check time. Core materiality uses
zero term/calculation delta; the jurisdiction's recorded tolerance applies only
to quoted-total reconciliation. Revised-offer semantic comparison belongs to the
later Deal Review ticket. A report cannot clear an evidence-review requirement;
fresh verification is needed to clear it.

The rating is independent: below market low is STRONG_DEAL, within the range is
FAIR_NEGOTIATE, above market high is POOR_DEAL. It is explicitly a selling-price
comparison, not an OTD market rating. Missing market or current offer evidence yields
INSUFFICIENT_DATA. Provider unavailability produces PARTIAL with available cash
economics, never a successful verification verdict. A stale Market Assessment
for a subsequently confirmed Vehicle is unavailable.

Material amounts carry currency, precision, and a source plus JSON pointer into
frozen evidence. Verified quoted amounts and input line items are exact; derived
economics under recorded jurisdiction rules and market/boundary suggestions are
estimated. Missing amounts are unavailable, never zero. The report states sources,
as-of time, jurisdiction coverage, policies, and excluded finance, mechanical,
title, legal and purchase-safety considerations.

The UI provides outcome-first navigation, an explicit partial warning, keyboard
links to evidence, a focused report heading after loading, labeled sections, visible
focus, and responsive wrapping. The release corpus is
`backend/src/test/resources/fixtures/review/core-report-safety-corpus.json`.
