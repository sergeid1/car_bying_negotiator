# 63 — Accept a written quote as the Agreed Price

**What to build:** The Buyer accepts a dealer's written, itemized quote. It becomes the Agreed Price and the Approved Baseline. If the accepted figure is not yet written and itemized, the platform asks the dealer to confirm it in writing first. The platform tells the dealer the Buyer accepts subject to a test drive and matching paperwork, never that the Buyer will buy. Spec §5.8, AGT-10, acceptance scenario 22.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies.

**Status:** blocked on 60 — filed on 2026-10-03 from specification 2.0.

- [ ] Acceptance requires a verified, itemized Dealer Offer from the conversation; a verbal or partial figure triggers a gated written-confirmation request instead.
- [ ] The Agreed Price records the accepted offer version, actor, time, and the written evidence, and creates an Approved Baseline through the existing approval path.
- [ ] The acceptance message passes the gate and contains no commitment language.
- [ ] The journey state becomes `AGREED`; other open negotiations in the journey are offered to the Buyer to pause or stop.
- [ ] The Buyer can download a one-page summary of the Agreed Price to bring to the dealership.
