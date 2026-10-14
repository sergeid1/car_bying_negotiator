# 127 — Integrate a licensed insurance quote partner

**What to build:** A live `InsuranceQuoteAdapter` for the partner chosen in ticket 123 (a licensed
marketplace or otdog's own agency carrier connections), passing the conformance suite from ticket 125.
Quotes from the partner reach Buyers only after the Phase 5 gate in spec section 17.

**Blocked by:** 123 (route and executed terms), 125.

**Status:** open — filed on 2026-10-14.

## Scope

- HTTPS only, timeout, bounded retries, body size cap, no redirects off host, credentials from managed
  secrets; selected by configuration, off by default.
- Only the fields the partner's terms require are sent, and only for providers the Buyer selected.
- Recorded fixtures for the conformance suite; main tests do not call the live partner.
- `docs/provider-inventory.md` records purpose, data sent, region, retention, fee and deletion behaviour.
