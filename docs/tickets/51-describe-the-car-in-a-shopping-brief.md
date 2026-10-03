# 51 — Describe the car in a Shopping Brief

**What to build:** A signed-in Buyer starts a Buying Journey by describing the car they want: year range, make, model, trims, maximum mileage, price ceiling, search radius from their ZIP, and must-have and nice-to-have features. Edits create new versions. This is the input to search; no inventory is queried yet. Spec §5.6, SHOP-01.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account.

**Status:** ready-for-agent (50 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] A Buying Journey belongs to one Buyer Account and has a state starting at `SHOPPING`.
- [ ] The brief form validates every field and names the field and correction on error; year, mileage, price, and radius have sane bounds.
- [ ] Make, model, and trim choices come from a versioned reference list, not free text; free text may only assist.
- [ ] Saving an edited brief creates a new immutable version; earlier versions remain readable.
- [ ] The Buyer's ZIP is resolved with the existing postal jurisdiction data; a ZIP outside supported coverage is explained, not silently accepted.
- [ ] A Buyer can list, open, and delete their journeys; deletion follows the account deletion rules.
- [ ] The journey screens work at 360 px in both themes and are registered in `Accessibility.test.tsx`.
