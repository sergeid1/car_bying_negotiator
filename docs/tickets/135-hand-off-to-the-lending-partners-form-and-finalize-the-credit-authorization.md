# 135 — Hand off to the lending partner's form and finalize the credit authorization

**What to build:** The partner-dependent parts of the loan journey from ticket 129, as ticket 134 does for insurance.
otdog's loan application holds the amount, term, vehicle, yearly income and housing, and never a name, address, date of
birth or Social Security number. A real soft inquiry needs those, so the Buyer enters them in the lending partner's own
hosted form, and the client hands off to it. The credit authorization shipped in 129 is a draft
(`otdog-credit-authorization.draft-1`).

**Blocked by:** 123 (route and partner chosen), 130 (lender integration and executed terms), and counsel for the FCRA
authorization text.

**Status:** open — filed on 2026-10-16.

## Scope

- A hand-off step from the authorization screen to the partner's hosted form, carrying only what the partner's terms
  allow, and a way back to the pre-approvals. otdog never stores the identity details or SSN.
- Replace the draft authorization with the text counsel and the partner's terms approve; bump the version so earlier
  requests keep the version the Buyer agreed to.
- If the partner needs a fact otdog collects differently, decide whether it goes in the hosted form or otdog's
  application, and record why in `docs/provider-inventory.md`.

## Done when

- [ ] The final authorization text and version are live, and an acceptance test refuses the old version for new requests.
- [ ] A client test and the 360 px gate cover the hand-off, and a test shows no identity field or SSN reaches otdog's
      tables or logs.
- [ ] `docs/provider-inventory.md` records what the partner receives from otdog and what the Buyer gives it directly.
