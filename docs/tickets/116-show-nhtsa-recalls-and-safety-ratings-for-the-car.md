# 116 — Show NHTSA recalls and safety ratings for the car

**What to build:** For the case's car, the Buyer sees the safety recalls NHTSA lists for its model year,
make and model, and NHTSA's crash-test ratings, from NHTSA's free public APIs. Each recall leads to one
plain question for the dealer: has it been repaired on this car, and can they show the repair record.
Operator decision on 2026-10-14, after comparing the product with CoPilot, which shows NHTSA ratings on
every listing. NHTSA data is public US government data with no license fee, so this needs no vendor
terms.

**Blocked by:** none. Builds on 04 (case vehicle), 52 (`NhtsaVpicVinDecoder`, the adapter pattern to
follow), 11 (report) and 41 (provider metering).

**Status:** open — filed on 2026-10-14. Migration V57 is reserved for this ticket.

## How it works

- A replaceable `VehicleSafetyProvider` with a deterministic fake (the default) and an NHTSA adapter
  selected by `vehicle-safety.provider=nhtsa`, written like `NhtsaVpicVinDecoder`: HTTPS only, timeout,
  bounded retries on 429 and 5xx, a body size cap, no redirects, nothing sent but year, make and model.
  - Recalls: `api.nhtsa.gov/recalls/recallsByVehicle?make=&model=&modelYear=` (campaign number,
    component, summary, consequence, remedy, report date).
  - Ratings: `api.nhtsa.gov/SafetyRatings/modelyear/{year}/make/{make}/model/{model}`, then
    `SafetyRatings/VehicleId/{id}` for each variant (overall, frontal, side, rollover stars and rollover
    risk).
- The public API lists recalls for the model, not whether a recall applies to this VIN or was repaired.
  The Buyer is told exactly that and given NHTSA's own VIN lookup link (`nhtsa.gov/recalls`) to check this
  car; the platform does not query the VIN lookup. Spec 2.2 excludes definitive recall conclusions, so the
  copy never says a car is "recall-free" or "safe".
- When NHTSA lists several rating variants (drive or body style), all are shown with their names instead
  of picking one; no variant match is guessed.
- Results are cached by model year, make and model for a bounded time, and the facts a frozen report shows
  are stored with it and their retrieval time, so a saved report does not change when NHTSA does.
- Unavailable answers are said ("NHTSA did not answer; check nhtsa.gov/recalls"), never shown as zero
  recalls.

## Done when

- [ ] With a case vehicle that has year, make and model (from VIN or confirmed manual facts), the case
      shows a "Recalls and safety ratings" panel and a fresh report includes the same facts as of
      their retrieval time.
- [ ] Each recall shows its NHTSA campaign number, component, summary and remedy, and the desk question;
      the panel links to NHTSA's VIN recall lookup with the case VIN ready to copy.
- [ ] Zero recalls, an unavailable provider and a vehicle without enough facts are each distinct and
      worded plainly; none is shown as "no recalls".
- [ ] `VehicleSafetyProductionGuard` refuses the fake under a pilot or production `DeploymentStage`; the
      NHTSA adapter is metered through `ProviderMeter` and listed in `docs/provider-inventory.md` (data
      sent: year, make, model only).
- [ ] Adapter tests run against a local stub server: success, several variants, empty results, 429 then
      success, 5xx exhaustion, oversized body, malformed JSON and a redirect.
- [ ] Saved reports from before this ticket render unchanged; reports with safety facts export and delete
      with the case.
- [ ] Copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`; the panel is registered in
      `Accessibility.test.tsx` and passes the 360 px browser gate in both themes.
- [ ] Migration `V57` (if needed) applies on PostgreSQL and H2; targeted `mvn -Dtest=...` suites,
      `npm test`, `npm run build` and `npx playwright test` pass.
