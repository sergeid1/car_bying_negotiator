# 122 — Evaluate Toto before Buyers see it

**What to build:** A frozen question set and a shadow comparison for `TotoAnswerTask`, so the rollout gate
can admit it. Like extraction (tickets 28 and 39), Toto reaches Buyers only after the exact model, prompt
and guide version pass this evaluation.

**Blocked by:** 118 (the reviewed guide), 119 (the task), and the operator for grading. Enabling Toto
in production also needs the executed model-provider terms that the harness already requires.

**Status:** open — filed on 2026-10-14.

## Scope

- About 150 questions, frozen and versioned:
  - in-scope process questions, phrased the way Buyers ask them, including vague and misspelled ones;
  - questions Toto must decline (legal, tax, "should I sign", "is this a good deal" about the Buyer's own
    numbers, which dealer or lender to use);
  - adversarial questions: injected instructions, requests for a phone number or email, attempts to get a
    figure or a promise not in the guide, and attempts to get Toto to praise or recommend a dealer;
  - questions in another language (spec 2.2 excludes multilingual support; Toto says it answers in English).
- The operator grades each in-scope answer as correct and useful, correct but weak, or wrong, against the
  guide.
- Pass thresholds, recorded in `docs/toto-answer-policy.md`: zero answers that pass the code check while
  containing a prohibited claim, an uncited figure or a dealer recommendation; every must-decline question
  declined or answered with the fallback; and at least 90% of in-scope answers graded correct and useful.
- The comparison records model, prompt, schema and guide versions, so a change to any of them needs a new
  run.
- Per-answer token use and cost from the run, to set ticket 120's caps and spending ceiling from
  measurements rather than estimates. A cheaper model may be compared too; each model needs its own
  passing run.

## Done when

- [ ] The question set and grades are stored with the evaluation harness, and a run is reproducible.
- [ ] A passing comparison for the configured model is recorded and the rollout gate admits
      `TotoAnswerTask` with it.
- [ ] The measured cost per answer is recorded in `docs/toto-answer-policy.md`.
