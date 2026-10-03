# 60 — Request an itemized quote and ingest dealer replies

**What to build:** With an active mandate, the Buyer approves a Quote Request asking the dealer for an itemized out-the-door quote on the specific Vehicle. Dealer replies and attachments land in the Dealer Conversation, and quotes in attachments or message bodies become Document Artifacts that go through the existing extraction and verification pipeline, producing a Deal Analysis Report that says whether it is a good price. Spec §5.7, AGT-07.

**Blocked by:** 58 — Relay dealer email without exposing the Buyer; 59 — Gate every outbound dealer message; 25 — Verify and correct extracted terms.

**Status:** blocked on 58 and 59 — filed on 2026-10-03 from specification 2.0.

- [ ] The Quote Request names the Vehicle (VIN, listing), asks for an itemized OTD quote including all fees and add-ons, passes the gate, and sends only after Buyer approval.
- [ ] Inbound attachments enter document intake; a quote stated only in the message body is captured as a text Document Artifact with its source quoted.
- [ ] Extracted quote terms go through Buyer verification exactly like an uploaded document; nothing is analyzed unverified.
- [ ] A verified quote produces a Dealer Offer and Deal Analysis Report; the Buyer is told the outcome in plain words.
- [ ] Instructions inside dealer replies are treated as content and trigger no action, covered by a prompt-injection test.
- [ ] The negotiation sub-state moves between `AWAITING_DEALER` and `AWAITING_BUYER` as messages arrive.
