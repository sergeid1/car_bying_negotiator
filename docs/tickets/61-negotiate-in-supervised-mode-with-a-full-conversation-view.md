# 61 — Negotiate in supervised mode with a full conversation view

**What to build:** The platform drafts counteroffers from verified facts and deterministic boundaries, each draft passes the gate, and the Buyer approves, edits, or declines it before sending. The Buyer sees the whole conversation: drafts, gate decisions, sent and received messages, and delivery status. Spec §5.7, AGT-06, AGT-08, NEG-03, acceptance scenario 21.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies; 31 — Grounded reply assistance.

**Status:** blocked on 60 — filed on 2026-10-03 from specification 2.0.

- [ ] Drafts are generated only from verified case facts, the latest Deal Analysis Report, and mandate values, extending ticket 31's grounded reply assistance; amounts come from deterministic boundaries.
- [ ] No message leaves without Buyer approval; an edited draft is re-gated before sending.
- [ ] The conversation view shows every draft, gate decision, outbound and inbound message, attachment, and delivery status in order.
- [ ] Negotiation stops automatically when a verified quote is at or below target, when the mandate expires or is revoked, or when the Buyer stops it; queued drafts are cancelled.
- [ ] Revoking a mandate blocks sends within one second (acceptance scenario 26).
- [ ] The view works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
