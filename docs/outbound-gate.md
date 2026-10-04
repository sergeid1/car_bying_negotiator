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
