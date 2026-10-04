# 58 — Relay dealer email without exposing the Buyer

**What to build:** Each Buying Case gets a relay address. The platform can send email to a dealer and receive replies and attachments through an email relay adapter, without ever exposing the Buyer's address or identity. Every message identifies the platform as acting for a Buyer and as AI-assisted and carries a dealer opt-out. This ticket builds the channel; sending negotiation content is ticket 61. Spec AGT-03, AGT-04, AGT-11, §13.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account; 57 — Approve a Negotiation Mandate.

**Status:** ready-for-agent (50 and 57 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] A replaceable relay adapter sends and receives email; a deterministic fake drives the main test suite; the real provider is selected under specification §18 prerequisite 10 and is not enabled by this ticket.
- [ ] Relay addresses are unguessable per case and stop accepting mail when the case or account is deleted.
- [ ] Outbound messages carry a fixed footer identifying the platform as acting for a Buyer, AI-assisted, and an opt-out; a dealer opt-out permanently blocks that relay identity and is shown to the Buyer.
- [ ] No outbound message can contain the Buyer's email, phone, or surname unless released (ticket 65); a test scans rendered messages.
- [ ] Inbound mail is size-limited, malware-scanned, and stored as untrusted content; attachments enter the existing document intake.
- [ ] Outbound volume is capped per dealer and per case; send attempts and deliveries are metered.
- [ ] Message bodies follow the artifact retention and deletion policy and never enter ordinary logs.
