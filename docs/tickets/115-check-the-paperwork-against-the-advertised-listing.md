# 115 — Check the paperwork against the advertised listing

**What to build:** The Buyer saves the dealer's listing for the car they are looking at, as a screenshot,
a "Save as PDF" of the page, or its pasted text, and the case keeps what it advertised: price, VIN,
mileage, dealer and any price fine print. The platform never fetches the page; the Buyer supplies the
capture, as spec 2.2 allows ("a listing URL as Buyer-supplied evidence", section 2.1) without becoming
a scraping dependency (section 5.2). Once a Dealer Offer or desk paperwork exists, the analysis compares
its vehicle price with the advertised price, so a price that grew between the ad and the paperwork is
shown with its dollar difference and one question for the dealer. Operator decision on 2026-10-14,
after comparing the product with CoPilot; ticket 117 holds the server-fetch alternative, which is
blocked.

**Blocked by:** none. Builds on 04 (Listing Snapshots and VIN conflicts), 05 and 24 (document intake and
upload), 06 and 25 (extraction with evidence and Buyer confirmation), 66 (Desk Check). Reading
screenshots needs ticket 36's Claude provider; until then, like ticket 105, the flow works for
text-bearing captures (a saved PDF of the page, pasted text) and Buyer-typed fields.

**Status:** open — filed on 2026-10-14. Migration V56 is reserved for this ticket.

## How it works

- A new document purpose, `LISTING_CAPTURE`, beside `DEALER_PAPERWORK` and `TRADE_OFFER_EVIDENCE` in
  `DocumentIntakePersistence`. Like trade offer evidence it does not change the case's review status and
  never becomes a Dealer Offer.
- Extraction reads the listing fields with field-level evidence: `LISTING_PRICE` (the advertised vehicle
  price), `VEHICLE_VIN`, `VEHICLE_YEAR`, `VEHICLE_MAKE`, `VEHICLE_MODEL`, `VEHICLE_TRIM`, `ODOMETER`,
  `DEALER_NAME`, the date the capture was taken when shown, and `LISTING_PRICE_CONDITIONS`: fine print
  such as "price requires dealer financing", "includes $1,500 rebate", "plus dealer fees". The
  deterministic provider handles text-bearing captures; the Claude provider gets a separate listing
  prompt version and stays behind its existing rollout gate.
- The Buyer confirms or corrects each field against its outlined region, as in ticket 25, or types the
  fields directly when the capture cannot be read. Confirmed fields become an immutable Listing Snapshot
  with source `BUYER_SUPPLIED_LISTING_CAPTURE`, provider version and license metadata stating it is the
  Buyer's own capture, not redistributed, satisfying `ListingProviderContract`.
- The listing VIN joins the existing identity conflict check: a different VIN from the case vehicle or
  the paperwork is blocking, as spec 5.2 requires.
- A rule (`listing-price-check.v1`) compares the vehicle price on the latest Dealer Offer and on desk
  paperwork with the confirmed advertised price. A higher price is a finding with the dollar difference,
  the snapshot date, and the desk question ("The listing on <date> showed $X. Why is the price on this
  paperwork $Y?"). Captured conditions are shown beside it, because a conditional ad price (dealer
  financing, rebates the Buyer may not qualify for) can explain part of the gap. Equal or lower is said
  plainly. The finding makes no legal claim about advertising law (spec 2.2 excludes definitive legal
  conclusions).

## Done when

- [ ] A Buyer can add a listing capture to an open case by upload (PDF, JPEG, PNG, HEIC within the
      existing limits) or by pasting text, from the "Get quotes" step and from "I'm at the dealer".
- [ ] `LISTING_CAPTURE` documents go through the existing malware, parser confinement and encryption
      path, never change the case status, and never feed Dealer Offer creation.
- [ ] Extracted listing fields carry evidence regions; the Buyer confirms or corrects each before a
      Listing Snapshot is created; an unreadable capture falls back to typed fields without stopping.
- [ ] A listing VIN different from the case vehicle or paperwork VIN raises the existing blocking
      identity conflict.
- [ ] The Desk Check, the "I'm at the dealer" result and a fresh report show the price difference with its
      snapshot date and desk question; price conditions captured from the ad are shown with it; saved
      reports from before this ticket render unchanged.
- [ ] Labelled synthetic fixtures cover: a matching price, a higher paperwork price, a conditional ad price,
      a listing VIN mismatch, and an unreadable capture.
- [ ] Copy says this is the Buyer's own capture of the ad, never that the platform checked the dealer's
      site, and passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] Captures and snapshots are exported and deleted with the case (`case-portability-policy.md`,
      `case-deletion-policy.md`); `CaseIsolationInventoryAcceptanceTest` classifies every new endpoint.
- [ ] New panels are registered in `Accessibility.test.tsx` and pass the 360 px browser gate in both themes.
- [ ] Migration `V56` (if needed) applies on PostgreSQL and H2; targeted `mvn -Dtest=...` suites, `npm test`,
      `npm run build` and `npx playwright test` pass.
