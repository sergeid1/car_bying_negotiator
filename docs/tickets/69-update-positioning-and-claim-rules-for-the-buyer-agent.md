# 69 — Update positioning and claim rules for the Message Assistant

**What to build:** Buyer-facing copy, the referral partner kit, and the copy claim rules describe the
specification 2.1 positioning: the Buyer's side of the deal, as a tool that finds cars, checks quotes, drafts
the Buyer's emails to dealers, and checks desk paperwork, while the Buyer sends every email and makes every
decision. The partner kit line that the product "does not contact dealers for the Buyer" stays true and is
kept. Spec 2.1 §1.2, §1.3.

**Blocked by:** 82 — Send dealer email from the Buyer's own email app (copy should describe a send path that
exists). Re-scoped on 2026-10-05 from specification 2.0; it no longer waits on ticket 68, which is withdrawn.

**Status:** ready-for-agent after 82 — re-scoped on 2026-10-05 from specification 2.1.

- [ ] Start screen, footer, guides, and partner kit describe the journey as help the Buyer uses (find, check, draft, send from your own email) and state the credo.
- [ ] `PilotCopyClaimsTest` rules and reviewed phrases prohibit claims that the platform negotiates, contacts dealers, or acts on the Buyer's behalf ("negotiates for you", "on your behalf", "our agent", "AI negotiator", "autonomous"), and keep "safe to sign" and guaranteed savings prohibited.
- [ ] No copy claims a capability before it is built and enabled; the connected-mailbox path is mentioned only once ticket 83 is enabled.
- [ ] The footer and partner kit still state that no dealer pays for leads, placement, or referrals.
