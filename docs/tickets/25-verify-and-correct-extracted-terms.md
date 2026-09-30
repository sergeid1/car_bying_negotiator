# 25 — Verify and correct extracted terms, then freeze the Dealer Offer

**What to build:** The Buyer reviews every material extracted term against its source page, corrects what is wrong, and freezes an immutable Dealer Offer — the verification step the specification requires and the interface does not yet provide.

**Blocked by:** 06 — Extract deal terms with field-level evidence; 07 — Verify deal terms and create an immutable Dealer Offer; 24 — Upload Dealer paperwork from a phone and follow processing.

**Status:** complete — the Buyer confirms or corrects every extracted term against its outlined
source page and freezes a versioned Dealer Offer as of 2026-09-30, with a real-browser gate at 360 px.

- [x] The verification view lists every material field with its extracted value, confidence, and provenance, read from the existing extraction endpoints.
- [x] Each field displays its source page alongside the value, with the originating region indicated rather than described only in text.
- [x] Low-confidence and missing fields require an explicit Buyer decision before the case can advance; silent acceptance is not possible.
- [x] Corrections post to the existing corrections endpoint, record the Buyer-entered value, and preserve the original extracted value and its provenance.
- [x] Freezing creates an immutable Dealer Offer through `POST /api/v1/cases/{caseId}/dealer-offers`; a later change creates a new version and never mutates a frozen one.
- [x] Trade terms present in the paperwork are preserved and displayed separately, with no fairness assessment, per specification §2.2.
- [x] The Buyer can see which fields differ between consecutive Dealer Offer versions.
- [x] Automated coverage includes correction persistence, blocked advancement on unverified fields, version immutability, and trade-term display without assessment.

## Implementation notes — 2026-09-30

### Showing the source page needed two read endpoints

The ticket assumed the existing extraction endpoints were enough, but nothing let the client list a
case's documents or fetch a page image. `SourceDocumentController` adds
`GET /api/v1/cases/{caseId}/documents` (active documents, oldest first) and
`GET /api/v1/cases/{caseId}/documents/{documentId}/pages/{n}/image` (the normalized page PNG), both
behind the same access-token and service-processing-consent check as `GET /documents/{id}`. They live
outside `DealAnalysisController`, and there is no schema or migration change.
`DocumentExtractionAcceptanceTest` covers both, including a wrong token and a missing page.

### Every term needs a decision, not only the material ones

The backend requires a decision on financial and low-confidence material terms. The verification view
requires Confirm or Correct on every term, including high-confidence ones such as the dealer name, so
nothing is accepted silently; a missing value can only be corrected. The freeze button stays disabled,
with a live count of undecided terms, until every term has a decision.

### Freezing is replay-safe

The freeze idempotency key is derived from the extraction, the latest frozen version, and the confirmed
and corrected terms, so a double tap replays the same Dealer Offer while any later change creates a new
version. Earlier versions are never modified; the Buyer can compare any two consecutive versions.

### Trade terms

Trade allowance, trade payoff, and `TRADE` line items appear in their own section as written, with no
valuation or rating.
