# 68 — Obtain counsel review for acting as the Buyer’s representative

**What to build:** Before the platform contacts any dealer for a Buyer outside moderated testing, qualified counsel reviews, for the pilot state, whether representing the Buyer requires an auto-broker or similar licence, the agency authorization the Negotiation Mandate must express, AI-disclosure duties, and commercial-email obligations. Spec §13, §18 prerequisite 9.

**Blocked by:** 22 — Run the controlled pilot release (pilot state selection).

**Status:** blocked on pilot state selection and counsel engagement — filed on 2026-10-03 from specification 2.0. Operator work, not agent work.

- [ ] The licensing analysis for the pilot state is recorded, with any licence obtained or the reason none is needed.
- [ ] The mandate wording, dealer-message footer, and Buyer-facing agent copy are reviewed and approved.
- [ ] CAN-SPAM obligations for relay email are confirmed; SMS and calls stay excluded until a TCPA review.
- [ ] The outcome is recorded in `docs/pilot-readiness-record.md` as a new prerequisite.
