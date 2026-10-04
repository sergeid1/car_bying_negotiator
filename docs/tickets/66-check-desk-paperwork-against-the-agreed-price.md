# 66 — Check desk paperwork against the Agreed Price

**What to build:** At the dealership the Buyer uploads the paperwork presented and a Desk Check compares it with the Agreed Price, leading with any difference: added fees or add-ons, a changed price, changed financing, or a different VIN. A Buyer who switches cars, or never negotiated, starts a fresh case from that car's VIN and paperwork. Spec §5.9, DESK-01, DESK-03, acceptance scenarios 23 and 24.

**Blocked by:** 63 — Accept a written quote as the Agreed Price; 14 — Review a revised Dealer Offer; 24 — Upload dealer paperwork from a phone.

**Status:** complete — a case-credential `GET /api/v1/cases/{caseId}/desk-check` compares paperwork added after
acceptance with the Agreed Price's Approved Baseline through revised-offer review, and the Desk Check panel leads
with the differences, as of 2026-10-04. Full-pipeline latency on a phone over a real network still needs field
measurement.

- [x] The Desk Check reuses revised-offer review against the Agreed Price as Approved Baseline and leads with additions and changes in plain words with their total effect.
- [x] A different VIN on the desk paperwork is a blocking finding that offers to start a fresh case for that car instead.
- [x] A fresh desk case needs no journey or account and produces the Phase 1 analysis, clearly separate from the agreed car.
- [x] The upload-to-result path is reachable in two taps from the journey on a phone and meets the Phase 1 document-review latency targets.
- [x] The Desk Check works one-handed at 360 px in both themes and is registered in `Accessibility.test.tsx`.

## Implementation notes — 2026-10-04

- `desk.DeskCheckService` is a read projection with no table. Desk paperwork is the latest source document
  uploaded after `agreed_prices.accepted_at`, sent through the existing intake, extraction and verification.
  The comparison source is, strongest first: the saved Deal Review against the agreed baseline, then
  `RevisedOfferPolicy` over the verified desk offer, then `RevisedOfferPolicy` over the terms as read. Findings
  from the last source are marked as read, not verified.
- Findings lead with a different VIN, then added charges, removed charges, changed charges, price,
  financing, trade, limits and totals. The total effect is CALCULATED from review cash deltas, QUOTED from the
  paperwork's out-the-door total, or UNAVAILABLE. It is never shown as zero.
- A different VIN is one blocking finding with no amount comparison. Verification already refuses that
  paperwork for the agreed car. "Start a fresh case for this car" opens the existing case start, prefilled with
  the VIN, the dealer ZIP and the payment method, and hands it the in-memory files. The fresh case is labelled
  separately, and the Agreed Price stays unchanged. The paperwork itself stays stored in the agreed car's case,
  because intake cannot move documents.
- The check shares the report purchase gate once an Agreed Price exists. The 402 path is unit-tested on the
  client, but no HTTP test covers it.
- Two taps are counted from an opened journey: "Open linked Buying Case", then the Desk Check camera
  control, which is scrolled into view. The comparison read has p95 under 500 ms in `DeskCheckAcceptanceTest`.
  Upload-to-result is otherwise the existing document pipeline and its Phase 1 targets.
- The endpoint and rules are in [desk-check.md](../desk-check.md).
