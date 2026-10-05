# 72 — Stop negotiation when the dealer declines to move

**What to build:** Supervised negotiation stops sending counteroffers when the dealer declines to move, the one stop condition in spec §5.7 that ticket 61 left out. The rule is deterministic: no model reads dealer messages to decide it. The Buyer sees why counteroffers stopped and can still accept the latest written quote or stop the negotiation. Spec §5.7, AGT-06.

**Blocked by:** 61 — Negotiate in supervised mode with a full conversation view; 63 — Accept a written quote as the Agreed Price.

**Status:** complete — a deterministic dealer-declined rule and Buyer-recorded decision in `com.dealanalyzer.quote`
(no migration; decisions are case-scoped `audit_events`), as of 2026-10-05. Open: the rule reads USD selling prices
only, like the written-quote contract; it settles when the conversation is read or a counteroffer is attempted, not
in the background; "The dealer won't move" is offered before any quote request has been sent.

- [x] The dealer has declined to move when, after each of the last N sent counteroffers, the next verified Dealer Offer did not lower the verified selling price below the one before it. N is configuration (default 2) and is recorded with the decision.
- [x] The Buyer can also record "the dealer won't move" in the conversation view; it is audited and has the same effect.
- [x] When either happens, queued counteroffer drafts are cancelled with a stated reason, new counteroffers are refused, and the conversation view shows the reason in plain words with the last verified selling price.
- [x] Like reaching the target, this does not move the case to `STOPPED`: the Buyer can still accept the latest written quote through ticket 63, or stop the negotiation.
- [x] A later verified Dealer Offer that lowers the selling price clears the condition and allows counteroffers again; a Buyer-recorded decision is cleared only by the Buyer.
- [x] The decision is derived only from verified offers, sent counteroffers and Buyer actions, never from unverified extraction or message text; any new rows are covered by case export and deletion.
