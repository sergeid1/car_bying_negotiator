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

## Trade-in check (ticket 103)

When the paperwork shows a trade allowance (`TRADE_ALLOWANCE`, or `TRADE` lines), `trade.desk.TradeDeskCheck`
(`trade-desk-check.v2`; v1 before ticket 106) compares it with the Buyer's Trade Vehicle (ticket 100) and returns `trade` on the Desk
Check, on the "I'm at the dealer" first result, and as `analysis.tradeCheck` in the report. Its warning and
blocking findings lead the `TRADE` group, ahead of totals, and the first one leads the report's reasons.

- **Trade VIN.** `TRADE_VIN` is a deal-terms field (checksum-validated, read by the deterministic provider from
  "Trade VIN"). A different VIN is one `BLOCKING` finding and nothing else about the trade is compared.
- **Written offer.** The best unexpired written offer (`bestOfferId`); a shortfall is shown in dollars with the
  offer it is measured against. Expired offers are never used.
- **Sales tax.** The trade-in tax credit comes from the case's jurisdiction policy pack (its tax components,
  `tradeTaxCreditEnabled`, rounding), capped at the paperwork's selling price, and is added to the offer
  comparison (`offerGapAfterTaxMinor`). Without a pack for the jurisdiction it is `UNAVAILABLE`, never estimated.
  `taxCredit.taxName` is what the state calls the tax ("Texas motor vehicle sales tax", "North Carolina highway
  use tax"; "sales tax" when a pack does not name it), and the copy uses it.
- **Tax paid at titling (ticket 137).** When the case's pack has `paidAtTitling` (North Carolina), a tax line or
  `TAX_TOTAL` missing from desk paperwork is an `INFORMATIONAL` note under `otherDifferences`, with the pack's
  `buyerNote`, rather than a lead finding: the tax may be paid when the car is titled and is still owed.
- **Valuation range.** From `TradeValuationService` (ticket 101) for the dealer ZIP, labelled as an estimate
  from the named source; unavailability is stated and nothing is substituted. One available result per trade
  revision is reused in memory for ten minutes so repeated reads do not meter repeated calls; nothing is stored.
- **Buyer estimate (ticket 106).** When the adapter gives no licensed range (unavailable, or the synthetic
  development range), the Buyer's most recently looked-up estimate stands in: `valuation.basis` is
  `BUYER_ESTIMATE` (else `PROVIDER`), with `estimateId`, `checkedOn`, `old` and `label` ("your Carfax estimate
  from 2026-10-05"). The same finding codes are used and the copy names the estimate as the reference; an
  estimate checked more than 30 days ago is called old in the finding, not dropped. An allowance above it is
  shown next to the price as `priceTogether.reference = BUYER_ESTIMATE_HIGH`. An estimate is never an offer
  and never feeds `bestOffer`. A licensed range takes precedence. v1 results lack the new fields and render
  as before.
- **Payoff.** The paperwork's payoff against the Buyer's: a difference is flagged with its amount; a stale or
  unknown Buyer payoff is said and never treated as the same.
- **Equity.** Allowance less the paperwork's payoff (else the Buyer's). Negative equity on paperwork with a loan
  is priced through `FinancingCalculator` as a financed add-on (payment, scheduled payments, interest).
- **DEAL-09 / TRADE-05.** Nothing here touches price, discount or the out-the-door total. An allowance above the
  trade's value (the best offer, else the top of the range) is shown next to the price increase over the Agreed
  Price as a net effect, never netted silently.

The report computes the check before it locks the case row, because the valuation meter commits on its own
and references the case.
