# 57 — Approve a Negotiation Mandate

**What to build:** Before any dealer contact, the Buyer approves an immutable Negotiation Mandate for a Buying Case: target, walk-away ceiling, permitted disclosures, autonomy mode, and expiry. The ceiling can never exceed the Deal Plan's walk-away ceiling. Revocation is immediate. Nothing is sent yet. Spec §5.7, AGT-01, AGT-02, AGT-06.

**Blocked by:** 10 — Approve immutable price boundaries; 26 — Carry the Buyer from a verified offer to the report and back.

**Status:** ready-for-agent — filed on 2026-10-03 from specification 2.0.

- [ ] A mandate proposes target and ceiling from the existing Deal Plan; the Buyer may lower either, and a ceiling above the Deal Plan's walk-away ceiling is refused.
- [ ] Permitted disclosures are chosen from a fixed list (for example: paying cash, has outside financing, first name, timeline); nothing outside the list can be disclosed.
- [ ] Autonomy mode is `SUPERVISED`; `DELEGATED` exists in the schema but is refused until ticket 70.
- [ ] Approval creates an immutable version with actor and time; changing anything requires a new approval; revocation and expiry take effect immediately and are audited.
- [ ] A mandate needs a Buying Case with an available Market Assessment; without one the Buyer is told why.
- [ ] The mandate screen states in plain words what the platform may and may not do, works at 360 px in both themes, and is registered in `Accessibility.test.tsx`.
