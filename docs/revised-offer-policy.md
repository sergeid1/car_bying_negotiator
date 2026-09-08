# Revised Dealer Offer review v1

Upload each revised document through the existing authenticated
POST `/api/v1/cases/{caseId}/documents` workflow. The same file validation,
encrypted storage, extraction, evidence display, correction, and explicit verification
requirements apply. POST `/dealer-offers` appends the next verified offer.
Recalculate cash economics for that offer, then POST `/analyses` with an
Idempotency-Key to generate its report and Deal Review atomically.

Reports for offer version 2 onward include `analysis.dealReview`.
GET `/deal-reviews/latest` and `/deal-reviews/{version}` return saved reviews
with Case-Access-Token authorization. Report retries return the same operation.
A new analysis key appends a new report and review; historical versions remain
unchanged. The case lock serializes version allocation. Scalar database foreign
keys reference the Approved Baseline and previous/current offers, with no ORM
cascades; reviews and offers are immutable JPA entities.

The comparison records the latest Approved Baseline and the immediately preceding
verified offer, even when that offer has no report. Candidates match by field;
line items match by normalized label and occurrence. Duplicate labels remain
distinct. Renaming a charge conservatively produces a missing and added pair.
Evidence identifiers do not count as term changes. Each difference includes both
frozen evidence records, extraction confidence when available, expected and
observed values, units, numeric delta when comparable, severity, reason and Buyer
action. Unverified values and unknown classifications remain visible even when
unchanged. Original extraction confidence is retained after Buyer verification.

Every difference is shown. Materiality is USD 1.00 or 0.1 APR percentage points;
other term, classification, currency, missing and added changes are material.
Materiality never suppresses a row. Every excess over any applicable approved
walk-away or Buyer maximum is a material violation, with zero tolerance.
Both quoted and recomputed amounts are checked; a lower calculation cannot clear
a higher dealer quote. No
review changes an approved ceiling.

Cash summaries compare complete pre-tax OTD totals and separately calculated
taxes, so hidden fees, add-ons and changed discounts all contribute. They retain
both calculation policies and frozen inputs. Missing or incompatible calculations
are unavailable, never zero; unresolved classifications still prevent verification.
Tax differences may also reflect policy or eligibility changes, which remain in
the saved evidence. Financing terms in the document are compared; this review
does not approve a financing schedule or introduce financing limits.

The core report remains conservative: semantic equality alone does not clear
stale evidence, unknown charges, missing approval, reconciliation failures or
other existing review requirements. The report UI shows both comparisons,
including informational changes, uncertainty, evidence and Buyer actions.
