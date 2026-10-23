# 116 — Show NHTSA recalls and safety ratings for the car

**What to build:** For the case's car, the Buyer sees the safety recalls NHTSA lists for its model year,
make and model, and NHTSA's crash-test ratings, from NHTSA's free public APIs. Each recall leads to one
plain question for the dealer: has it been repaired on this car, and can they show the repair record.
Operator decision on 2026-10-14, after comparing the product with CoPilot, which shows NHTSA ratings on
every listing. NHTSA data is public US government data with no license fee, so this needs no vendor
terms.

**Blocked by:** none. Builds on 04 (case vehicle), 52 (`NhtsaVpicVinDecoder`, the adapter pattern to
follow), 11 (report) and 41 (provider metering).

**Status:** complete against a local NHTSA stub — no migration (V57 unused), as of 2026-10-14.
`com.dealanalyzer.vehicle.safety` holds `VehicleSafetyProvider`, the development fake (the default, labelled
"Development sample — not NHTSA data") and `NhtsaVehicleSafetyProvider` (`vehicle-safety.provider=nhtsa`): HTTPS only,
no redirects, a 1 MiB streamed body cap, a deadline covering a stalled body, retries on 429 and 5xx, and only year, make
and model sent. `VehicleSafetyProductionGuard` refuses the fake under a production-like `DeploymentStage`; calls are
metered as `VEHICLE_SAFETY`. Successful answers are cached per process by year, make and model (six hours, 500
entries); failures are not cached. `GET /api/v1/cases/{caseId}/vehicle-safety` serves the case panel, shown collapsed
below the case header; a fresh report freezes the facts as `vehicleSafety` after rechecking the car under the case
lock. Verified against live NHTSA on 2026-10-22 (recalls, ratings variants and variant details for ten
cars, plus five public VINs decoded through vPIC and looked up end to end), which fixed four mismatches the stub
hid: a lookup with no recall records answers HTTP 400 with an empty JSON body and was shown as "NHTSA did not
answer" (now zero records); `ReportReceivedDate` is day first and was shown raw (`01/02/2022` reads as 2 January
to a US Buyer; now ISO); an unrated variant's `0.0` rollover risk was shown as 0.0% (now not supplied); and a model
with `/` hit a path NHTSA rejects (now no ratings request). Trimmed live answers are the stub fixtures in
`fixtures/nhtsa-safety`. Open: model names differ between sources, so some cars return zero records, which the copy
covers by sending the Buyer to NHTSA's VIN lookup. vPIC decodes a 2018 Silverado 1500 as "Silverado" (Series
"1500"), which finds none of the 7 recalls or 7 rated variants NHTSA lists under "Silverado 1500"; a 2021 F-150
finds 29 recalls but no ratings, which NHTSA lists under "F-150 Super Crew" and other cab names. vPIC's Series field
is not consistent enough to join to the model (Ford "F-Series", Toyota chassis codes), so nothing is matched by
guess.

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

- [x] With a case vehicle that has year, make and model (from VIN or confirmed manual facts), the case
      shows a "Recalls and safety ratings" panel and a fresh report includes the same facts as of
      their retrieval time.
- [x] Each recall shows its NHTSA campaign number, component, summary and remedy, and the desk question;
      the panel links to NHTSA's VIN recall lookup with the case VIN ready to copy.
- [x] Zero recalls, an unavailable provider and a vehicle without enough facts are each distinct and
      worded plainly; none is shown as "no recalls".
- [x] `VehicleSafetyProductionGuard` refuses the fake under a pilot or production `DeploymentStage`; the
      NHTSA adapter is metered through `ProviderMeter` and listed in `docs/provider-inventory.md` (data
      sent: year, make, model only).
- [x] Adapter tests run against a local stub server: success, several variants, empty results, 429 then
      success, 5xx exhaustion, oversized body, malformed JSON and a redirect.
- [x] Saved reports from before this ticket render unchanged; reports with safety facts export and delete
      with the case.
- [x] Copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`; the panel is registered in
      `Accessibility.test.tsx` and passes the 360 px browser gate in both themes.
- [x] Migration `V57` (not needed; unused) applies on PostgreSQL and H2; targeted `mvn -Dtest=...` suites,
      `npm test`, `npm run build` and `npx playwright test` pass.
