# 70 — Enable delegated negotiation within the mandate

**What to build:** A Buyer can opt in, per mandate, to delegated mode, in which gated messages within the mandate send without per-message approval, once supervised-mode evidence meets the pre-registered gate. Spec AGT-06, Phase 3 delegated gate.

**Blocked by:** 61 — Negotiate in supervised mode with a full conversation view; 69 — Update positioning and claim rules for the Buyer Agent.

**Status:** withdrawn on 2026-10-05 — specification 2.1 excludes delegated or automatic sending: every message
reaches a dealer only after the Buyer approves its exact text (AGT-06). Ticket 84 removes the autonomy mode.

- [ ] Thresholds (unchanged-approval rate, gate-escape count, volume) are frozen before supervised outcomes are reviewed and recorded against.
- [ ] Delegated mode is opt-in per mandate with plain-words consent and can be switched back to supervised at any time.
- [ ] Delegated sends still pass the gate, never exceed the ceiling, and never send acceptance or contact-release messages, which always need the Buyer.
- [ ] The Buyer is notified of every delegated send and can stop negotiation instantly.
- [ ] A kill switch disables delegated mode service-wide without affecting supervised mode.
