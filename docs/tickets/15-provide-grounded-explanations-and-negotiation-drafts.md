# 15 — Provide grounded explanations and negotiation drafts

**What to build:** The Buyer receives prioritized plain-language explanations and editable questions or messages derived only from verified evidence and deterministic findings.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 12 — Analyze dealer financing and financed add-ons.

**Status:** Complete

- [x] Explanations consume only finalized structured facts and findings and introduce no new material claim or amount.
- [x] Each recommended question identifies the verified fact or deterministic finding that supports it.
- [x] Drafts remain editable and can be copied or downloaded by the Buyer.
- [x] Drafts do not invent competing offers, legal conclusions, dealer statements, eligibility, commitments, or guaranteed savings.
- [x] The product provides no mechanism to send a draft or otherwise contact a dealer.
- [x] Generated language distinguishes financial consistency from legal, mechanical, title, history, or purchase-safety conclusions.
- [x] Automated checks reject generated claims that cannot be matched to the finalized structured input.
- [x] Persisted explanation and draft versions reference finalized findings through JPA identifiers and projections rather than lazy entity traversal, and generated text cannot mutate its source records.

## Implementation notes

- Finalized reports atomically append an immutable language version using source
  report identifiers, scalar JPA projections and a composite foreign key.
- Controlled templates and an exact source/template gate prevent unsupported
  amounts and claims. Each question exposes its supporting finding.
- The buyer can edit, copy and download locally; generated versions remain intact.
- See the [grounded language policy](../grounded-language-policy.md) for the API,
  generation scope, persistence model and editor behavior.
- Validation: full Maven suite, frontend tests and production build passed.
