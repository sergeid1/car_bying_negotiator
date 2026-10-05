# 73 — Make dealer relay sends safely retryable

**What to build:** An outbound dealer message whose provider response was unclear no longer leaves the Buyer stuck. Every relay send carries a stable idempotency key that the provider boundary receives. A delivery-status check settles unclear sends, and the Buyer can retry one with the same key, so the dealer never gets a duplicate. This covers the quote request, counteroffer, acceptance and test-drive messages, the open item that tickets 58, 59 and 65 left. Spec §8.10 AGT-03.

**Blocked by:** 58 — Relay dealer email without exposing the Buyer; 59 — Gate every outbound dealer message; 65 — Schedule a test drive and release contact details.

**Superseded on 2026-10-05 by specification 2.1:** relay sending is retired by ticket 77, and this retry
machinery with it. Unclear Connected-mailbox sends are handled by ticket 83 (AGT-13).

**Status:** complete — idempotent relay sends with a delivery check and Buyer retry in `com.dealanalyzer.relay`
(no migration; the relay message id is the provider idempotency key), as of 2026-10-05. Each caller implements
`RelayMessageOwner` to re-check its send and apply its first-send state once. Open: a lookup that misses an unclear
send marks it `FAILED`, so a provider with lagging lookups could report a false absence (the retry still reuses the
key); Retry is offered before the owner's permission is re-checked and can then be refused; a test-drive booking retry
is authorized by the case link alone and resends only fields the account owner already released; reschedule and
cancellation retries share the booking path but are not covered end to end.

- [x] Every outbound relay message has one idempotency key, fixed when the message is first recorded and never changed. `DealerMailRelay.send` receives it, and the fake adapter returns the original provider id for a repeated key instead of sending again.
- [x] A message in `DELIVERY_UNKNOWN` can be settled by a delivery-status lookup through the provider boundary. It becomes `SENT` when the provider has it, and becomes retryable when the provider does not. The lookup never sends.
- [x] The Buyer can retry an unsettled or not-received message from the conversation view. A retry reuses the stored message, its key, and the same gate decision. It is refused when the case is no longer accepting, the dealer opted out, or the mandate or negotiation state no longer permits the original send. Every retry is audited and counts against the existing daily caps.
- [x] The quote request, counteroffer, acceptance and test-drive records that point at a retried or settled message reach the same state as a first-time successful send, including the negotiation state transition. None of them can be sent twice.
- [x] Message content is never re-rendered on retry, and no address, subject, body or provider payload is logged. Any new columns or rows are covered by case export and deletion.
- [x] The conversation view says in plain words when delivery was not confirmed, what checking does, and when a retry is offered. It works at 360 px in both themes.
