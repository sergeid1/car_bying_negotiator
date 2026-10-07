# Outbound draft policy

`OutboundGate.VERSION` is `outbound-gate.v4`. The gate has no model, I/O, or clock. Its trusted
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

A stored pass describes the saved context only. Hand-off must load the current context under the
case lock, run the gate again for the exact text and dealer, require the Buyer's approval of that
text, and only then hand it to the Buyer to send. Revocation, expiry and Deal Plan replacement must stop
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

Relay sends were made safely retryable by ticket 73; ticket 84 retired the platform relay and that
retry machinery with it (see below).

## Sending from the Buyer's own email app (ticket 82)

The platform does not write to a dealer in its own name (spec 2.1 §2.1). After the owner record's send
checks pass under the case lock, `DealerMessageHandoff.handOff` is the one step every approved message
takes: the quote request, counteroffer, acceptance or written-confirmation request, and test-drive
proposal, booking, rescheduling and cancellation. On the `OWN_EMAIL_APP` send path it stores the exact approved subject and text, encrypted, as an outbound
`dealer_relay_messages` row in state `HANDED_OFF` with `send_path = 'OWN_EMAIL_APP'` and no relay
identity. Nothing is added: no footer, opt-out line, relay address or platform name. The Buyer
identity screen still refuses any Buyer email, phone or surname other than values the Buyer released
to this dealer, and the gate's own checks are unchanged. The owner record moves to `HANDED_OFF`, not
`SENT`, and no negotiation or appointment state changes yet.

`GET /api/v1/cases/{caseId}/handoffs/{messageId}` returns the address, subject and body for the
Buyer's mail app (a `mailto:` link, or Copy when the link would exceed about 2,000 characters).
`POST .../sent` is "I sent it": under the case lock it re-runs the owner's
`RelayMessageOwner.requireResendPermitted` checks (mandate, gate decision on the exact stored text,
negotiation and appointment state, test-drive contact release), marks the message `SENT` with the
time, and calls the owner's `sent`, which applies the first-send state change, once. A second press, a discarded message, or a mandate that was revoked or expired changes
nothing and returns 409 with the reason. `POST .../discard` moves the message and its owner record to
`DISCARDED`. Nothing is sent or retried automatically; a handed-off message stays "Not sent yet" until
the Buyer acts. Both actions write a `DEALER_MESSAGE_MARKED_SENT` or `DEALER_MESSAGE_DISCARDED` row to
case-scoped `audit_events` holding only the send path. Test-drive text stays authorized for the gate
while its message is `HANDED_OFF`.

`POST /api/v1/cases/{caseId}/quote-requests/{id}/replies` (multipart `text`, optional `subject`,
`file` parts, `Idempotency-Key`) adds a dealer reply the Buyer pasted or attached to a Dealer
Conversation whose request is marked sent. `RelayService.receivePasted` stores it as an `INBOUND`,
`UNTRUSTED` message from the conversation's dealer address with `send_path = 'OWN_EMAIL_APP'`, and
the reply-intake pipeline ingests it: malware scan, attachments and quoted figures into document
intake, and the dealer-reply negotiation transition. Written-quote evidence and test-drive
confirmations read it like any other dealer reply.

V44 adds `send_path` (`RELAY`, `OWN_EMAIL_APP`, `CONNECTED_MAILBOX`; existing rows keep `RELAY`),
makes `identity_id` nullable for non-relay rows, and admits `HANDED_OFF` and `DISCARDED`. The column
is in the case export, and the rows are erased with the case like every relay message.
Ticket 83 adds a `CONNECTED_MAILBOX` implementation behind the same step.

