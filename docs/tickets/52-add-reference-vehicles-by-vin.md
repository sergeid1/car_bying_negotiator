# 52 — Add Reference Vehicles by VIN

**What to build:** A Buyer adds VINs of cars they like. Each VIN is checksum-validated and decoded into year, make, model, trim, body, drivetrain, engine, and transmission, and the Buyer marks which decoded attributes matter. Those attributes refine the Shopping Brief. Spec §5.6, SHOP-02.

**Blocked by:** 51 — Describe the car in a Shopping Brief.

**Status:** ready-for-agent (51 complete) — filed on 2026-10-03 from specification 2.0.

- [ ] A replaceable VIN decoder adapter interface exists with a deterministic fake used by the main test suite; no main test calls a live endpoint.
- [ ] A live adapter for the free public NHTSA vPIC decoder exists behind configuration, with timeout, retry, and stated unavailability; its terms are recorded in `docs/provider-inventory.md`.
- [ ] An invalid VIN is refused with a checksum message before any decoder call; an undecodable VIN is shown as unavailable, never guessed.
- [ ] Decoded attributes show their source and decoder version; the Buyer marks each as relevant or not, and only relevant attributes refine matching.
- [ ] A brief holds up to five Reference Vehicles; adding or removing one creates a new brief version.
- [ ] Reference VINs are personal data under the case deletion policy and are deleted with the journey.
