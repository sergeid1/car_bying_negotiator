# AI Deal Analyzer — MVP Development Specification

Status: Ready for development; public pilot gated by Section 18 prerequisites  
Version: 1.0  
Date: 2026-08-25  
Sources consolidated: `PROPOSED_CONTEXT.md`, `PROPOSED_PRODUCT_SPEC.md`, and `.scratch/ai-deal-analyzer-mvp/spec.md`

## 1. Product contract

The AI Deal Analyzer is a buyer-aligned, document-first web product for a U.S. consumer evaluating a used vehicle offered by a licensed dealer. Given a vehicle or listing, the buyer's jurisdiction, and a dealer quote, it produces a versioned, evidence-backed **Deal Analysis Report** that answers:

> What am I really paying, how does this offer compare with the market, what should I challenge, and what price boundaries should guide my next conversation?

The product uses AI to extract and explain facts. Deterministic, versioned code owns money calculations, classifications, thresholds, financial boundaries, state transitions, and review outcomes.

The product represents the Buyer. Dealer compensation, affiliate eligibility, or lead value must never influence a market assessment, rating, finding, or recommendation.

### 1.1 MVP outcome

A Buyer can:

1. Create an ephemeral Buying Case without an account.
2. identify one used Vehicle by VIN, manual facts, or a supported listing;
3. provide buyer location and dealer location;
4. upload and verify a dealer quote or buyer's order;
5. review and correct extracted Deal Terms with source evidence;
6. optionally enter a spending limit and an outside Financing Scenario;
7. approve a Deal Plan as the immutable comparison baseline;
8. receive a Deal Analysis Report with market range, deal economics, findings, price boundaries, and buyer-editable talking points;
9. upload a revised Dealer Offer and compare it with the Approved Baseline and prior offer;
10. export or delete the Buying Case.

### 1.2 MVP positioning

Position the product as an **evidence-backed second set of eyes on the dealer's actual paperwork** or “deal X-ray.” Do not market it as a legal review, autonomous negotiator, guaranteed savings product, or a declaration that a document is “safe to sign.”

## 2. Scope

### 2.1 Included

- Responsive web application.
- Used passenger vehicles sold by licensed dealers.
- One configured pilot jurisdiction; jurisdiction behavior must be versioned configuration rather than hard-coded branching.
- VIN/manual vehicle entry and a listing URL as Buyer-supplied evidence.
- Licensed vehicle, inventory, and valuation data through replaceable adapters.
- PDF, JPEG, PNG, and HEIC dealer documents within configured limits.
- Multi-page quote and buyer's-order extraction.
- Field-level confidence, provenance, verification, and Buyer correction.
- Itemized cash and financed economics.
- Transparent Market Assessment and comparable set.
- Deterministic opening, target, and walk-away boundaries.
- Initial and revised Dealer Offer comparison.
- Buyer-editable questions and negotiation message drafts; the Buyer sends them.
- HTML report and PDF export from the same immutable report payload.
- Optional manual entry of one outside financing offer.
- Ephemeral access by high-entropy case link and optional email recovery/delivery.
- Redacted operator diagnostics and separately consented manual review.

### 2.2 Excluded

- New vehicles, leases, private-party sales, auctions, motorcycles, RVs, and commercial vehicles.
- Natural-language nationwide inventory discovery.
- Dealer contact by the product, autonomous negotiation, deposits, signatures, credit applications, or binding commitments.
- Credit pulls, lender matching, loan origination, insurance quoting, or credit eligibility decisions.
- Trade-in valuation or external trade offers. If a quote includes trade terms, preserve and show them separately but do not assess their fairness.
- Definitive legal, tax, mechanical, title, accident, recall, odometer, dealer-legitimacy, or vehicle-history conclusions.
- Final-contract legal review or the phrase “safe to sign.”
- Proprietary market-value machine learning.
- Dealer rankings or cross-Buyer dealer intelligence.
- Native mobile apps, multilingual support, subscriptions, success fees, and dealer-paid leads.

## 3. Canonical language

Code, API schemas, UI copy, analytics, and tests must use these terms consistently.

