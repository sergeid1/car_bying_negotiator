# 31 — Provide grounded reply assistance for a specific Dealer answer

**What to build:** When a Dealer responds, the Buyer gets a grounded reply suggestion tied to the current quote, the approved boundaries, and recorded sources, instead of a fixed template that ignores the exchange.

**Blocked by:** 15 — Provide grounded explanations and negotiation drafts; 26 — Carry the Buyer from a verified offer to the report and back; 35 — Establish the AI execution harness.

**Status:** ready-for-agent

- [ ] The Buyer supplies the Dealer's response as text, and the draft reply is generated against the current Dealer Offer, approved boundaries, and recorded evidence.
- [ ] Every factual claim in a draft traces to a recorded source; a claim that cannot be grounded is omitted rather than asserted.
- [ ] Drafts respect the Buyer-approved walk-away limit and never propose a figure beyond it.
- [ ] The Buyer edits and sends every message; the product does not contact the Dealer, per specification §2.2.
- [ ] Generated content cannot alter calculations, approvals, limits, or review state.
- [ ] The existing template path remains available when generation is unavailable, and the Buyer can tell which one produced the text.
- [ ] Automated coverage includes refusal to fabricate unsupported claims, boundary compliance, and the template fallback.
