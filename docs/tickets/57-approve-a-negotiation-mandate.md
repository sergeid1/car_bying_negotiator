# 57 — Approve a Negotiation Mandate

**What to build:** Before any dealer contact, the Buyer approves an immutable Negotiation Mandate for a Buying Case: target, walk-away ceiling, permitted disclosures, autonomy mode, and expiry. The ceiling can never exceed the Deal Plan's walk-away ceiling. Revocation is immediate. Nothing is sent yet. Spec §5.7, AGT-01, AGT-02, AGT-06.

**Blocked by:** 10 — Approve immutable price boundaries; 26 — Carry the Buyer from a verified offer to the report and back.

**Status:** complete — Buyer-approved Negotiation Mandates in `com.dealanalyzer.mandate` (migration V30), as
of 2026-10-03. The proposal takes the target and walk-away ceiling from the latest Deal Plan selling-price
boundary; the Buyer may only lower either, and the database refuses a ceiling above the plan's. Disclosures come
from a fixed list (paying cash, outside financing, first name, timeline). `DELEGATED` is refused until ticket 70.
Each approval is an immutable version with actor and time; revocation is a separate audited row and takes effect
at once; expiry applies at read time and is audited by a one-minute sweep. A mandate stops applying
(`PLAN_CHANGED`) once a newer Deal Plan replaces the one it was approved against. `MandateService.activeMandate`
is the read for tickets 58–61. Open: the mandate carries the selling-price boundary only, not an out-the-door
ceiling; the Deal Plan still needs a verified dealer offer, so a mandate does too.

- [x] A mandate proposes target and ceiling from the existing Deal Plan; the Buyer may lower either, and a ceiling above the Deal Plan's walk-away ceiling is refused.
- [x] Permitted disclosures are chosen from a fixed list (for example: paying cash, has outside financing, first name, timeline); nothing outside the list can be disclosed.
- [x] Autonomy mode is `SUPERVISED`; `DELEGATED` exists in the schema but is refused until ticket 70.
- [x] Approval creates an immutable version with actor and time; changing anything requires a new approval; revocation and expiry take effect immediately and are audited.
- [x] A mandate needs a Buying Case with an available Market Assessment; without one the Buyer is told why.
- [x] The mandate screen states in plain words what the platform may and may not do, works at 360 px in both themes, and is registered in `Accessibility.test.tsx`.
