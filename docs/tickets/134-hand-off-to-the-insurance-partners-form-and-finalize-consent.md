# 134 — Hand off to the insurance partner's form and finalize consent

**What to build:** The remaining partner-dependent parts of the insurance journey from ticket 126. otdog's
application stores rating facts only (driver age, ZIP and state, the car, coverage), not name, date of birth,
licence number or street address. If the partner chosen in ticket 123 needs those, the Buyer enters them in the
partner's own hosted form, and the client hands off to it. The consent text shipped in 126 is a draft
(`otdog-insurance-consent.draft-1`).

**Blocked by:** 123 (route and partner chosen), 127 (partner integration and executed terms), and counsel for the
consent text.

**Status:** open — filed on 2026-10-16.

## Scope

- If the partner uses a hosted form: a hand-off step from the consent screen to the partner's form, carrying only
  what the partner's terms allow, and a way back to see the quotes. otdog never stores the identity details.
- Replace the draft consent with the text the partner's terms and counsel approve; bump the consent version so
  earlier requests keep the version the Buyer agreed to.
- If the partner needs a fact otdog already collects in another form (for example date of birth instead of age),
  decide whether it goes in the hosted form or otdog's application, and record why in
  `docs/provider-inventory.md`.

## Done when

- [ ] The final consent text and version are live, and an acceptance test refuses the old version for new requests.
- [ ] If a hosted form is used: a client test and the 360 px gate cover the hand-off, and a test shows no identity
      field reaches otdog's tables or logs.
- [ ] `docs/provider-inventory.md` records what the partner receives from otdog and what the Buyer gives it directly.
