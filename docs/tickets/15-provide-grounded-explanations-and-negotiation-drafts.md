# 15 — Provide grounded explanations and negotiation drafts

**What to build:** The Buyer receives prioritized plain-language explanations and editable questions or messages derived only from verified evidence and deterministic findings.

**Blocked by:** 11 — Generate the core Deal Analysis Report; 12 — Analyze dealer financing and financed add-ons.

**Status:** ready-for-agent

- [ ] Explanations consume only finalized structured facts and findings and introduce no new material claim or amount.
- [ ] Each recommended question identifies the verified fact or deterministic finding that supports it.
- [ ] Drafts remain editable and can be copied or downloaded by the Buyer.
- [ ] Drafts do not invent competing offers, legal conclusions, dealer statements, eligibility, commitments, or guaranteed savings.
- [ ] The product provides no mechanism to send a draft or otherwise contact a dealer.
- [ ] Generated language distinguishes financial consistency from legal, mechanical, title, history, or purchase-safety conclusions.
- [ ] Automated checks reject generated claims that cannot be matched to the finalized structured input.
- [ ] Persisted explanation and draft versions reference finalized findings through JPA identifiers and projections rather than lazy entity traversal, and generated text cannot mutate its source records.
