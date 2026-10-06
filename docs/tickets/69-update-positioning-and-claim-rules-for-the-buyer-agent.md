# 69 — Update positioning and claim rules for the Message Assistant

**What to build:** Buyer-facing copy, the referral partner kit, and the copy claim rules describe the
specification 2.1 positioning: the Buyer's side of the deal, as a tool that finds cars, checks quotes, drafts
the Buyer's emails to dealers, and checks desk paperwork, while the Buyer sends every email and makes every
decision. The partner kit line that the product "does not contact dealers for the Buyer" stays true and is
kept. Spec 2.1 §1.2, §1.3.

**Blocked by:** 82 — Send dealer email from the Buyer's own email app (copy should describe a send path that
exists). Re-scoped on 2026-10-05 from specification 2.0; it no longer waits on ticket 68, which is withdrawn.

**Status:** complete — positioning copy in `frontend/src` and claim rules in `PilotCopyClaimsTest` (no migration), as
of 2026-10-05. The start screen, footer, both explainer guides and the referral partner kit describe Deal X-Ray as a
tool on the Buyer's side (find cars, check quotes, draft the Buyer's emails to dealers, check desk paperwork; the Buyer
sends every email from their own email and makes every decision) and state the credo "We don't work with dealers. We
help people." The partner kit keeps "does not contact dealers for the Buyer" and adds the no-dealer-money term.
`PilotCopyClaimsTest` adds the categories negotiates-for-you, on-your-behalf, agent (our/your/AI agent, AI negotiator,
autonomous, representative) and contacts-dealers, with three reviewed negations; the mandate screen's "on your
behalf" line is reworded. Open: the dealer email relay panel is still shown until ticket 84 removes it; the
quote request's no-mandate message "to contact the dealer" is ambiguous; `DEVELOPMENT_SPEC.md` §1.0 still says the
product represents the Buyer.

- [x] Start screen, footer, guides, and partner kit describe the journey as help the Buyer uses (find, check, draft, send from your own email) and state the credo.
- [x] `PilotCopyClaimsTest` rules and reviewed phrases prohibit claims that the platform negotiates, contacts dealers, or acts on the Buyer's behalf ("negotiates for you", "on your behalf", "our agent", "AI negotiator", "autonomous"), and keep "safe to sign" and guaranteed savings prohibited.
- [x] No copy claims a capability before it is built and enabled; the connected-mailbox path is mentioned only once ticket 83 is enabled.
- [x] The footer and partner kit still state that no dealer pays for leads, placement, or referrals.
