# 144 — Decide how otdog offers Spanish

**What to build:** Record the product decisions for offering otdog in Spanish, and amend the specification to make
them part of it. Section 2.2 currently excludes "multilingual support". Operator decision on 2026-10-19: the app
must be usable in Spanish. Russian, French and Haitian Creole may follow; they are not part of this ticket.

The goal, in the operator's words: Spanish speakers need to understand what is going on in the app and how to use
it. Screens and findings come first. Dealer-facing text and the remaining open points are settled later.

Why Spanish first: Census American Community Survey data (2024, via USAFacts) puts Spanish at about 73% of
non-English home speakers in Florida (5.2M people), 79% in Texas (8.3M) and 64% in North Carolina (957k). It is the
first non-English language in every pilot state. Haitian Creole is the second in Florida (about 512k speakers) and
is the strongest candidate for a later language.

**Blocked by:** none for the decisions recorded below. The open points do not block tickets 145–147.

**Status:** open — decisions 1, 5 and 8 recorded on 2026-10-19; specification 2.6 written on 2026-10-20. Remaining:
the operator confirms or replaces working defaults 2, 3, 4, 6 and 7.

## Decided on 2026-10-19

1. **Who translates.** The implementing agent writes the Spanish copy, following decisions 2 and 3. A separate
   human review is not required before release. The operator may add one later, for example before the public
   pilot. Spanish claim rules (ticket 146) still apply, so no Spanish string can make a claim the English copy may not.
5. **Dealer paperwork.** English only. Extraction does not support Spanish-language dealer paperwork. A
   Spanish-speaking Buyer sees English paperwork's terms explained in Spanish, with each label shown as printed.
8. **How the language is chosen.** The header's existing appearance button (the moon/sun/monitor icon,
   `ThemeMenu.tsx`) opens one menu with two groups: **Theme** (System, Light, Dark) and **Language** (English,
   Español). No separate language button is added. On the first visit the language follows the browser when Spanish
   comes first. The choice is remembered in the browser and on the Buyer Account. Each Buying Case records the
   language for its emails and report.

## Working defaults, to revisit later

The operator deferred these on 2026-10-19. The implementing tickets use the default until a decision is recorded.

2. **Register and variety.** Neutral US Latin American Spanish that addresses the Buyer as "usted". One Spanish, not
   one per country.
3. **Terms dealers use in English.** A code-owned glossary that gives the Spanish term and, the first time it appears
   on a screen, the English term the dealer uses, for example "precio total final (out-the-door)".
4. **Dealer email language.** Drafts to dealers stay in English. A Spanish Buyer sees a Spanish reading copy of
   each draft and approves the exact English text that is sent. Spanish drafts to dealers are not offered yet.
6. **Legal documents.** The Spanish site links to the English Terms of Service and Privacy Policy and says they are
   in English. Translations wait for counsel.
7. **Toto.** Toto stays English-only for now (ticket 148 is deferred). On the Spanish site, Toto's panel says in
   Spanish that it answers in English for now.

## Scope

- Specification version 2.6: move Spanish from Section 2.2 (Excluded) to Section 2.1 (Included); add the decisions
  and working defaults above; extend Section 13's accessibility line with "the page declares its language"; and note
  that further languages need a decision ticket of their own.
- Note in `docs/grounded-language-policy.md` that claim rules apply in every language a Buyer sees (ticket 145 makes
  the check enforce it).

## Done when

- [x] Decisions 1, 5 and 8 are recorded with their date.
- [x] Specification 2.6 is merged with the changes above.
- [ ] Each working default is either confirmed or replaced by a recorded decision, and tickets 145–148 updated to
      match.
