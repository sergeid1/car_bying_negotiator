# 25 — Verify and correct extracted terms, then freeze the Dealer Offer

**What to build:** The Buyer reviews every material extracted term against its source page, corrects what is wrong, and freezes an immutable Dealer Offer — the verification step the specification requires and the interface does not yet provide.

**Blocked by:** 06 — Extract deal terms with field-level evidence; 07 — Verify deal terms and create an immutable Dealer Offer; 24 — Upload Dealer paperwork from a phone and follow processing.

**Status:** ready-for-agent

- [ ] The verification view lists every material field with its extracted value, confidence, and provenance, read from the existing extraction endpoints.
- [ ] Each field displays its source page alongside the value, with the originating region indicated rather than described only in text.
- [ ] Low-confidence and missing fields require an explicit Buyer decision before the case can advance; silent acceptance is not possible.
- [ ] Corrections post to the existing corrections endpoint, record the Buyer-entered value, and preserve the original extracted value and its provenance.
- [ ] Freezing creates an immutable Dealer Offer through `POST /api/v1/cases/{caseId}/dealer-offers`; a later change creates a new version and never mutates a frozen one.
- [ ] Trade terms present in the paperwork are preserved and displayed separately, with no fairness assessment, per specification §2.2.
- [ ] The Buyer can see which fields differ between consecutive Dealer Offer versions.
- [ ] Automated coverage includes correction persistence, blocked advancement on unverified fields, version immutability, and trade-term display without assessment.
