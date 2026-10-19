# 148 — Let Toto answer in Spanish

**What to build:** Toto answers in Spanish from reviewed Spanish guide articles, under the same rules as in
English (`docs/toto-answer-policy.md`): it answers only from articles, cites them, adds no amount or rule they do
not contain, and declines the same topics with fixed wording.

**Blocked by:** 144 (decision 7), 118 (the English articles must be reviewed first, so the translation does not
chase a moving text), 132 (decline wording) and 133 (how closely answers must quote the guide).

**Status:** open — filed on 2026-10-19.

## Scope

- **Spanish articles** next to the 50 English ones, in `backend/src/main/resources/toto/guide/es/`, one per English
  article with the same `id`. Front matter records `translated_from` (the English article's version),
  `reviewed_by` and `reviewed_on`. A test fails when a Spanish article is missing, unreviewed, or translated from an
  older English version.
- **Answering.** The client sends the Buyer's current language. Toto retrieves and cites Spanish articles and
  answers in Spanish. Fixed decline messages and the "I'm an AI guide" line have reviewed Spanish versions. A
  question asked in Spanish while the site is in English gets a Spanish answer when Spanish articles exist.
- **Checks.** The answer check (ticket 119) applies the Spanish claim patterns, and verifies that every amount and
  rule in the answer appears in a cited Spanish article.
- **Evaluation.** Extend ticket 122's evaluation set with Spanish questions, including the decline categories, and
  record the results before Spanish answers reach Buyers.
- Until this ticket ships, the Spanish site follows decision 7's interim behaviour.

## Done when

- [ ] Every English article has a reviewed Spanish counterpart, and the parity test passes.
- [ ] Spanish questions get Spanish answers that cite Spanish articles, and fixed declines appear in Spanish.
- [ ] The Spanish evaluation results are recorded and meet the bar set in ticket 122.
- [ ] Usage stays within ticket 120's cost caps, and no conversation is stored.
