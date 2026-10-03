# 48 — Prepare the price boundary expert review packet

**What to build:** The part of public-pilot readiness prerequisite 4 (`expert-policy-validation`) that
needs no expert. An operator can generate a review packet that runs the shipped price boundary policy
over a versioned set of retrospective deal scenarios, hand it to a consumer-side automotive expert, and
import the completed review as a record of verdicts. The expert review itself, and any decision to
mark the prerequisite approved or change the policy, stay with ticket 22 and the readiness record.

**Blocked by:** 10 — Approve immutable price boundaries; 21 — Assemble and close public-pilot
readiness evidence.

**Status:** ready-for-agent — filed on 2026-10-03.

- [ ] A versioned scenario set of at least twelve retrospective cash deals is stored as data, not code.
      It covers a narrow and a wide market range, a Buyer selling-price limit below the opening, a Buyer
      OTD limit, a deal whose OTD conversion is unavailable, a quote far above market, a quote below the
      target, and a fee-heavy deal. Every synthetic scenario is labelled synthetic, and the schema can
      hold a later permissioned real deal without code changes.
- [ ] The packet is computed by the shipped `PriceBoundaryPolicy` and the economics engine, not by a
      reimplementation. It records the policy version and a content hash of the scenario set, so a
      completed review can be matched to exactly what the expert saw.
- [ ] The reviewer workbook (CSV, plus a readable Markdown or HTML rendering) shows, per scenario, the
      inputs, market range, opening, target, policy maximum, walk-away, OTD equivalents or the reason
      they are unavailable, warnings, and the method text. It has blank columns for a verdict
      (`SOUND`, `ADJUST`, `UNSOUND`), suggested opening/target/walk-away, and a rationale.
- [ ] An import validates a completed workbook: every scenario answered with an allowed verdict, a
      reviewer name, qualification statement and review date present, suggested values well formed, and
      the packet hash matching. It refuses an incomplete or mismatched workbook with a reason per row and
      writes a review record with counts by verdict.
- [ ] Generation and import run from a documented command that does not start the application, bind a
      port, use the database, or make a network call. Output files are created exclusively and never
      written under `backend/src` or `frontend/`.
- [ ] Importing a review changes no policy, parameter, or readiness status. `docs/pilot-readiness-record.md`
      prerequisite 4 stays **BLOCKED** and names the packet and command as the route to evidence.
- [ ] A new `docs/expert-review-packet.md` explains what the expert is asked to judge, what the
      scenarios are and are not (synthetic, not representative of any jurisdiction), and how a review
      becomes evidence. Tests cover generation, hash stability, and every import refusal.
