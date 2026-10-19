# Dealer add-ons and extra charges

Ticket 142 names the add-ons and extra dealer charges that dealers print under many names. The
catalogue is `backend/src/main/resources/fees/dealer-add-ons.v1.json`, version
`dealer-add-ons.2026-10.v1`; the check is `economics.fees.DealerAddOnCheck`
(`dealer-add-on-check.v1`). Migration `V62` adds the optional `suggested_charge_id` column to
`quote_line_items`.

## Recognition

Each catalogue entry has an id, a family, a kind, the category it gives a line the Buyer confirms,
a plain-language name, what it is, its question for the dealer, and three term lists:

- `terms` name the entry. The operator's rule is to hold as many names, abbreviations and
  spellings as dealers print: "Paint Prot.", "PAINT/FABRIC PKG", "Etch", "Theft Deterrent Sys",
  "N2 Tires", "Mkt Adj", "ADM", "Ext. Warranty", "T&W".
- `possible` terms only suggest the entry. The line is shown as "possibly <name>" with a
  question, never dropped.
- `unless` terms cancel the entry, such as "admin" for ADM or "prepaid maint" for dealer prep.

Labels are read with `economics.fees.FeeLabels`, the same matcher as the government fee check
(ticket 139) and the documentary fee check (ticket 140). It folds case, accents, punctuation and
dotted or slashed abbreviations, reads a longer word that starts with a term as that term, and
tolerates a misspelling one edit away (two for words of eight letters or more) that keeps its first
letter. Ticket 142 added the first-letter condition, so "maint" is not read as "paint" nor "retail"
as "detail". Every catalogue example is tested to be recognised as its entry.

A line can name more than one entry ("PAINT/FABRIC PKG" is paint protection and fabric
protection); each is named. When any entry is named, suggestions are ignored.

Which lines are read: add-on, dealer fee and unclassified lines. A suggestion is shown only on an
add-on or dealer fee line, and not on a dealer fee line the doc fee or state fee check names; an
unclassified line already asks the dealer what it is, and a suggestion there could be a rebate or
a tax. The selling price, taxes, government charges, trade and credit lines are never read.

## Families

| Family | Entries |
| --- | --- |
| Pre-installed protection | paint protection, fabric or interior protection, undercoating or rust proofing, protection package (appearance, environmental), ceramic coating |
| Theft deterrent | VIN etching, theft protection (deterrent, registration, alarm), tracking or recovery device |
| Small accessories priced as extras | nitrogen-filled tires, wheel locks, mud flaps or splash guards, door edge guards, pinstripes, window tint, floor mats, cargo tray or liner, dealer-installed accessories |
| Price add-ons | market adjustment (ADM, markup, market value adjustment), dealer addendum, dealer premium package |
| Charges that may repeat other charges | dealer preparation or PDI, reconditioning, destination or freight, dealer handling |
| Products sold in the finance office | service contract or extended warranty, GAP coverage, tire and wheel, key replacement, dent and ding, windshield, maintenance plan, credit life or disability |

`otherCharges` lists the ids of charges other checks cover, which a model suggestion may also
name: `doc-fee` and `government-fee:<group>` for each state fee group.

## Kinds and wording

The kind drives the wording. Copy states what a line is and asks; it never says the dealer is not
allowed to charge it, and it makes no legal claim.

| Kind | Wording | In the optional total |
| --- | --- | --- |
| `OPTIONAL_ADD_ON` | Usually optional and negotiable. | yes |
| `PRICE_INCREASE` | A price increase on the car, not a service. It is negotiable. | no |
| `POSSIBLE_DUPLICATE` | May repeat a charge already on the paperwork. | no |
| `FINANCE_PRODUCT` | Optional product; you can decline it or buy it elsewhere. | yes |
| `MANUFACTURER_CHARGE` | Usually set by the manufacturer and printed on the window sticker. | no |

The destination entry is `repeatOnly`: one destination or freight line is a `MANUFACTURER_CHARGE`;
two or more are each a `POSSIBLE_DUPLICATE`.

A finance-office product also gets a note from the purchase method. On a financed deal it adds
interest for the whole loan term (ticket 12 calculates that cost in the Dealer financing section).
On a cash purchase it is paid now, with no interest. GAP and credit insurance are `loanOnly`: on a
cash purchase the note says they cover a loan and asks what they would cover. When the purchase
method is unknown, the note says what financing it would add.

## Findings

The result lists every recognised line with its amount, status (`RECOGNISED`, `POSSIBLE` or
`UNRECOGNISED`), name, kind wording, what it is, note and question, and gives the total of
usually optional items, the total of possible add-ons and the total of unrecognised charges. A line
whose amount could not be read stays in the list and is counted in `unreadAmounts`, never as zero.

- **Unrecognised charges.** Every add-on or dealer fee line no check names ("Gold Package",
  "Peace of Mind Plan") is listed as "Unrecognised charge — ask what it is." A dealer fee line the
  doc fee or state fee check names is left to that check.
- **Where it appears.** `analysis.dealerAddOns` in the report and both exports,
  `dealerAddOns` in the Deal Review, `addOns` in the Desk Check view and the "I'm at the dealer"
  result. A report or review whose offer lists no such line keeps its earlier shape.
- **Desk Check.** A charge that was not in the Agreed Price keeps its `ADDED` finding, with
  `chargeName` attached ("Added: VIN ETCH (VIN etching), $299.00"). "I'm at the dealer" names the
  added charge the same way, and without an Agreed Price a named line's finding says what it is
  and asks its question.
- **Listing.** When the Buyer saved a listing (ticket 115), the result adds one note asking
  whether the advertised price included the add-ons. The price comparison stays in the listing
  check; it is not repeated.

## Suggestions from a model-backed reader

The extraction contract's line items carry an optional `suggestedChargeId`: a catalogue id or an
`otherCharges` id. The deterministic provider leaves it empty; the model-backed provider reads it
when its output has one. The validator keeps it only in the id shape, and the extraction view
shows it as `suggestedCharge` (id, name, category) only when the catalogue knows the id.

The Buyer's verification step shows "This looks like <name>; confirm or change it." The Buyer
confirms it by sending the line in `confirmedSuggestionLineItemIds` when freezing the Dealer Offer,
or changes it by correcting the line's kind of charge. A confirmed suggestion becomes the line's
`chargeId` and sets its category; confirming a line with no suggestion, or one the Buyer also
corrected, is refused. The add-on, doc fee and state fee checks read only `chargeId`, never
`suggestedChargeId`, so a suggestion has no effect on any finding until the Buyer confirms it
(ticket 36's rule: model output never sets the category on its own).

The current model prompt and schema (`deal-terms-prompt.v2`, `deal-terms.v1`) do not ask for a
suggestion yet. Asking for one is a new prompt and schema version, which needs its own shadow
comparison before the rollout gate serves it.
