# 06 — Extract Deal Terms with field-level evidence

**What to build:** A supported Dealer document produces versioned, schema-constrained candidate Deal Terms whose confidence and page-level evidence can be inspected without trusting document text as instructions.

**Blocked by:** 02 — Establish golden fixtures and contract evaluation; 05 — Accept Dealer documents securely.

**Status:** complete

- [x] Extraction covers document and Vehicle identity, parties, dates, line items, totals, financing fields, and conditional language across multiple pages.
- [x] Every candidate stores original wording, normalized value and unit, page or region evidence, confidence, verification state, and extractor/schema versions.
- [x] Deterministic validation checks currency, bounds, dates, duplicate lines, VINs, subtotals, totals, and financing consistency.
- [x] Unreadable digits remain missing or low-confidence instead of being guessed.
- [x] Material low-confidence values and suspected missing pages move the case to `NEEDS_REVIEW`.
- [x] Prompt-injection text remains inert document content and causes no tool use, disclosure, approval, or policy mutation.
- [x] Evaluation fixtures report material-item presence recall and exact-value accuracy for supported layouts.
- [x] Document Extraction versions, candidate terms, line items, confidence, and evidence references are persisted through JPA repositories with bounded fetch plans that avoid loading an entire document history by default.

## Completion notes — 2026-09-02

- Added a deterministic supported-layout extractor for text-bearing dealer quotes and buyers' orders, plus a replaceable normalized-page provider boundary for future OCR/model adapters.
- Extended document processing through extraction completion and added authenticated latest, versioned, and bounded-history extraction reads.
- Added schema-constrained candidate fields, line items, page/region evidence, confidence and verification states, deterministic cross-field validation, and `NEEDS_REVIEW` routing.
- Added Flyway-managed JPA persistence for extraction versions, candidates, line items, and validation issues without broad ORM relationships or unbounded history reads.
- Added production-provider corpus evaluation and acceptance coverage for multi-page PDFs, unreadable digits, prompt-injection content, and persistence/API inspection.
- Verification passed: the complete backend suite, frontend tests, TypeScript checks, production frontend build, and `git diff --check`.