| Term | Meaning |
|---|---|
| **Buyer** | The consumer considering the purchase and the only actor who approves economic limits. |
| **Buying Case** | The evidence-backed workspace for one Buyer's intended purchase of one Vehicle. |
| **Vehicle** | The physical automobile, identified by VIN when available and otherwise by confirmed attributes. |
| **Listing Snapshot** | A timestamped record of advertised Vehicle facts and price. It is immutable evidence, not current truth. |
| **Comparable** | A vehicle observation selected under a declared matching policy for a Market Assessment. |
| **Market Assessment** | A versioned market range with comparable set, adjustments, data vintage, and confidence. Never “true value.” |
| **Document Artifact** | An original uploaded file or image retained as evidence. |
| **Document Extraction** | A versioned set of candidate facts, source locations, confidence, and verification state derived from an artifact. |
| **Verified Term** | A Deal Term confirmed by the Buyer or accepted under the configured high-confidence policy. |
| **Deal Terms** | Normalized financial facts: prices, discounts, rebates, fees, add-ons, taxes, government charges, trade terms, and financing. |
| **Dealer Offer** | Deal Terms captured from a Document Artifact and verified by the Buyer. |
| **Deal Plan** | Suggested price boundaries plus Buyer-entered limits for a Buying Case. |
| **Approved Baseline** | An immutable Buyer-approved Deal Plan and Deal Terms version used for later comparisons. |
| **Deal Review** | A reproducible comparison of a Dealer Offer with a specific Approved Baseline. |
| **Discrepancy** | A missing, uncertain, or changed term found by a Deal Review. It is not automatically an error, scam, or fraud. |
| **Financing Scenario** | A reproducible calculation using specified principal, APR, term, fees, and timing. |
| **Out-the-Door Total** | Purchase charge before trade allowance, payoff, cash down, or financing: vehicle price, dealer charges, taxes, and government charges. |
| **Total Transaction Cost** | Cash paid plus scheduled financing payments and known deferred charges needed to complete and repay the purchase. |
| **Deal Analysis Report** | An immutable, versioned presentation of verified evidence and deterministic analysis for a Buying Case. |

The MVP uses **walk-away ceiling** in the UI for the maximum approved economic boundary. In the domain model it is a limit within the Deal Plan; it is never inferred from a monthly-payment preference.

## 4. Product principles and invariants

1. **Evidence before confidence.** Every material displayed fact has a source, timestamp, and confidence or is labeled missing.
2. **Deterministic money.** AI cannot calculate authoritative totals, set thresholds, approve terms, or change outcomes.
3. **Buyer control.** Only explicit Buyer action creates an Approved Baseline or changes a maximum.
4. **Immutable history.** Corrections and refreshes create versions; they never erase the original evidence or a delivered report.
5. **Separate economics.** Purchase price, financing, trade, and optional products remain visible independently.
6. **No invented precision.** Sparse, stale, conflicting, or unsupported data degrades confidence or yields an unavailable result.
7. **No unsupported accusations.** Unknown or unusual terms are questions, not claims of illegality or fraud.
8. **No false assurance.** Financial consistency does not imply legal validity, mechanical quality, or purchase safety.
9. **Fail closed for positive outcomes.** Missing or uncertain material fields prevent an unqualified positive review.
10. **Consumer alignment.** Commercial relationships cannot change ordering or analysis.

## 5. Primary workflow

### 5.1 Start a Buying Case

The Buyer provides:

- buyer ZIP code;
- dealer ZIP or address;
- purchase method: cash or finance;
- listing URL, VIN, or manual Vehicle facts;
- optional maximum selling price and maximum OTD price.

The system creates a high-entropy case link. Email is requested only for recovery, delivery, or research follow-up. Before upload, the system discloses processing, retention, deletion, optional human review, and optional product-research consent separately.

### 5.2 Establish Vehicle and listing evidence

The system normalizes VIN/provider/manual facts, captures a Listing Snapshot when licensed access supports it, and asks the Buyer to resolve material conflicts. A VIN mismatch between the listing, document, and Buyer confirmation is blocking.

Unsupported listing retrieval falls back to VIN/manual input. It must not become a hidden scraping dependency.

### 5.3 Upload and verify a Dealer Offer

Processing pipeline:

```text
validate signature/type/size
  → malware scan
  → isolated page and image normalization
  → OCR/layout extraction
  → schema-constrained document extraction
  → deterministic validation and cross-footing
  → Buyer review and correction
  → verified Dealer Offer
```

The verification screen shows original wording, normalized category, value, confidence, and page/region evidence. Material low-confidence values require confirmation. Corrections preserve the candidate value and append an audit event.

### 5.4 Assess and plan

After material inputs are verified, the system creates:

