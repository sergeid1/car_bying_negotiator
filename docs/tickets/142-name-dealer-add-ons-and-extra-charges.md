# 142 — Name dealer add-ons and extra charges

**What to build:** Recognise the add-ons and extra dealer charges that dealers put on paperwork under many names, and
tell the Buyer what each one is, whether it is usually optional, and what to ask. Today a line is only classified as
`DEALER_FEE`, `ADD_ON` or `GOVERNMENT_CHARGE`, and the report says "Ask the dealer to explain each fee and add-on
before deciding" without naming any of them. Operator request on 2026-10-18: different dealers call the same
charge different things, and the check must recognise as many names as possible.

**Blocked by:** none — tickets 139 and 140 are integrated. It reuses their label-recognition approach (normalised keyword
matching with a "possible" tier) and touches the same report, Deal Review, Desk Check and "I'm at the dealer"
wiring, so it starts after they merge rather than in parallel with them.

**Status:** open — filed on 2026-10-18.

## Scope

- **A code-owned add-on catalogue**, versioned (for example `dealer-add-ons.2026-10.v1`), in
  `backend/src/main/resources/fees/dealer-add-ons.v1.json`. Each entry has an id, a plain-language name, what it is,
  the keyword patterns and example labels that identify it, a kind, and the question for the dealer. Recognition
  follows the operator's rule: as many names, abbreviations and spellings as possible; an uncertain match is shown
  as "possibly <name>" with a question, never dropped. Starting families, each with its many names:
  - **Pre-installed protection:** paint or paint sealant, fabric or interior protection, undercoating or rust
    proofing, "appearance", "environmental" or "protection" packages, ceramic coating.
  - **Theft deterrent:** VIN etching, window etch, theft protection or registration, tracking or recovery devices
    (for example LoJack-type products, named generically).
  - **Small accessories priced as extras:** nitrogen-filled tires, wheel locks, mud flaps, door edge guards,
    pinstripes, window tint, splash guards, floor mats, cargo trays.
  - **Price add-ons:** market adjustment, ADM, additional dealer markup, "market value adjustment", dealer
    addendum, "premium package" added by the dealer.
  - **Charges that may duplicate other charges:** dealer prep, pre-delivery inspection or PDI, reconditioning on a
    new car, a second freight or destination line, "dealer handling".
  - **Products sold in the finance office:** service contract or extended warranty, GAP, tire and wheel, key
    replacement, dent and ding, windshield, maintenance plans, credit life or disability. Ticket 12 already calculates
    their financed cost; this ticket names them and says they are optional.
- **One matcher for every fee check.** Ticket 139's `economics.fees.FeeLabels` folds case, punctuation and
  abbreviations and tolerates misspellings ("registeration", "liscense"); ticket 140's `DocFeeCheck` uses regular
  expressions without misspelling tolerance, so "Docuemntary fee" is missed. Move `DocFeeCheck` onto `FeeLabels`
  (keeping its two tiers and its tests) and use `FeeLabels` for the add-on catalogue, so all three checks recognise
  labels the same way.
- **Kinds drive the wording**, never a legal claim: "usually optional and negotiable", "a price increase on the car,
  not a service", "may repeat a charge already on the paperwork", "optional product; you can decline it or buy it
  elsewhere". Copy states what the line is and asks; it never says the dealer is not allowed to charge it.
- **Findings** appear where dealer-charge findings already appear (the report, the Deal Review, the Desk Check and
  the "I'm at the dealer" result), list every recognised line with its amount, and give the total of optional
  items. A charge that was not in the Agreed Price keeps the Desk Check's existing `ADDED` finding, with the name
  attached.
- **Names no keyword can catch** (for example "Gold Package" or "Peace of Mind Plan"): every dealer charge the
  catalogue does not recognise is still listed for the Buyer as "unrecognised charge — ask what it is", never
  hidden. The extraction contract gains an optional per-line `suggestedChargeId` (a catalogue id, or a doc fee or
  government fee category from tickets 139 and 140). The deterministic provider leaves it empty. The model-backed
  provider (ticket 36) may fill it from the line's printed text and any description on the page. A suggestion is
  shown as "this looks like <name>; confirm or change it" in the Buyer's verification step, and becomes the line's
  category only when the Buyer confirms it. Model output never sets the category on its own (ticket 36's rule).
- Where ticket 115 (paperwork against the advertised listing) has the listing, an add-on missing from the
  advertised price is noted with the existing listing finding; do not duplicate that check.
- Update the Toto guide's `add-ons` and `other-dealer-charges` articles (ticket 118) to match; they are still
  unreviewed, so this does not need a new operator review cycle.
- Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.

## Done when

- [ ] The catalogue ships with each family above and its label variants, and `docs/dealer-add-ons.md` describes the
      kinds and the wording rules.
- [ ] Tests cover, with labelled synthetic lines: several spellings per family (for example "Paint Prot.",
      "PAINT/FABRIC PKG", "Etch", "Theft Deterrent Sys", "N2 Tires", "Mkt Adj", "ADM"); an uncertain match shown as a
      possible add-on; a line that must not match (for example the selling price, sales tax, title fee); a
      finance-office product on a cash deal and on a financed deal; and two add-ons on one paperwork totalled.
- [ ] An acceptance test shows named add-ons in the report and the Desk Check.
- [ ] No recognised line is dropped from what the Buyer sees, and an unrecognised dealer charge is listed as one.
- [ ] A `suggestedChargeId` on a line is shown for the Buyer to confirm, and tests show it has no effect on the
      findings until the Buyer confirms it.
- [ ] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.

## Implementation notes

- Catalogue `fees/dealer-add-ons.v1.json` (`dealer-add-ons.2026-10.v1`): 32 entries in the six families,
  each with terms, possible terms, unless terms, examples, a kind, a category and a question; `otherCharges` lists
  `doc-fee` and the `government-fee:<group>` ids. `DealerAddOnCatalogue` loads and validates it; `DealerAddOnCheck`
  (`dealer-add-on-check.v1`) produces the findings. See [the add-on rules](../dealer-add-ons.md).
- One matcher: `DocFeeCheck` now reads its two tiers with `FeeLabels` (`doc-fee-rules.2026-10.v3`), keeping every
  existing test. `FeeLabels` tolerates a misspelling only with the same first letter, so "maint" is not "paint". The
  government fee check leaves a dealer fee line the catalogue names ("Tire & Wheel", "N2 Tires") to the add-on check.
- Findings: `analysis.dealerAddOns` in the report and both exports, `dealerAddOns` in the Deal Review, `addOns` in
  the Desk Check and the "I'm at the dealer" result; `ADDED` Desk Check findings carry `chargeName`. Every add-on
  or dealer fee line no check names is listed as an unrecognised charge. With a saved listing, one note asks whether
  the advertised price included the add-ons; the price comparison stays with ticket 115's check.
- `suggestedChargeId`: optional on the provider and validated line items, stored by migration `V62`, shown in the
  extraction view as `suggestedCharge` and in the verification step as "This looks like <name>; confirm or change
  it." It becomes the line's `chargeId` and category only through `confirmedSuggestionLineItemIds` when the Buyer
  freezes the Dealer Offer; the checks read only `chargeId`. The current model prompt and schema do not ask for a
  suggestion yet; that needs a new prompt and schema version and its shadow comparison.
- Toto guide: `add-ons` and `other-dealer-charges` rewritten (article version 2, still unreviewed); the manifest
  records the new digest.
