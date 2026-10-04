# 51 — Describe the car in a Shopping Brief

**What to build:** A signed-in Buyer starts a Buying Journey by describing the car they want: year range, make, model, trims, maximum mileage, price ceiling, search radius from their ZIP, and must-have and nice-to-have features. Edits create new versions. This is the input to search; no inventory is queried yet. Spec §5.6, SHOP-01.

**Blocked by:** 50 — Sign in with a passwordless Buyer Account.

**Status:** complete — Buying Journeys with versioned Shopping Briefs in `com.dealanalyzer.journey`
(migration V34), as of 2026-10-03. A journey belongs to one Buyer Account, starts at `SHOPPING`, and is reached only
through the account session at `/api/v1/journeys`. The brief form validates every field at once and names each field
and its correction; years, mileage, price ceiling, and radius have fixed bounds. Make, model, trim, and features come
from the versioned reference list `vehicle-reference-2026-10-v1`, recorded on each version; free text only filters
the list. Each saved edit inserts a new immutable version, a stale edit is refused, and earlier versions stay
readable. The ZIP resolves through `PostalJurisdictionResolver` with the same coverage as a Buying Case, and an
unrecognized, cross-state, or unsupported ZIP is explained and not stored. Journeys can be listed, opened, and deleted;
account deletion and export cover both tables. `BuyingJourneys.tsx` is in the axe sweep and the 360 px gate in both
themes. Open: the reference list is a curated starter list, not a licensed dataset; only North Carolina ZIPs can start
a journey until more policy packs exist; journey deletion is immediate because no cases are linked yet, and ticket 56
must queue linked cases on the case deletion lifecycle.

- [x] A Buying Journey belongs to one Buyer Account and has a state starting at `SHOPPING`.
- [x] The brief form validates every field and names the field and correction on error; year, mileage, price, and radius have sane bounds.
- [x] Make, model, and trim choices come from a versioned reference list, not free text; free text may only assist.
- [x] Saving an edited brief creates a new immutable version; earlier versions remain readable.
- [x] The Buyer's ZIP is resolved with the existing postal jurisdiction data; a ZIP outside supported coverage is explained, not silently accepted.
- [x] A Buyer can list, open, and delete their journeys; deletion follows the account deletion rules.
- [x] The journey screens work at 360 px in both themes and are registered in `Accessibility.test.tsx`.