- a Market Assessment with range, comparables, adjustments, vintage, method version, and confidence;
- recomputed deal totals and reconciliation;
- finance calculations when sufficient inputs exist;
- a Deal Plan containing suggested opening and target values plus a policy-derived maximum;
- Buyer-approved selling-price and/or OTD limits.

The walk-away ceiling is the lower of the policy-derived maximum and the Buyer's applicable approved maximum. No downstream component can raise it.

### 5.5 Generate and use the report

The report leads with a concise outcome, top findings, and next action, followed by evidence and calculations. The Buyer may copy/edit suggested questions, export the report, or provide a revised Dealer Offer.

A revised offer produces a new extraction, Dealer Offer, Deal Review, and report version. It is compared line by line with both the Approved Baseline and immediately preceding offer.

## 6. Workflow state model

`AnalysisCase.status` is one of:

```text
DRAFT
DOCUMENT_PROCESSING
NEEDS_REVIEW
READY_TO_ANALYZE
ANALYZING
COMPLETE
PARTIAL
FAILED
DELETION_PENDING
DELETED
```

Allowed happy path:

```text
DRAFT → DOCUMENT_PROCESSING → NEEDS_REVIEW → READY_TO_ANALYZE
      → ANALYZING → COMPLETE | PARTIAL
```

Rules:

- Any non-deleted state may enter `DELETION_PENDING`; only verified lifecycle completion enters `DELETED`.
- Processing failure enters `FAILED` without discarding the artifact or verified case state. Retry resumes the failed stage idempotently.
- `PARTIAL` means a useful report exists but one or more analysis areas are explicitly unavailable. It is not a successful verification verdict.
- A correction to a material term invalidates dependent derived results and moves the case to `READY_TO_ANALYZE` after required verification.
- A delivered `AnalysisReport` is immutable. Regeneration creates the next version.
- Duplicate create/upload/analyze requests with the same idempotency key return the existing resource or operation.

## 7. Review and report outcomes

### 7.1 Deal Review status

- `MATCHES_APPROVED_BASELINE`: all required material fields are verified and no material discrepancy exceeds the configured tolerance.
- `REVIEW_REQUIRED`: at least one material discrepancy, limit violation, or unresolved question exists.
- `UNABLE_TO_VERIFY`: document quality, missing pages, unsupported structure, identity conflict, or missing material data prevents reliable comparison.

`MATCHES_APPROVED_BASELINE` must always be accompanied by the scope and time of checks. The UI label is “Matches approved deal,” never “safe to sign.”

### 7.2 Market/deal rating

Separately from review status, the Deal Analysis Report may show:

- `STRONG_DEAL`
- `FAIR_NEGOTIATE`
- `POOR_DEAL`
- `INSUFFICIENT_DATA`

The rating is calculated from normalized OTD economics only when OTD inputs are sufficiently complete. Otherwise, use a clearly labeled selling-price analysis or `INSUFFICIENT_DATA`. A favorable rating cannot override `REVIEW_REQUIRED` or `UNABLE_TO_VERIFY`.

### 7.3 Discrepancy severity

- `BLOCKING`: wrong/mismatched Vehicle, unreadable material amount, missing page, or calculation input that prevents reliable review.
- `MATERIAL`: exceeds the configured absolute, percentage, or category-specific threshold.
- `MINOR`: below materiality but not hidden, including rounding differences.
- `INFORMATIONAL`: no economic delta but relevant context or question.

Every Discrepancy records expected value, observed value, delta where meaningful, severity, reason code, evidence, confidence, and recommended Buyer action.

## 8. Functional requirements

### 8.1 Buying Case, access, and consent

- **CASE-01:** Create, retrieve, export, expire, and delete a Buying Case without requiring an account.
- **CASE-02:** Protect case links with at least 128 bits of entropy; optional email recovery uses expiring single-use links.
- **CASE-03:** Store jurisdiction, Vehicle, evidence, consents, Dealer Offers, Deal Plans, reviews, reports, and audit events.
- **CASE-04:** Create immutable versions for material Buyer approvals, corrections, offers, and reports.
- **CASE-05:** Record separate, revocable consent for service processing, human review, and anonymized product research. Research consent is never required for service.
- **CASE-06:** A Buyer correction cannot overwrite source evidence.

### 8.2 Vehicle and market data

