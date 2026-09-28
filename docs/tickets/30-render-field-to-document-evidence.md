# 30 — Render field-to-document evidence instead of raw JSON

**What to build:** Evidence sections show a Buyer where a number came from and how it was computed, replacing the serialized payloads currently rendered in the report and revised-offer views.

**Blocked by:** 25 — Verify and correct extracted terms, then freeze the Dealer Offer; 29 — Lead the report with the total, the questions, and the next action.

**Status:** ready-for-agent

- [ ] The serialized evidence blocks in `frontend/src/CoreDealReport.tsx` and the expected/observed evidence blocks in `frontend/src/RevisedOfferReview.tsx` are replaced with structured, labeled presentation.
- [ ] Each amount resolves to its source page with the originating region indicated, or states plainly that no document source exists for it.
- [ ] Each calculated amount shows its inputs and operation as readable arithmetic that a Buyer can check by hand.
- [ ] Reproduction metadata remains available behind a disclosure, labeled as diagnostic rather than presented as Buyer evidence.
- [ ] The revised-offer comparison shows expected against observed values as a difference, naming the two source lines and the separately calculated tax effect.
- [ ] No serialized object is rendered in the default Buyer view, and a test asserts this for the report and revised-offer routes.