Because the Buyer sends from their own address, every platform-rendered dealer text is written in the
Buyer's own voice, with no sign of an intermediary: the conditional acceptance (`I accept your itemized
quote for VIN ...`) and the test-drive proposal, reschedule, booking and cancellation (`I'd like to
arrange a test drive ...`, `My acceptance of your quote remains subject to this test drive ...`,
`Please reply to this email with any changes.`). This changed the authorized case language, so the gate
version became `outbound-gate.v3`. The no-commitment rule is unchanged: none of these texts says the Buyer
will buy, pay, reserve or sign, any other wording still fails as unsupported language, and the
adversarial corpus has first-person commitment attempts. Drafts prepared under v2 wording keep their
stored decision. An unsent v2 acceptance no longer matches the current authorized text and has to be
prepared again. Test-drive messages stay grounded on their own stored text.

## The platform never writes to a dealer (ticket 84)

Spec 2.1 makes Deal X-Ray the Buyer's tool, not the Buyer's agent: it drafts and checks, and the Buyer
approves the exact text and sends it from their own email address. Ticket 84 removed every path by
which the platform could email a dealer in its own name. `RelayService.send`, the provider's `send`
and `lookup`, the delivery report hook, `POST .../relay/messages/{messageId}/delivery-check` and
`.../retry`, the per-case and per-dealer send caps, the platform footer ("sent this message on behalf
of a car Buyer", the AI-assistance line), the STOP reply and the dealer opt-out link
`/api/v1/relay/opt-out/{token}` are gone, and so is the `dealer-mail.send-path` property.
`DealerMessageHandoff.handOff` is the only way an approved message leaves, and on the `OWN_EMAIL_APP`
path it only stores the text for the Buyer. Ticket 83 adds `CONNECTED_MAILBOX` behind the same step
and the same `Outcome.sent` seam. `DealerRelayBoundaryTest.noOutboundRelayCallRemains` fails if a
send, retry, delivery lookup or opt-out method returns.

The gate's amount, currency, ceiling, commitment, invented-fact, disclosure and personal-data rules
are unchanged. The gate never authorized a footer or opt-out line, and its context no longer carries
an autonomy mode: the Negotiation Mandate bounds what the Buyer may approve and never lets the
platform send (V45 makes `negotiation_mandates.autonomy_mode` nullable; new mandates store none and
mandates approved earlier keep theirs). The version is `outbound-gate.v4`. Stored v3 decisions keep
their recorded version; the texts they approved still pass the same rules.

Messages the relay sent before ticket 84 keep `send_path = 'RELAY'`, their recorded state
(`SENT`, `DELIVERED`, `FAILED`, `DELIVERY_UNKNOWN` and so on) and their relay identity. They stay in
the conversation view and the case's dealer messages, and in case and account export and deletion.
Nothing offers to check, retry or resend them; owner records left in `FAILED` or `DELIVERY_UNKNOWN`
can be prepared again as a new message.

### Connected-mailbox dispatch

Connected-mailbox dispatch (ticket 90) rechecks the mandate, gate, negotiation/appointment
state and prepared contact release after token preparation, immediately before the provider call.
The active account, case and exact connection are locked, in that order, through the call.
A disconnect, replacement, account deletion, mandate revocation or release withdrawal that
wins before dispatch prevents the call, records a non-delivery attempt and leaves the own-email-app
handoff available where the case still exists. The audit records no credentials or contact values.
Once dispatch enters the provider, those changes wait for the call to return and cannot recall it.
A provider-confirmed send records `SENT`; an uncertain or unexpected failure after dispatch
records `DELIVERY_UNKNOWN` and is never automatically retried. A process interruption can also
leave the earlier claimed attempt unknown. Latch-controlled fake-provider tests in
`ConnectedMailboxAcceptanceTest` cover the fence and the already-dispatched boundary; live
provider validation and slow/crashed-send behavior remain ticket 92's scope.

### The private reply-intake address

Each case keeps its unguessable relay identity (160 random bits, issued on first use) as its private
reply-intake address, shown to the Buyer as "Forward a dealer's email here". It accepts mail only while
the case is active, unexpired, not being deleted and its account is active; otherwise mail is refused
unstored. `RelayService.receive` stores forwarded mail as an `INBOUND`, `UNTRUSTED` message with
`send_path = 'OWN_EMAIL_APP'`, keeps the whole body, including any quoted forward headers, as content,
and sends attachments and quoted figures through the existing malware scan and document intake. A
STOP line is ordinary content. The message joins the Dealer Conversation whose dealer address sent it
or, for a forward, the one conversation dealer named on a quoted `From:` line; otherwise it is listed
with its sender and joins no conversation. Addresses issued since V45 have no opt-out token.

Ticket 78's inbound mail authentication prerequisite applies before any real inbound provider is
enabled: a verified provider webhook signature or equivalent, replay protection, and binding each
message to its case's address. No real provider exists in this build. `RelayProductionGuard` refuses
the fake provider in pilot and production, where `dealer-relay.provider=none` issues addresses that
receive nothing, and the relay view's `forwardingAvailable` stays false, so the Buyer is told to paste
replies instead.
