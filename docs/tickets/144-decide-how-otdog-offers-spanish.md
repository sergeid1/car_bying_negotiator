# 144 — Decide how otdog offers Spanish

**What to build:** Record the product decisions for offering otdog in Spanish, and amend the specification to make
them part of it. Section 2.2 currently excludes "multilingual support". Operator decision on 2026-10-19: the app
must be usable in Spanish. Russian, French and Haitian Creole may follow; they are not part of this ticket.

Why Spanish first: Census American Community Survey data (2024, via USAFacts) puts Spanish at about 73% of
non-English home speakers in Florida (5.2M people), 79% in Texas (8.3M) and 64% in North Carolina (957k). It is the
first non-English language in every pilot state. Haitian Creole is the second in Florida (about 512k speakers) and
is the strongest candidate for a later language.

**Blocked by:** the operator, for the decisions below.

**Status:** open — operator decision, filed on 2026-10-19.

## Decisions needed

Each has a recommended default. Record the choice here and in specification version 2.6.

1. **Who reviews Spanish copy.** Machine or model translation can produce drafts, but every Spanish string a Buyer
   sees needs review by a fluent speaker before release, just as the English guide articles need operator
   review (ticket 118). Credo 1.3.5 requires the reviewer to be independent of dealers, lenders and insurers.
   *Default:* a paid professional translator plus a fluent reviewer who knows car buying.
2. **Register and variety.** *Default:* neutral US Latin American Spanish that addresses the Buyer as "usted", the
   register used on US government and bank sites. One Spanish, not one per country.
3. **Terms dealers use in English.** Many Spanish-speaking Buyers hear "out-the-door", "doc fee" and "four-square"
   in English at the dealership. *Default:* a code-owned glossary that gives the Spanish term and shows the English
   term the dealer uses next to it the first time it appears, for example "precio total final (out-the-door)".
4. **Dealer email language.** Drafts to dealers (quote requests, counteroffers, test-drive requests) go out from
   the Buyer's own email. *Default:* drafts are in English unless the Buyer picks Spanish for that dealer. The
   Buyer reads a Spanish translation of an English draft before approving it, and approves the exact text that is
   sent.
5. **Dealer paperwork.** Paperwork stays in whatever language the dealer prints. *Default:* Spanish-language
   paperwork is out of scope for extraction for now. A Spanish-speaking Buyer still sees the English paperwork's
   terms explained in Spanish.
6. **Legal documents.** *Default:* the Terms of Service and Privacy Policy get Spanish translations reviewed by
   counsel. Until then, the Spanish site links to the English versions and says so.
7. **Toto.** *Default:* Toto answers in Spanish only from reviewed Spanish articles (ticket 148). Until those exist,
   the Spanish site either hides Toto or Toto says, in Spanish, that it answers in English for now.
8. **How the language is chosen.** *Default:* the first visit follows the browser's language when it is Spanish. A
   language switch in the header ("English" / "Español", each named in its own language) overrides it. The choice is
   remembered in the browser and on the Buyer Account, and each Buying Case records the language for its emails and
   report.

## Scope

- Record the decisions in this ticket.
- Specification version 2.6: move Spanish from Section 2.2 (Excluded) to Section 2.1 (Included); add the decisions
  above; extend Section 13's accessibility line with "the page declares its language"; and note that further
  languages need a decision ticket of their own.
- Note in `docs/grounded-language-policy.md` that claim rules apply in every language a Buyer sees (ticket 145 makes
  the check enforce it).

## Done when

- [ ] Each decision above is recorded with its date.
- [ ] Specification 2.6 is merged with the changes above.
- [ ] Tickets 145–148 are updated with any decision that differs from its default.
