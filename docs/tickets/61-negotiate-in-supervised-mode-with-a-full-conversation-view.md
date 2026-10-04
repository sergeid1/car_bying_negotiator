# 61 — Negotiate in supervised mode with a full conversation view

**What to build:** The platform drafts counteroffers from verified facts and deterministic boundaries, each draft passes the gate, and the Buyer approves, edits, or declines it before sending. The Buyer sees the whole conversation: drafts, gate decisions, sent and received messages, and delivery status. Spec §5.7, AGT-06, AGT-08, NEG-03, acceptance scenario 21.

**Blocked by:** 60 — Request an itemized quote and ingest dealer replies; 31 — Grounded reply assistance.

**Status:** complete — supervised counteroffers and the conversation view in `com.dealanalyzer.quote` and
`com.dealanalyzer.negotiation` (migration V41), as of 2026-10-04. `Counteroffer` (`supervised-counteroffer.v1`) drafts
from the latest report's grounded language; its only figure is the mandate target, proposed only below the verified
selling price, within the ceiling and the report's boundary. Drafts and Buyer edits are stored as gated revisions;
only the exact latest revision can be approved and sent through the relay. Entering STOPPED, PAUSED, AGREED or
EXPIRED cancels queued counteroffer drafts; mandate revocation and recorded expiry settle state in the same
transaction, and a test shows sends refused within one second of revocation. `NegotiationConversation.tsx` shows
drafts, gate decisions, messages, attachments and delivery status in order. Open: reaching the target cancels drafts
and stops counteroffers without moving the case to STOPPED, so the Buyer can still accept; draft quote and acceptance
requests are not cancelled on stop (their sends refuse); the spec's "dealer declines to move" stop is not
implemented.

- [x] Drafts are generated only from verified case facts, the latest Deal Analysis Report, and mandate values, extending ticket 31's grounded reply assistance; amounts come from deterministic boundaries.
- [x] No message leaves without Buyer approval; an edited draft is re-gated before sending.
- [x] The conversation view shows every draft, gate decision, outbound and inbound message, attachment, and delivery status in order.
- [x] Negotiation stops automatically when a verified quote is at or below target, when the mandate expires or is revoked, or when the Buyer stops it; queued drafts are cancelled.
- [x] Revoking a mandate blocks sends within one second (acceptance scenario 26).
- [x] The view works at 360 px in both themes and is registered in `Accessibility.test.tsx`.
