# 133 — Decide how closely Toto must quote the guide

**What to build:** A decision, backed by ticket 122's evaluation, on whether Toto's answers must stay exact
passages of the cited guide articles. Ticket 119 added that rule beyond its own checks: every paragraph must appear
word for word in a cited article. It reliably keeps out invented figures and dealer, lender or insurer names, but
answers may read stiffly and fall back to "These guides may help" more often.

**Blocked by:** 118 (the reviewed guide) and 122 (the evaluation and the operator's grading).

**Status:** open — filed on 2026-10-16.

## Scope

- From ticket 122's frozen question set, report the fallback rate and the grades of answered questions under the
  exact-passage rule.
- If answers are too stiff or the fallback rate is too high, propose a looser check that still enforces ticket
  119's rules (cited numbers only, no names absent from cited articles, no claim terms, no contact details), for
  example: sentences drawn from cited articles with light joining words, or a code-owned list of the names the
  guide allows. Write guide articles in short passages that read well on their own, whichever rule stays.
- Any change to the rule changes the prompt version and needs a new shadow comparison (ticket 122).

## Done when

- [ ] The operator has chosen to keep or loosen the rule, with the 122 numbers behind it, recorded in
      `docs/toto-answer-policy.md`.
- [ ] If loosened: tests show invented numbers, names and claim terms are still rejected, and the shadow
      comparison passes again.