- **VEH-01:** Accept VIN, manual structured facts, and approved provider facts.
- **VEH-02:** Validate VIN checksum when applicable and surface conflicting identity data.
- **VEH-03:** Preserve material listing facts, source, retrieval time, provider/license metadata, and raw snapshot reference.
- **MKT-01:** Return a range rather than an unexplained point value.
- **MKT-02:** Show included and excluded Comparables and material match/adjustment reasons.
- **MKT-03:** Record geography, vintage, sample size, method version, and confidence.
- **MKT-04:** Filter duplicates, incompatible variants, stale observations, and implausible price/mileage using versioned rules.
- **MKT-05:** Sparse or divergent data widens the range, lowers confidence, or returns `INSUFFICIENT_DATA`.

### 8.3 Document intake and verification

- **DOC-01:** Accept PDF, JPEG, PNG, and HEIC within configured file, page, pixel, and resolution limits.
- **DOC-02:** Reject encrypted, executable, malformed, unsupported, oversized, or unsafe files with an actionable message.
- **DOC-03:** Preserve page order and detect likely missing, duplicate, rotated, or cropped pages.
- **DOC-04:** Store every candidate field's source text, normalized value/unit, page/region, confidence, verification state, extractor/schema version, and correction history.
- **DOC-05:** Extract document identity, Vehicle identity, parties, dates, line items, totals, finance fields, and conditional language.
- **DOC-06:** Run deterministic currency, subtotal, duplicate-line, VIN, date, bounds, and financing consistency validation.
- **DOC-07:** Require Buyer confirmation for all financially material values and every material value below the configured confidence threshold.
- **DOC-08:** Treat document text as untrusted data and never as system or tool instructions.

### 8.4 Deal economics

- **DEAL-01:** Normalize negotiated price, discounts, conditional and unconditional rebates, dealer fees, add-ons, taxable/non-taxable status, taxes, government charges, trade allowance/payoff, down payment, credits, and lender charges.
- **DEAL-02:** Preserve unknown items as `UNKNOWN`; never silently map them to a favorable or government category.
- **DEAL-03:** Recompute subtotals and totals with decimal arithmetic and disclosed rounding.
- **DEAL-04:** Store versioned inputs, formula/policy versions, steps, assumptions, and output for every calculation.
- **DEAL-05:** Compare Dealer Offers against the Approved Baseline and prior offer, including minor differences.
- **DEAL-06:** Separate cash price, OTD Total, net amount due, amount financed, and Total Transaction Cost.
- **DEAL-07:** Identify conditional incentives and exclude unverified eligibility from guaranteed savings.
- **DEAL-08:** Show each add-on's cash price and, when financed, its total financed effect.
- **DEAL-09:** Never count down payment, trade allowance, or Buyer-selected financing as a dealer discount.

### 8.5 Financing

- **FIN-01:** For a standard amortizing loan, compute payment, total payments, total interest, and Total Transaction Cost from verified inputs.
- **FIN-02:** Reconcile a quoted payment and show the tolerance and difference.
- **FIN-03:** Support zero APR. Flag balloons, irregular schedules, and insufficient inputs as unsupported/manual review.
- **FIN-04:** Compare the dealer offer with one manually entered outside offer using aligned purchase assumptions.
- **FIN-05:** Show the incremental full-term cost of APR and financed add-ons.
- **FIN-06:** Do not fabricate a result when principal, APR, term, or required timing is missing.

### 8.6 Deal Plan and assistance

- **PLAN-01:** Calculate opening, target, and policy maximum from a versioned rule set and explicit Market Assessment inputs.
- **PLAN-02:** Calculate and label selling-price and OTD boundaries separately; convert only when jurisdiction inputs are complete enough.
- **PLAN-03:** Cap the walk-away ceiling at the Buyer's approved maximum.
- **PLAN-04:** Warn when the Buyer maximum appears incompatible with the selected Vehicle without manufacturing a lower market value.
- **PLAN-05:** Express estimated savings against a named baseline with uncertainty; never guarantee savings.
- **NEG-01:** Generate editable questions and drafts only from verified case facts and deterministic findings.
- **NEG-02:** Drafts cannot invent competing offers, legal claims, commitments, or dealer statements.
- **NEG-03:** The system cannot send a message in the MVP.

### 8.7 Report, operations, and deletion

