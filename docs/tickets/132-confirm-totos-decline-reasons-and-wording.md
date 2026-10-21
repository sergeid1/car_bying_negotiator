# 132 — Confirm Toto's decline reasons and wording

**What to build:** The operator's sign-off on the six reasons Toto declines a question and the fixed wording the
Buyer sees for each. Ticket 119 shipped them as provisional (`TotoAnswerTask.DeclineReason`), taken from ticket
118's list.

**Blocked by:** the operator, as the expert reviewer. Coordinate with ticket 118, which writes
`docs/toto-answer-policy.md`.

**Status:** complete — on 2026-10-21. The operator approved all six reasons, their wording and the fallback as
shipped, except `VEHICLE_JUDGMENT`, now "I can't assess a vehicle's condition. A vehicle inspection may help."
Dealers usually give Buyers the history report, often on their websites, so Toto no longer mentions one. The reason
still covers history judgments. Only code-owned wording changed, so the prompt version is unchanged.

## Scope

- Review each reason and its wording as shipped in ticket 119:
  - `LEGAL_OR_TAX`: "I can explain buying terms. For legal or tax questions, talk with a qualified professional."
  - `WHETHER_TO_SIGN`: "The decision to sign is yours. I can explain the buying process."
  - `OWN_CASE`: "Open your report for questions about your own numbers or case."
  - `VEHICLE_JUDGMENT`: "I can't assess a vehicle's condition or history. A vehicle inspection or history report
    may help."
  - `PROVIDER_CHOICE`: "I can't choose a dealer, lender or insurer for you. I can explain how to compare offers."
  - `OUTSIDE_CAR_BUYING`: "I can help with questions about the car-buying process and otdog."
- Add, drop or reword reasons as the operator decides; the enum, the model instructions and the schema change
  together, and the prompt version changes with them.
- The fallback wording ("I can't answer that one well. These guides may help,") is reviewed at the same time.
- The final list and wording are recorded in `docs/toto-answer-policy.md`.

## Done when

- [x] The operator has approved each reason and its wording, recorded in `docs/toto-answer-policy.md`.
- [x] `TotoAnswerTask` matches the approved list, its tests cover each reason, and `PilotCopyClaimsTest` passes.
- [x] The provisional note in ticket 119's status is resolved.
