# 130 — Integrate licensed lenders for pre-approvals

**What to build:** A live `PreApprovalAdapter` for the lenders or lending marketplace chosen in ticket 123,
passing the conformance suite from ticket 128. Pre-approvals reach Buyers only after the Phase 5 gate in
spec section 17.

**Blocked by:** 123 (route and executed terms), 128.

**Status:** open — filed on 2026-10-14.

## Scope

- The same transport rules as ticket 127; selected by configuration, off by default.
- Only soft inquiries; a lender that cannot guarantee a soft inquiry is not integrated.
- Recorded fixtures for the conformance suite; main tests do not call live lenders.
- `docs/provider-inventory.md` records each lender or marketplace, data sent, retention, fee and deletion
  behaviour.