- **RPT-01:** Present status, top three reasons, and next action before detailed analysis.
- **RPT-02:** Every material amount links to source evidence or a reproducible calculation.
- **RPT-03:** Label every analysis value as exact, estimated, or unavailable.
- **RPT-04:** State as-of time, jurisdiction coverage, sources, policy versions, limitations, and excluded considerations.
- **RPT-05:** Generate accessible HTML first; PDF is a rendering of the same immutable payload.
- **OPS-01:** Provide redacted failure diagnostics, provider status, rule/version lookup, consented review queue, and deletion verification without broad document browsing.
- **OPS-02:** Meter provider/model usage and cost by Buying Case without exposing sensitive content in logs.
- **DEL-01:** Default raw artifact retention is 30 days or less, configurable downward by the Buyer.
- **DEL-02:** Case deletion revokes access immediately and schedules removal of originals, normalized pages, OCR artifacts, exports, caches, and derived personal data.
- **DEL-03:** Record deletion progress and accurately disclose backup expiry rather than promising immediate erasure from immutable backups.

## 9. Canonical financial model

```text
vehicle subtotal
  = negotiated vehicle price
  - applicable discounts
  - verified applicable rebates

pre-tax dealer total
  = vehicle subtotal
  + dealer fees
  + add-ons and accessories

out-the-door total
  = pre-tax dealer total
  + taxes
  + title, registration, and other government charges

trade equity
  = trade-in allowance
  - trade-in payoff

net amount due
  = out-the-door total
  - trade-in allowance
  + trade-in payoff
  - cash down payment
  - other credits

amount financed
  = net amount due
  + financed lender charges

total transaction cost
  = cash paid at or before closing
  + total scheduled loan payments
  + known deferred charges
```

All money is stored as currency plus integer minor units where possible. Rates use decimal values with sufficient precision. Intermediate calculations retain full precision; rounding occurs only at a policy-declared step. Jurisdiction packs declare place, effective interval, authoritative sources, taxable categories, formulas, and rounding. Outside validated coverage, results are estimates or unavailable.

Standard fixed-rate financing uses the ordinary annuity formula, with an explicit zero-rate branch. Contract fixtures define payment timing and rounding behavior.

## 10. Data model

`AnalysisCase` (the persistence name for Buying Case) is the aggregate root.

| Record | Required responsibilities |
|---|---|
| `AnalysisCase` | Access token hash, jurisdiction, purchase method, workflow state, Buyer constraints, consent references, selected Vehicle, current report, timestamps. |
| `Vehicle` | VIN and normalized year/make/model/trim/body/drivetrain/mileage facts with source and verification. |
| `Dealer` | Public business identity and location only. |
| `ListingSnapshot` | Immutable advertised facts, URL, Dealer, retrieval time, provider/version/license metadata, raw reference. |
| `SourceDocument` | Object key, checksum, verified media type, size/pages, lifecycle, retention deadline, encryption metadata. |
| `DocumentExtraction` | Artifact, extractor/model/schema versions, status, candidate fields, confidence, provenance. |
| `QuoteLineItem` | Original label, normalized category, amount, tax/option claims, confidence, evidence, verification/correction. |
| `DealerOffer` | Immutable verified Deal Terms assembled from one or more extraction versions. |
| `MarketAssessment` | Provider values, frozen comparable set, exclusions, adjustments, range, confidence, policy version. |
| `FinancingScenario` | Principal, APR, term, timing, payment/fees, source, assumptions, calculated outputs. |
| `DealPlan` | Suggested opening/target/policy maximum and Buyer limits; approval creates an Approved Baseline version. |
| `DealReview` | Baseline version, offer version, previous offer, status, discrepancies, tolerance policy. |
| `AnalysisReport` | Immutable schema-versioned snapshot referencing all evidence and policy versions. |
| `ConsentRecord` | Purpose, policy version, grant/revoke actor and timestamps. |
| `AuditEvent` | Append-only material action metadata; no raw sensitive document text. |
| `DeletionJob` | Scope, lifecycle, attempts, completion evidence, backup-expiry metadata. |
| `ProviderUsage` | Provider, operation, request correlation, units, cost, status, license metadata. |

Material records include IDs, case ID, creation time, source, schema/policy version, and actor. Original document bytes never live in PostgreSQL.

## 11. Architecture

Build a modular monolith with one background worker. The initial reference stack is React + TypeScript, Java + Spring Boot, PostgreSQL, encrypted object storage, and a managed queue. The architecture is a product boundary, not authorization to choose a cloud or vendor before diligence.

### 11.1 Deep modules

