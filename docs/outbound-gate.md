# Outbound draft policy

`OutboundGate.VERSION` is `outbound-gate.v1`. The gate has no model, I/O, or clock. Its trusted
context contains an active mandate, verified money values in that mandate's currency, verified
disclosure facts, known personal values, values released for this specific dealer, and an optional
finalized core report. HTTP clients supply only `dealerId` and `text`, never gate context.

The policy accepts paragraphs separated by exactly two newlines. Each paragraph must be one of:

- `Could you provide a current itemized written offer?`
- `Is there flexibility in the written selling price?`
- `Could you confirm the full payment schedule and full-term cost in writing?`
- `Would you consider USD 23000.00 as the selling price in a revised written offer?`
  (replace the currency and decimal amount with a traceable mandate or verified value).
- A question produced by the unchanged `GroundedLanguage.generate` from the finalized report.
- `I am paying cash.` or `I have outside financing.`, when both permitted and verified.

Every price must match the mandate currency, equal its target or ceiling or a verified case amount,
and be at or below its ceiling. Other representations, including spelled-out and obfuscated money,
fail closed. Unknown prose, appended assertions, indirect commitments, encoded text, unsupported
personal disclosures and competing-offer claims fail even when keyword detectors do not recognize
them. The keyword checks provide specific reasons; template matching is the enforcement boundary.
This deliberately limits free-form wording. A new template needs a policy version and corpus update.

`OutboundDraftService` stores immutable draft, decision, gate version and policy context snapshots
in V33's `outbound_drafts`, independently of the relay migration. Its controller provides POST,
GET list and GET by ID under `/api/v1/cases/{caseId}/outbound-drafts`, with case-token authorization
and idempotent POST requests. The dealer must belong to the case. Drafts are exported and deleted
with the case. No operation sends a message.

Known personal values include the Buyer's postal code, recovery email and attached account contact
values. Account contacts are decrypted only inside the account privacy boundary. Context snapshots
retain mandate terms, verified amounts/disclosures, the personal-value count and report fingerprint;
they do not duplicate contact values. The rejected draft itself is retained so the Buyer can review it.

The service currently authorizes no personal releases or profile disclosure facts. First name and
purchase timeline have no templates in v1. Ticket 65 can extend verified facts and dealer-scoped
releases; permission alone never establishes a fact. The standalone `OutboundGateDecision`
component renders the stored decision and all reasons; ticket 61 mounts it.

A stored pass describes the saved context only. Future send orchestration must load the current
context under the case lock, run the gate again for the exact text and dealer, enforce Buyer approval
in supervised mode, and then call the relay. Revocation, expiry and Deal Plan replacement must stop
sending even if an older saved decision passed. Never treat a saved pass as a send capability.

The frozen adversarial resource `backend/src/test/resources/outbound/adversarial-drafts.json`
runs in `OutboundGateTest` in the normal Maven suite. Add examples to this file as new attacks appear.

Supervised counteroffers also stop when the dealer declines to lower the selling price. The
deterministic rule compares each of the last `negotiation.dealer-declined.rounds` sent counteroffers
(default 2) with its next verified Dealer Offer and the verified offer used to draft it. Every round
must have a distinct verified response in the same currency with a selling price that did not fall.
A pending response, missing price or currency cannot establish the condition. A later verified price
reduction resets the rule's rounds and clears a rule decision. Dealer prose and unverified extraction
play no part. The Buyer can separately record “the dealer won't move” and undo that decision; only
the Buyer clears a Buyer decision, and undo still applies the deterministic rule if it holds.

The conversation view and counteroffer checks settle this condition under the case lock. Proposed
drafts are cancelled with `DEALER_DECLINED`; fresh counteroffers and sends are refused while it holds.
The case remains active so written-quote acceptance and the Buyer's stop action remain available.
Decisions record their source, configured rounds and verified price in case-scoped `audit_events`,
including Buyer undo and rule clearance. That existing graph is exported and erased with the case
(including account-linked case export/deletion); no new table or migration is required.

Relay sends are safely retryable (ticket 73). The relay message id, fixed when the outbound row is
first inserted, is the provider idempotency key: `DealerMailRelay.send(key, mail)` returns the
original provider id for a repeated key, and `lookup(key)` reports whether the provider has the
message without sending. A `FAILED` or `DELIVERY_UNKNOWN` refusal carries the message id, and the
quote request, counteroffer, acceptance or test-drive row records it. `POST
/api/v1/cases/{caseId}/relay/messages/{messageId}/delivery-check` settles an unclear send by lookup
only. `.../retry` accepts `FAILED` and `DELIVERY_UNKNOWN` messages, looks up first, and resends the
stored ciphertext with the same key only when the provider lacks it; nothing is re-rendered. Each
record type implements `RelayMessageOwner`: a retry re-runs the owner's original send checks (gate,
mandate, negotiation state, test-drive appointment and contact release) under the case lock, after
the case-accepting and dealer opt-out checks and before the daily caps, which count the retry. When a
message settles as `SENT`, its owner applies the first-send state change once, including the
negotiation transition, while negotiation can still reach that state. Test-drive text stays
authorized for the gate while its message is `FAILED` or `DELIVERY_UNKNOWN`. Every check and retry
writes a `RELAY_DELIVERY_CHECKED` or `RELAY_RETRY` row to case-scoped `audit_events` holding only
the outcome. That graph is already exported and erased with the case; no migration was needed.
