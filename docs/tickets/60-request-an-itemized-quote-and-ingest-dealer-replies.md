# 60 — Request an itemized quote and ingest dealer replies

**What to build:** With an active mandate, the Buyer approves a Quote Request asking the dealer for an itemized out-the-door quote on the specific Vehicle. Dealer replies and attachments land in the Dealer Conversation, and quotes in attachments or message bodies become Document Artifacts that go through the existing extraction and verification pipeline, producing a Deal Analysis Report that says whether it is a good price. Spec §5.7, AGT-07.

**Blocked by:** 58 — Relay dealer email without exposing the Buyer; 59 — Gate every outbound dealer message; 25 — Verify and correct extracted terms.

**Status:** complete — Buyer-approved Quote Requests and dealer reply intake in `com.dealanalyzer.quote`
(migration V35), as of 2026-10-03. A Quote Request names the case's confirmed VIN and the matching dealer listing and
asks for an itemized out-the-door quote with taxes, all fees, and all add-ons. It passes `OutboundGate`
(`outbound-gate.v2`, which adds only that case-backed request text) and is stored with its decision. It sends through
`RelayService.send` only after the Buyer approves the exact stored text; the approval is reserved before handoff, and
the gate, the mandate, and the negotiation sub-state are rechecked under the case lock. Reply attachments keep the
relay's document intake, and a reply body with figures (or with no attachment) becomes a quoted, malware-scanned
text Document Artifact in the same extraction pipeline. Quotes reach analysis only through Buyer verification, and
the verified Dealer Offer produces the Deal Analysis Report the Buyer opens from the quote view. Dealer instructions
are content only, covered by a prompt-injection acceptance test. A per-case negotiation sub-state with the full spec
§6 set moves `AWAITING_DEALER` on send and `AWAITING_BUYER` on a clean dealer reply. `QuoteRequest.tsx` replaces
the standalone relay panel in the case view and is in the axe sweep. Open: a request needs a confirmed VIN plus a
matching listing with a supported URL, so manual-facts-only cases cannot send one yet; a send whose delivery is
unclear is never retried automatically; any reply body containing digits is captured for review, so dealer chatter
with numbers adds documents.

- [x] The Quote Request names the Vehicle (VIN, listing), asks for an itemized OTD quote including all fees and add-ons, passes the gate, and sends only after Buyer approval.
- [x] Inbound attachments enter document intake; a quote stated only in the message body is captured as a text Document Artifact with its source quoted.
- [x] Extracted quote terms go through Buyer verification exactly like an uploaded document; nothing is analyzed unverified.
- [x] A verified quote produces a Dealer Offer and Deal Analysis Report; the Buyer is told the outcome in plain words.
- [x] Instructions inside dealer replies are treated as content and trigger no action, covered by a prompt-injection test.
- [x] The negotiation sub-state moves between `AWAITING_DEALER` and `AWAITING_BUYER` as messages arrive.