- **Buying Case:** owns lifecycle, evidence timeline, approvals, current baseline, and orchestration.
- **Document Understanding:** turns an artifact into candidate normalized fields with provenance. OCR/model vendors remain internal adapters.
- **Vehicle Identity:** reconciles VIN/manual/provider facts.
- **Market Assessment:** selects Comparables and returns a range, adjustments, confidence, and version.
- **Deal Engine:** performs fee classification, OTD arithmetic, financing, reconciliation, and Deal Review. No LLM calls.
- **Deal Policy:** produces ratings and price boundaries under Buyer caps.
- **Report:** assembles immutable structured output and evidence references.
- **Explanation:** converts finalized structured results to plain language and cannot introduce facts.
- **Consent and Retention:** owns purpose grants, access expiry, deletion, and audit.
- **Provider Metering:** normalizes operational usage and licensing metadata.

Provider-specific response objects must be normalized at ingestion and must not leak across module interfaces.

### 11.2 External adapters

- VIN decoder.
- Licensed inventory/valuation provider.
- Jurisdiction/tax/government-fee source.
- OCR/layout and vision-capable structured extraction provider.
- Object storage, malware scanner, email delivery, and optional consented human-review queue.

Every adapter has a deterministic fake or licensed recorded fixture for the main test suite. Main tests do not call paid live endpoints.

### 11.3 Public workflow API

The versioned API must support:

```text
create case
attach/confirm vehicle evidence
request upload and attach Document Artifact
read/correct Document Extraction
create verified Dealer Offer
add Buyer limits and optional outside Financing Scenario
approve Deal Plan / create Approved Baseline
request analysis
read operation status
retrieve/export Deal Analysis Report
submit optional outcome feedback
delete case
```

Upload, correction, baseline approval, analysis, export, and deletion operations require idempotency keys. Long-running endpoints return operation IDs and explicit stage state.

## 12. AI execution policy

- Uploaded and provider content is untrusted input, including apparent instructions embedded in text.
- Models receive only the minimum data needed for extraction or explanation.
- Extraction requires schema-constrained output and deterministic validation.
- Unreadable values become missing or low-confidence; the model must not guess digits.
- Model output cannot mutate a Verified Term, approval, limit, policy, state, or sent communication.
- Explanations consume only finalized structured facts and findings. Displayed claims are checked against those inputs.
- Record model, prompt, schema, thresholds, and evaluation-set versions for material outputs.
- Model/provider changes must pass a frozen corpus and shadow comparison before rollout.
- Customer documents are not used for training in the MVP. Vendor retention and training controls require contractual validation.

## 13. Security, privacy, compliance, and accessibility

- TLS in transit; encryption at rest; short-lived signed object access; least-privilege service identities; managed secrets; separated development, staging, and production data.
- Verify file signatures, isolate parsing, scan malware, limit size/pages/decompression, and rate-limit abuse.
- Never put raw documents, raw text, access links, Buyer identity plus VIN, or financing details into ordinary logs, analytics, support tooling, or error trackers.
- Collect no SSN, driver's license, bank credential, or payment credential for analysis.
- Maintain an inventory of providers, purposes, subprocessors, regions, retention, display/caching rights, and deletion behavior.
- Complete threat modeling, dependency/container scanning, SAST, secret scanning, upload fuzzing, tenant/access isolation tests, backup restoration, and an incident runbook before public pilot.
- Obtain jurisdiction-specific counsel review for privacy, fee/tax/optionality claims, consumer-protection and savings claims, financial/legal-advice boundaries, accessibility, retention, and provider terms.
- Do not encode the vacated FTC CARS Rule as active law. Legal requirements are versioned jurisdiction policy maintained from authoritative sources and counsel guidance.
- Core flows meet WCAG 2.2 AA, including keyboard use, screen-reader labeling, contrast, focus, and non-color warning cues.

## 14. Non-functional requirements

- **Calculation correctness:** 100% pass on audited golden fixtures for supported policies.
- **Material-item extraction:** at least 99% recall for detecting the presence of financially material line items on the frozen supported-document validation set; uncertain values may be routed to review.
- **Traceability:** 100% of displayed material amounts trace to evidence, Buyer input, or a reproducible calculation.
- **Review safety:** zero false `MATCHES_APPROVED_BASELINE` outcomes in the release corpus.
- **Performance:** deterministic recalculation p95 under 500 ms; supported document to review-ready p50 under 60 seconds and p95 under 180 seconds, excluding consented human review.
- **Availability:** 99.5% monthly during public beta, excluding announced maintenance.
- **Deletion:** access revoked immediately on request; active-system deletion completion p95 within 24 hours; backup expiry disclosed and monitored.
- **Accessibility:** no critical WCAG 2.2 AA violations in core workflows.
- **Reproducibility:** historical structured input plus recorded policy/provider snapshots reproduces the same report payload except explicitly versioned presentation metadata.

