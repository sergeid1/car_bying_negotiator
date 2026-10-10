# Desk Check v1

`GET /api/v1/cases/{caseId}/desk-check` with `Case-Access-Token` compares the paperwork
presented at the dealership desk with the case's Agreed Price (ticket 63). It is a read
projection over existing records and adds no table or migration.

## What counts as desk paperwork

The most recent source document uploaded to the case after `agreed_prices.accepted_at`,
through the ordinary `POST /documents` intake. The accepted quote itself never counts. The
comparison baseline is the Agreed Price's own Approved Baseline (`agreed_prices.baseline_id`
and `baseline_version`), not merely the latest baseline.

## Sources, strongest first

| `source` | When | Verified? |
| --- | --- | --- |
| `DEAL_REVIEW` | The paperwork was verified into the next Dealer Offer and `POST /analyses` saved a Deal Review against the Agreed Price's baseline | Per term, from the offer's `buyerVerified` |
| `VERIFIED_OFFER` | Verified, but no Deal Review against that baseline yet | Per term, from the offer's `buyerVerified` |
| `READ_FROM_PAPERWORK` | Extraction finished, nothing verified yet | Never |

All three use `RevisedOfferPolicy` (`revised-offer.v1`): the saved review's `BASELINE`
discrepancies, or the policy run directly over the verified terms or the terms as read. The
check never reports a term as verified unless it rests on Buyer-verified document evidence.

## Leading with differences

Findings are grouped and ordered: `VEHICLE`, `ADDED` (charges not in the Agreed Price),
`REMOVED`, `CHANGED_CHARGE`, `PRICE`, `FINANCING`, `TRADE`, `LIMIT` (an approved ceiling
exceeded, from the Deal Review), `TOTAL`. Changes that do not affect money, such as a new
document number or date, or a charge that keeps its amount under a different classification,
go to `otherDifferences` and stay visible. Terms equal to the Agreed Price are counted in
`unchangedTerms`.

`totalEffect` states its `basis`: `CALCULATED` uses the Deal Review's cash deltas (pre-tax
and tax effect separately), `QUOTED` uses the out-the-door total printed on the paperwork, and
`UNAVAILABLE` never stands in for zero. `addedChargesMinor` sums the added charges.

## A different VIN

If the paperwork's VIN differs from the agreed car's, the status is `DIFFERENT_VIN`: one
`BLOCKING` finding, no amount comparison, and `freshCase` with that VIN, the dealer ZIP and
the purchase method. Verification already refuses to turn that paperwork into a Dealer Offer
for the agreed car. The client starts the fresh case through the ordinary `POST /api/v1/cases`
path, needing no journey or account, and sends the photographed paperwork to it again
through intake.

## Access and latency

Case credentials as for any case resource. Once an Agreed Price exists the check shares the
report purchase gate (402 `PURCHASE_REQUIRED`). It adds no processing stage: document-to-review
latency is the Phase 1 intake and extraction pipeline's, and the comparison itself is a
deterministic read, which `DeskCheckAcceptanceTest` holds under the 500 ms recalculation p95.

## Starting from a photo

The header's "I'm at the dealer" routes photographed paperwork here or to a fresh case by its VIN,
after the Buyer confirms which car it is for. See [desk-start.md](desk-start.md).
