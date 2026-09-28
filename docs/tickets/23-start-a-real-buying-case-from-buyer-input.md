# 23 — Start a real Buying Case from Buyer-supplied vehicle and location

**What to build:** The buyer-facing entry point creates a real Buying Case from entered vehicle and location details instead of posting a hard-coded synthetic fixture, so every later step operates on the Buyer's own purchase.

**Blocked by:** 03 — Create and securely reopen an ephemeral Buying Case; 04 — Establish Vehicle and listing evidence.

**Status:** ready-for-agent

- [ ] The buyer-facing path creates a case through `POST /api/v1/cases` with recorded `SERVICE_PROCESSING` consent; `POST /api/v1/cases/synthetic` is removed from that path and remains reachable only behind an explicit non-production affordance.
- [ ] The Buyer supplies buyer and dealer location and either a VIN or manual year, make, model, trim, and mileage, with validation messages that name the field and the required correction.
- [ ] An optional listing URL is recorded as Buyer-supplied evidence through `POST /api/v1/cases/{caseId}/vehicle-evidence`.
- [ ] Vehicle evidence conflicts display the conflicting values with their sources and resolve through the existing conflict-resolution endpoint; an unresolved conflict blocks progression without discarding entered data.
- [ ] A location outside the configured pilot jurisdiction is refused before case creation, with an explanation and no stored personal data.
- [ ] The flow is usable at 360 px width in a single column, with labeled inputs, visible focus order, and no horizontal scrolling.
- [ ] Automated coverage includes case creation, consent recording, validation failure, conflict resolution, refusal outside the pilot jurisdiction, and keyboard traversal.