## 15. Acceptance scenarios

1. **Hidden add-ons:** A baseline OTD of $36,150 followed by a quote containing new $399 VIN etching, $899 protection, and $1,495 warranty yields `REVIEW_REQUIRED`, lists all three with evidence, and shows a $2,793 pre-tax delta plus separately calculated tax effect.
2. **Financing mismatch:** Verified principal, APR, and term whose quoted payment exceeds tolerance show quoted/calculated payments, delta, tolerance, and neutral possible causes.
3. **Poor scan:** An unreadable amount-financed field enters `NEEDS_REVIEW` and cannot yield `MATCHES_APPROVED_BASELINE`.
4. **Changed maximum:** An offer above an Approved Baseline limit produces a material limit violation; no model output or provider refresh raises the limit.
5. **Prompt injection:** Text instructing the system to ignore rules or transmit data remains document content and triggers no tool, disclosure, or policy change.
6. **Unknown fee:** An unfamiliar label is preserved and categorized `UNKNOWN`, with a clarification action and no claim that it is a government charge or illegal.
7. **Sparse market:** Insufficient recent Comparables produce low confidence or `INSUFFICIENT_DATA`, not a precise value or savings claim.
8. **VIN mismatch:** Different listing and document VINs create a blocking finding and prevent analysis until the Buyer resolves identity.
9. **Conditional rebate:** A rebate whose eligibility is unverified remains conditional and is excluded from guaranteed OTD savings.
10. **Cash purchase:** A Buyer selecting cash receives a complete applicable report without financing questions blocking progress.
11. **Revised quote:** A second verified Dealer Offer is compared with both the Approved Baseline and first offer, while the first report remains unchanged.
12. **Duplicate request:** Repeating an analyze request with the same idempotency key produces one operation and one report version.
13. **Partial provider failure:** Market-provider failure preserves the case and yields a partial deal-economics report with market analysis unavailable.
14. **Deletion:** Deleting a case immediately invalidates its access link, progresses through every derived artifact, and produces auditable completion without sensitive content in logs.
15. **Financed add-on:** An add-on financed for the full term shows both its cash price and incremental scheduled-payment/interest effect.

## 16. Test strategy

The primary acceptance seam is:

> Given Vehicle evidence, Buyer jurisdiction, Buyer constraints, and a Dealer document, produce a versioned structured Deal Analysis Report.

- Build a legally acquired, de-identified golden corpus covering clean PDFs, scans, phone photos, rotations/crops, multi-page documents, annotations, conditional incentives, duplicate add-ons, negative credits, missing pages, VIN mismatch, financing layouts, and prompt injection.
- Keep train/development/test sets separate even without fine-tuning.
- Score extraction by field, weighting VIN, selling price, totals, APR, term, payment, and amount financed most heavily.
- Use property tests for cross-footing, amortization, monotonic interest behavior, caps, rounding, and the rule that credits/down payments are not discounts.
- Use table-driven jurisdiction and fee-classification tests, including unknown terminology and effective-date changes.
- Use provider contract fixtures and scheduled sandbox smoke tests for timeouts, rate limits, stale/partial data, duplicates, missing VINs, and schema drift.
- Test idempotency, retries, report versioning, unauthorized link access, retention, and deletion at the API workflow level.
- Security-test MIME confusion, decompression bombs, malicious PDFs, object-key enumeration, signed-URL expiry, redaction, rate limiting, and data isolation.
- Run automated accessibility checks plus keyboard and screen-reader walkthroughs.
- Replay historical structured cases for every deterministic engine change; require intentional report-version deltas.

Tests assert observable outputs and contracts, not prompts, private methods, class layout, or a vendor's incidental schema.

## 17. Delivery plan and gates

### Milestone 0 — Foundation and external prerequisites (2–3 weeks)

Deliver the canonical schema, jurisdiction-pack contract, golden financial fixtures, fee taxonomy, report schema, clickable flow, provider spikes, and first labeled corpus.

