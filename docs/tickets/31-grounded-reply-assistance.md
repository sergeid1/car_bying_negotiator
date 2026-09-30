# 31 — Provide grounded reply assistance for a specific Dealer answer

**What to build:** When a Dealer responds, the Buyer gets a grounded reply suggestion tied to the current quote, the approved boundaries, and recorded sources, instead of a fixed template that ignores the exchange.

**Blocked by:** 15 — Provide grounded explanations and negotiation drafts; 26 — Carry the Buyer from a verified offer to the report and back; 35 — Establish the AI execution harness.

**Status:** complete — the Buyer pastes a Dealer's response and receives an editable reply built only
from the finalized report's recorded facts and the approved walk-away limits, labeled with the path
that produced it, as of 2026-09-30. The model path is implemented behind `ModelRolloutGate` and stays
off, so the template produces every reply until ticket 35's vendor and shadow-comparison obligations
are met for `REPLY_DRAFT`.

- [x] The Buyer supplies the Dealer's response as text, and the draft reply is generated against the current Dealer Offer, approved boundaries, and recorded evidence.
- [x] Every factual claim in a draft traces to a recorded source; a claim that cannot be grounded is omitted rather than asserted.
- [x] Drafts respect the Buyer-approved walk-away limit and never propose a figure beyond it.
- [x] The Buyer edits and sends every message; the product does not contact the Dealer, per specification §2.2.
- [x] Generated content cannot alter calculations, approvals, limits, or review state.
- [x] The existing template path remains available when generation is unavailable, and the Buyer can tell which one produced the text.
- [x] Automated coverage includes refusal to fabricate unsupported claims, boundary compliance, and the template fallback.

## Implementation notes — 2026-09-30

### One gate for both producers

`POST /api/v1/cases/{caseId}/reports/{version}/negotiation/replies` takes the Dealer's text (1–4000
characters) and stores nothing. `ReplyGrounds` reads citable facts and the counter ceiling from the
finalized report only. Both the template (`GroundedReply`) and the model task (`ReplyDraftTask`,
`reply-draft-prompt.v1` / `reply-draft-schema.v1`) pass through `ReplyCheck`: facts are cited by id and
rendered in product wording; model free text carrying figures, spelled-out amounts, claim-like
language, links or markup is dropped and counted; a counter above the ceiling, not below the Dealer's
figure, or without an approved limit rejects the model reply and the template is used instead.

### Walk-away ceiling

The ceiling is the selling-price walk-away limit. When an out-the-door limit exists, the counter
never exceeds the selling target, because above the target the out-the-door effect depends on tax.
An out-the-door limit with no recorded conversion allows no counter.

### Untrusted input and state

The Dealer's text is passed to the model as untrusted data and never copied into the template; the
walk-away limits are never sent to the model. The endpoint writes nothing, so generated content cannot
change calculations, approvals, limits or review state. `ProviderMeter.Operation` gains `REPLY_DRAFT`;
no migration was needed.
