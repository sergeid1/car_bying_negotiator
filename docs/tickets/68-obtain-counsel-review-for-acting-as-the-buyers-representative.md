# 68 — Obtain counsel review for acting as the Buyer’s representative

**What to build:** Before the platform contacts any dealer for a Buyer outside moderated testing, qualified counsel reviews, for the pilot state, whether representing the Buyer requires an auto-broker or similar licence, the agency authorization the Negotiation Mandate must express, AI-disclosure duties, and commercial-email obligations. Spec §13, §18 prerequisite 9.

**Blocked by:** 22 — Run the controlled pilot release (pilot state selection).

**Status:** withdrawn on 2026-10-05 — specification 2.1 makes the platform a tool: it drafts and checks, and
the Buyer sends every dealer email from their own address, so the platform never acts as the Buyer's
representative and sends no commercial email of its own. Pilot state: Florida. What remains moves to spec §18:
published Terms of Service and a Privacy Policy before the pilot (prerequisite 9), and a legal review before
charging Buyers, to confirm a paid tool is not a motor vehicle broker under Fla. Stat. 320.27 (open decision,
Pricing for the Message Assistant). Operator work, not agent work.

- [ ] The licensing analysis for the pilot state is recorded, with any licence obtained or the reason none is needed.
- [ ] The mandate wording, dealer-message footer, and Buyer-facing agent copy are reviewed and approved.
- [ ] CAN-SPAM obligations for relay email are confirmed; SMS and calls stay excluded until a TCPA review.
- [ ] The outcome is recorded in `docs/pilot-readiness-record.md` as a new prerequisite.