Gate: selected pilot jurisdiction; provider rights/cost/coverage understood; domain review passes sample deals; deterministic fixtures pass; five target Buyers can explain prototype outcomes.

### Milestone 1 — Case, intake, and extraction (2–3 weeks)

Deliver case access/lifecycle, secure upload, isolated normalization, extraction/provenance, correction, consent, retention/deletion, and corpus evaluation harness.

Gate: material extraction thresholds met on supported layouts; unsafe/unsupported inputs fail safely; privacy/security review passes for private alpha.

### Milestone 2 — Deterministic analysis (3–4 weeks)

Deliver vehicle/provider adapters, Listing Snapshots, Market Assessment, jurisdiction fee/OTD engine, finance engine, Deal Plan, Approved Baseline, revised-offer review, and report-level contract tests.

Gate: calculation fixtures pass 100%; no false positive review in release corpus; policy results reproduce from frozen inputs.

### Milestone 3 — Report and operations (2–3 weeks)

Deliver accessible mobile-first report, evidence links, explanations, Buyer-sent drafts, PDF export, metering, redacted console, feedback, outcome capture, and observability.

Gate: traceability is 100%; end-to-end latency targets met; support can diagnose failures without unrestricted artifact access.

### Milestone 4 — Hardening and pilot (2–4 weeks)

Deliver threat/security/accessibility/resilience tests, legal copy review, operator and incident runbooks, controlled rollout, and metric dashboards.

Gate: 25–50 moderated cases complete safely before a gated 100–300-case pilot; no critical privacy issues; material numeric error below 1%; quality thresholds remain met.

Expected effort: 12–16 calendar weeks for two experienced full-stack engineers plus part-time product/design, automotive expertise, security, and counsel. Provider contracting and corpus acquisition are critical-path dependencies.

## 18. Decisions and prerequisites

The following defaults resolve prior scope ambiguity:

- used dealer retail purchases only;
- analysis plus Buyer-sent drafts, not product-sent negotiation;
- one-time case pricing experiment after private alpha, not subscription;
- no required account;
- raw-document retention default no longer than 30 days;
- Buyer correction by default; human review only through separate consent;
- one initial offer plus revised-offer comparison;
- modular monolith and worker, not microservices.

Development may begin on provider-independent modules and synthetic jurisdiction fixtures immediately. The following must be resolved before claims or integrations enter a public pilot:

1. Select the pilot state and obtain counsel-approved jurisdiction rules and language.
2. Obtain written automotive provider terms covering consumer display, caching, attribution, derived values, retention, deletion, and pricing.
3. Acquire at least 100 permissioned representative dealer documents and freeze a held-out evaluation subset.
4. Have a consumer-side automotive expert validate the opening/target/walk-away policy against retrospective deals.
5. Set exact supported upload limits, materiality tolerances, extraction confidence thresholds, market freshness/sample rules, and report schema v1 fixtures.
6. Decide the private-alpha recovery mechanism and cloud/provider stack.
7. Pre-register pilot success and safety thresholds before reading pilot outcomes.

## 19. Success measures

Primary pilot measure: percentage of qualified Buyers who complete a Deal Analysis Report and make an understood decision without an unresolved material Discrepancy.

Track:

- upload → verified extraction → completed report conversion;
- time to review-ready extraction and report;
- field precision/recall, numeric exact match, correction rate, and human-review rate;
- seeded discrepancy false-positive/false-negative rates;
- comparable relevance and market-confidence coverage;
- usefulness, clarity, trust, action taken, revised-offer rate, and verified outcome delta;
- willingness to pay at one-time price variants;
- per-completed and per-failed-case provider cost;
- refunds, complaints, support contacts, deletion SLA, and privacy/licensing incidents.

Directional gate after 100–300 pilot cases: at least 70% qualified-case completion, 80% usefulness, 40% “very disappointed” among live-use respondents, material numeric error below 1%, no critical privacy event, and credible paid-conversion evidence. Final thresholds must be set before pilot results are reviewed.

## 20. Definition of done

The MVP is done when a Buyer can establish a supported Vehicle and jurisdiction, upload a supported Dealer Offer, verify every material extracted term, approve financial boundaries, receive a reproducible and traceable Deal Analysis Report, compare a revised offer, evaluate standard financing when present, export the report, and delete the Buying Case—while no AI component can alter calculations, approvals, limits, review state, or external systems.
