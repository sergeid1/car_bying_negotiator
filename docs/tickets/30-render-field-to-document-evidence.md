# 30 — Render field-to-document evidence instead of raw JSON

**What to build:** Evidence sections show a Buyer where a number came from and how it was computed, replacing the serialized payloads currently rendered in the report and revised-offer views.

**Blocked by:** 25 — Verify and correct extracted terms, then freeze the Dealer Offer; 29 — Lead the report with the total, the questions, and the next action.

**Status:** complete — every report amount names its dealer document page and outlined region or
says plainly that none exists, calculated amounts show hand-checkable arithmetic, the revised-offer
comparison shows expected against observed with both source lines, and serialized data appears only
behind a diagnostic disclosure, as of 2026-09-30.

- [x] The serialized evidence blocks in `frontend/src/CoreDealReport.tsx` and the expected/observed evidence blocks in `frontend/src/RevisedOfferReview.tsx` are replaced with structured, labeled presentation.
- [x] Each amount resolves to its source page with the originating region indicated, or states plainly that no document source exists for it.
- [x] Each calculated amount shows its inputs and operation as readable arithmetic that a Buyer can check by hand.
- [x] Reproduction metadata remains available behind a disclosure, labeled as diagnostic rather than presented as Buyer evidence.
- [x] The revised-offer comparison shows expected against observed values as a difference, naming the two source lines and the separately calculated tax effect.
- [x] No serialized object is rendered in the default Buyer view, and a test asserts this for the report and revised-offer routes.

## Implementation notes — 2026-09-30

### Evidence is built from the frozen report, not re-derived in the client

`AmountEvidence` attaches to each material amount either a `documentSource` (document, extraction,
Dealer Offer version, wording, page and region, traced through the frozen offer terms) or a
`sourceNote` for amounts with no document behind them (financing inputs, market estimates, Buyer
limits, policy tolerances, calculated totals), plus a `calculation` of labeled operands and notes.
The fields are additive; reports saved before this change render a plain fallback line.

### Revised-offer differences

`RevisedOfferPolicy.compareCash` also returns expected and observed out-the-door totals and taxes, so
the review writes out the tax effect and the pre-tax difference as arithmetic. Tax is calculated per
offer, not per line, and the view says so. Comparisons against the Approved Baseline omit the Dealer
Offer version on the expected side because the review payload does not carry it.

### Diagnostics

Reproduction metadata and frozen inputs are rendered only after the Buyer opens a disclosure labeled
as diagnostic data; tests assert no `<pre>` or serialized object in the default report and
revised-offer views.
