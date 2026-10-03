# 54 — Integrate a licensed inventory source

**What to build:** Inventory search uses a licensed provider under executed terms, replacing the fake for pilot use, with measured coverage for the pilot area. Spec §18 prerequisite 8.

**Blocked by:** 53 — Define the inventory search adapter with a deterministic fake.

**Status:** blocked on executed inventory provider terms — filed on 2026-10-03 from specification 2.0.

- [ ] Executed terms, permitted display, caching, attribution, retention, and pricing are recorded in `docs/provider-inventory.md` before production enablement.
- [ ] The licensed adapter passes ticket 53's conformance suite against recorded fixtures.
- [ ] Coverage for the pilot area is measured with denominators (listings returned per brief, share with VIN and price).
- [ ] Per-call cost and latency are reported against the pilot budget through ticket 41's report.
