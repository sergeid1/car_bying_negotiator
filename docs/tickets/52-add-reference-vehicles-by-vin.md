# 52 — Add Reference Vehicles by VIN

**What to build:** A Buyer adds VINs of cars they like. Each VIN is checksum-validated and decoded into year, make, model, trim, body, drivetrain, engine, and transmission, and the Buyer marks which decoded attributes matter. Those attributes refine the Shopping Brief. Spec §5.6, SHOP-02.

**Blocked by:** 51 — Describe the car in a Shopping Brief.

**Status:** complete — Reference Vehicles decoded by VIN in `com.dealanalyzer.journey` and `com.dealanalyzer.journey.vin`
(migration V36), as of 2026-10-04. A VIN is checksum-validated with the existing `Vin` rule and refused with a checksum
message before any decoder call. `VinDecoder` is replaceable: `FakeVinDecoder` is the default and the only decoder the
main suite uses, and `NhtsaVpicVinDecoder` (`vin-decoder.provider=nhtsa-vpic`) has a per-attempt timeout, bounded retry
on connection failures, 429 and 5xx, and a stated unavailability answer that saves nothing; `VinDecoderProductionGuard`
refuses the fake under a pilot or production deployment. An undecodable VIN is stored and shown as unavailable with no
attributes. Each decoded attribute shows its source and decoder version, starts unmarked, and the Buyer marks which
matter; `JourneyService.referenceVehicles(accountId, journeyId)` exposes `relevantAttributes()` for matching. A brief
holds up to five, and adding, removing, or re-marking one inserts the next immutable brief version. Reference VINs are
deleted with the journey and the account and included in the account export. vPIC terms are recorded in
`docs/provider-inventory.md`. Open: no live vPIC response is exercised in tests, only a local stand-in; vPIC usage
limits must be re-checked and dated before pilot enablement; matching itself is ticket 55.

- [x] A replaceable VIN decoder adapter interface exists with a deterministic fake used by the main test suite; no main test calls a live endpoint.
- [x] A live adapter for the free public NHTSA vPIC decoder exists behind configuration, with timeout, retry, and stated unavailability; its terms are recorded in `docs/provider-inventory.md`.
- [x] An invalid VIN is refused with a checksum message before any decoder call; an undecodable VIN is shown as unavailable, never guessed.
- [x] Decoded attributes show their source and decoder version; the Buyer marks each as relevant or not, and only relevant attributes refine matching.
- [x] A brief holds up to five Reference Vehicles; adding or removing one creates a new brief version.
- [x] Reference VINs are personal data under the case deletion policy and are deleted with the journey.
