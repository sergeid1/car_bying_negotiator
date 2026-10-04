# 66 — Check desk paperwork against the Agreed Price

**What to build:** At the dealership the Buyer uploads the paperwork presented and a Desk Check compares it with the Agreed Price, leading with any difference: added fees or add-ons, a changed price, changed financing, or a different VIN. A Buyer who switches cars, or never negotiated, starts a fresh case from that car's VIN and paperwork. Spec §5.9, DESK-01, DESK-03, acceptance scenarios 23 and 24.

**Blocked by:** 63 — Accept a written quote as the Agreed Price; 14 — Review a revised Dealer Offer; 24 — Upload dealer paperwork from a phone.

**Status:** ready-for-agent (63, 14 and 24 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] The Desk Check reuses revised-offer review against the Agreed Price as Approved Baseline and leads with additions and changes in plain words with their total effect.
- [ ] A different VIN on the desk paperwork is a blocking finding that offers to start a fresh case for that car instead.
- [ ] A fresh desk case needs no journey or account and produces the Phase 1 analysis, clearly separate from the agreed car.
- [ ] The upload-to-result path is reachable in two taps from the journey on a phone and meets the Phase 1 document-review latency targets.
- [ ] The Desk Check works one-handed at 360 px in both themes and is registered in `Accessibility.test.tsx`.
