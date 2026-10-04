# 63 — Accept a written quote as the Agreed Price

**What to build:** The Buyer accepts a dealer's written, itemized quote. It becomes the Agreed Price and the Approved Baseline. If the accepted figure is not yet written and itemized, the platform asks the dealer to confirm it in writing first. The platform tells the dealer the Buyer accepts subject to a test drive and matching paperwork, never that the Buyer will buy. Spec §5.8, AGT-10, acceptance scenario 22.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies.

**Status:** complete for a Buying Case; the journey-level items wait on ticket 56 — written quote acceptance in
`com.dealanalyzer.quote` (migration V38), as of 2026-10-04. `AgreedPriceService` accepts only the latest VERIFIED,
itemized Dealer Offer that traces to a received document in this dealer's conversation; otherwise it prepares a
gated written-confirmation request. Both messages pass the outbound gate, need the Buyer's approval of the exact
text, and are sent once through the relay; the acceptance identifies the quote by VIN, out-the-door total and date
received, and is conditional on a test drive and matching paperwork, with no commitment language. After delivery
the Approved Baseline is created through `PricePlanService.approve`, and `agreed_prices` records the offer version,
BUYER actor, time, baseline, and evidence message and document; the case negotiation becomes AGREED. The Buyer can
download a one-page HTML summary to print. Case export and deletion cover both new tables. Open: Buying Cases are not
linked to journeys until ticket 56, so the journey does not become `AGREED` and no other negotiations are offered to
pause or stop yet; `JourneyAgreement` is the seam ticket 56 must implement. Acceptance needs a reviewed price plan,
which finance-only cases cannot yet produce. A crash after the provider handoff leaves the request reserved, without
automatic resend.

- [x] Acceptance requires a verified, itemized Dealer Offer from the conversation; a verbal or partial figure triggers a gated written-confirmation request instead.
- [x] The Agreed Price records the accepted offer version, actor, time, and the written evidence, and creates an Approved Baseline through the existing approval path.
- [x] The acceptance message passes the gate and contains no commitment language.
- [ ] The journey state becomes `AGREED`; other open negotiations in the journey are offered to the Buyer to pause or stop.
- [x] The Buyer can download a one-page summary of the Agreed Price to bring to the dealership.
