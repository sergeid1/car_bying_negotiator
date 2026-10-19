# Jurisdiction tax packs

`jurisdiction-tax-components.v1` extends `jurisdiction_policies.policy_json` with a nonempty
`taxComponents` array. Legacy rows without the array retain the original `tax_rate` calculation.
Each component stores `name`, decimal `rate` (0.06 means 6%), `base`, optional `baseCap` in major
currency units, `citation` (publication/section, year and URL), and `checkedOn` (ISO date).
Unknown bases and malformed components fail loading rather than falling back to a flat rate.

The first supported base is `TAXABLE_PRICE_AFTER_TRADE`: the pack's taxable line types, less the
trade allowance when its credit flag is on, floored at zero. The optional cap applies **after**
the allowance. Sum the unrounded component amounts and round the tax total using the pack's
currency scale and rounding mode. Recorded results freeze the components as well as the unrounded
total. Economics, price boundaries and the trade comparison all use these components. For a
trade comparison, calculate tax without the trade and subtract tax with the trade, rounding each
total to cents; multiplying the allowance by a combined rate would overstate a capped credit.
The desk comparison uses the paperwork's selling price and is labelled "about"; it does not
silently assume missing taxable fees or accessories.

Future patterns extend component/base rules in this same versioned JSON structure, with a new
pack version and validation for the additional fields, without replacing the policy table or
the shared calculation entry point. Rate bands need lower/upper bounds and a band application
mode (marginal versus whole-price). A total-tax maximum or minimum belongs after component
aggregation, distinct from `baseCap`. A value floor and book-value/MSRP bases require explicitly
named bases and recorded evidence for those inputs. Partial trade credit needs a deduction cap
and eligibility conditions before calculating each component's base. These patterns are extension
points, not currently enabled rules; do not encode them as a flat rate or a Florida base cap.

## Florida 2026

`US-FL.cash.2026.v1` has 67 immutable county scopes, whose stored versions are
`US-FL.cash.2026.v1:<county>` and jurisdictions are `US-FL:<county>`. Intake first resolves the
registration ZIP's state, then the Florida county. It stores the county identifier in the existing
`buyer_jurisdiction` column; it does not retain a registration ZIP for tax selection. More than
one county requires `buyerCounty` selected from the Census intersections. `SupportedJurisdictions`
lists the parent state only when an effective county pack exists. A county-free Florida calculation
has no fallback. Future-dated packs do not enable a state or replace its effective calculation.

V53 stores each county rate, citation, checked date and review date in `florida_county_surtaxes`,
then freezes the values in county-scoped policies. V54 has all Florida intersections from the
2020 Census ZCTA/county relationship file, including cross-county areas; its header records the
source and licence. ZCTAs approximate residential ZIPs, so PO-box-only ZIPs remain unrecognized.

Sources read in full and checked **2026-10-12**:

- [2026 Florida Statutes s.212.05(1)(a)1](https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.05.html): 6% state sales tax.
- [2026 Florida Statutes s.212.054(2)(b)1 and (3)(a)2](https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.054.html): $5,000 surtax base cap and purchaser's registration/title residence county.
- [2026 Florida Statutes s.212.09](https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.09.html): trade-in allowance deducted for both new and used purchases.
- [DOR DR-15DSS, Calendar Year 2026, R.11/25](https://floridarevenue.com/Forms_library/current/dr15dss_26.pdf): all county rates. Citrus and Collier have none; Hamilton is 2%; Martin and Palm Beach are 0.5% in 2026.
- [DOR GT-800019, R.10/25](https://floridarevenue.com/Forms_library/current/brochure/gt800019.pdf): discretionary surtax guide, including the cap and registration residence rule.
- [DOR GT-800030, R.08/22](https://floridarevenue.com/Forms_library/current/brochure/gt800030.pdf): motor-vehicle guide, taxable dealer charges/accessories, excluded mandated registration charges, and trade deduction.
- [Census 2020 ZCTA/county relationship file](https://www2.census.gov/geo/docs/maps-data/data/rel2020/zcta520/tab20_zcta520_county20_natl.txt): U.S. Government work, uncopyrighted under 17 U.S.C. 105.

The pack is effective **2026-01-01**, with review due **2027-01-01**. Do not reuse the 2025 DR-15DSS
at the old unversioned URL. DOR has moved brochure URLs to `current/brochure/`; the old research
URL for GT-800019 returns 404. GT-800019 is the surtax guide, not the motor-vehicle guide.

On a synthetic $35,000 vehicle in a 1% county without a trade, state tax is $2,100 and county tax
is $50, total $2,150. A $4,000 trade on an $8,000 vehicle leaves $4,000 taxable: $240 state tax and
$40 county tax. A trade on a vehicle whose taxable price remains above $5,000 removes state tax
without removing any county tax.

Out of scope: title, registration, plate, tire and battery fee schedules; non-resident purchases;
out-of-state purchases registered in Florida. Those need separate sourced rule passes.

## Pack rules beyond components (ticket 137)

Texas and North Carolina tax a different set of dealer charges than Florida does, and neither the
line type alone (`DEALER_FEE`, `ADD_ON`) nor the shared calculation can tell a documentary fee from
dealer preparation, or an attached accessory from a service contract. The pack data carries the
difference instead, as optional `policy_json` fields read into `CashDealEconomics.TaxRules`:

- `taxName`: what the state calls the tax. Buyer-facing copy uses it; packs without it read "sales tax".
- `nonTaxableCharges`: per taxable line type, label terms for a **separately stated** charge the state
  does not tax. A term matches the line's label case-insensitively at a word start, so `doc` matches
  "Doc fee" and "Documentary fee" but not "Paddock". A matching line stays in the out-the-door total but
  not in the taxable price, and its `treatmentReason` names the pack and term. `SELLING_PRICE` cannot be
  excluded, and empty, blank or unknown-line-type entries fail loading.
- `paidAtTitling` with `buyerNote`: the tax is collected when the car is titled rather than always on the
  dealer's invoice. The total still includes it. A quoted total that equals the recomputed total less the
  tax, within tolerance, reconciles with a reason naming the tax; the report's jurisdiction limitation and
  the Desk Check carry the note (see [desk check](desk-check.md)).

`TaxCalculation` freezes `taxName` and `buyerNote` with the components. A lump `DEALER_CHARGES_TOTAL` or
`ADD_ON_TOTAL` has no itemized labels, so it is taxed by its line type; itemized paperwork is needed for
an exclusion to apply.

## Texas 2026

`US-TX.cash.2026.v1`, jurisdiction `US-TX`, scope a Texas resident buying from a Texas licensed dealer:
one component, Texas motor vehicle sales tax at 6.25% on `TAXABLE_PRICE_AFTER_TRADE`, no cap and no
local motor vehicle tax. Taxable line types are `SELLING_PRICE,DEALER_FEE,ADD_ON`, with
`nonTaxableCharges` for a documentary fee and vehicle inventory tax reimbursement (`DEALER_FEE`) and for
a service contract, warranty, maintenance agreement, roadside assistance, GAP or other debt cancellation
agreement, insurance and auto club membership (`ADD_ON`). Dealer preparation and other dealer charges
are part of total consideration; accessories attached at the sale are taxable. The trade-in credit
applies because the trade is on the dealer's paperwork, which is a licensed dealer taking it in the same
transaction. The selling price after dealer discounts and verified rebates is the vehicle base, as the
Comptroller deducts separately stated cash discounts and rebates.

Sources read in full and checked **2026-10-16**:

- [Texas Tax Code chapter 152](https://statutes.capitol.texas.gov/Docs/TX/htm/TX.152.htm), s.152.002
  (total consideration, including the trade-in exclusion in (b)(5)), s.152.021 (6-1/4% rate) and
  s.152.0411 (licensed dealers collect the tax). The page is a script shell; the text was read from
  [the same document on the Legislative Council host](https://tcss.legis.texas.gov/resources/TX/htm/TX.152.htm).
- Comptroller Motor Vehicle Tax Guide 96-254 (3/2026): [Tax Rates](https://comptroller.texas.gov/taxes/publications/96-254/tax-rates.php)
  (6.25% since 09/1/91), [Motor Vehicle Sales Tax](https://comptroller.texas.gov/taxes/publications/96-254/mv-sales.php)
  (price less trade-in and dealer discounts on licensed-dealer sales),
  [Total Consideration](https://comptroller.texas.gov/taxes/publications/96-254/total-consideration.php)
  (the taxable and deducted charges above), [Trade-Ins](https://comptroller.texas.gov/taxes/publications/96-254/trade-ins.php)
  (same transaction, directly to the seller; no credit for a private sale or a sale to a different dealer)
  and [Accessories and Equipment](https://comptroller.texas.gov/taxes/publications/96-254/accessories-equipment.php).
- [Comptroller motor vehicle sales and use tax](https://comptroller.texas.gov/taxes/motor-vehicle/sales-use.php).

The ticket expected the sales section to list taxable dealer charges; it does not. The list is on the
guide's Total Consideration page, which also shows that Texas, unlike Florida, does not tax a
separately stated documentary fee.

Synthetic worked examples, checked in `TexasNorthCarolinaTaxPacksAcceptanceTest`: a $30,000 price and no
trade-in is $1,875.00 tax; a $12,000 trade-in leaves $18,000 taxable, $1,125.00; an $8,000 car with a
$10,000 trade-in is $0. The Comptroller's own example, $42,000 less a $15,000 trade-in, is $1,687.50. On
$30,000 with a $150 documentary fee, $300 dealer prep, $400 window tint, a $2,000 service contract and
a $700 GAP waiver, $30,700 is taxable: $1,918.75. Trading in the $12,000 car instead of selling it
elsewhere keeps $750.00 of tax.

## North Carolina 2026

`US-NC.cash.2026.v1`, jurisdiction `US-NC`, scope a North Carolina resident buying a passenger vehicle
from a North Carolina retailer: one component, North Carolina highway use tax at 3% on
`TAXABLE_PRICE_AFTER_TRADE`, no cap and no local tax. The $2,000 maximum in G.S. 105-187.3(a1) applies
only to Class A or B commercial motor vehicles and recreational vehicles, so it is not in the pack.
Taxable line types are `SELLING_PRICE,DEALER_FEE,ADD_ON`: retail value is the sales price including
accessories attached at delivery, plus any administrative, documentary or similar fee regulated by
G.S. 20-101.1. `nonTaxableCharges` excludes a separately stated online registration fee
(20-101.1(d)) and, as add-ons, a service contract, warranty, maintenance agreement, roadside assistance,
GAP and insurance, which are not the vehicle or an attached accessory. `paidAtTitling` is true: the tax
is paid when the certificate of title is applied for, and a retailer may collect it (105-187.4).

Sources read in full and checked **2026-10-16**:

- [G.S. 105 Article 5A](https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html):
  105-187.3 (base, 3% rate, maximums, retail value less the retailer's trade-in allowance, service
  contracts excluded when separately stated), 105-187.4 (payment at titling; retailer may collect) and
  105-187.6 and 105-187.7 (exemptions and credits, out of scope).
- [G.S. 20-101.1](https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/BySection/Chapter_20/GS_20-101.1.html):
  which dealer administrative fees are in the base.
- [NCDMV MVR-106, Rev. 04/26](https://www.ncdot.gov/dmv/downloads/Documents/MVR-106.pdf): "Dealer Sale -
  3% of sale price plus any Administrative/Document Fee less any trade-in price."
- [NCDOT highway use tax brief](https://www.ncdot.gov/about-us/how-we-operate/finance-budget/nc-first/Documents/nc-first-brief-edition-3.pdf),
  August 2020, background only: trade-in value comes off on a dealership purchase; the $2,000 cap is for
  commercial and recreational vehicles. Its state comparison figure is not used.
- The [NCDOR highway use tax page](https://www.ncdor.gov/taxes-forms/highway-use-tax) has no dealer-sale
  guidance; NCDOR administers the alternate tax on leases and rentals.

Synthetic worked examples: $30,000 and no trade-in is $900.00; a $12,000 trade-in leaves $18,000
taxable, $540.00; an $8,000 car with a $10,000 trade-in is $0. With the same five dealer charges as the
Texas example, the documentary fee is taxable here: $30,850 taxable, $925.50. A quote of $30,000 on a
$30,000 car with a $12,000 trade-in leaves out the $540 tax and still reconciles; the total stays
$30,540. Trading in the $12,000 car keeps $360.00 of tax.

## The synthetic fixture

V61 moves `US-NC.cash.v1` to jurisdiction `SYNTHETIC-FIXTURE`, which no postal code resolves to,
instead of deleting it: frozen results reference its `policy_version`, and they keep the pack version
they were made with. `SupportedJurisdictions` and the operator evidence measurement list only `US-`
jurisdictions. Acceptance tests that used a North Carolina ZIP code now run on the sourced pack; tests
that need a deterministic flat-rate policy construct one in code.

Out of scope for Texas and North Carolina: title, registration, plate and inspection fees; Texas
new-resident and even-exchange taxes and standard presumptive value (private sales); private-party
sales in both states; dealer doc fee caps (Texas has a $225 safe harbor, North Carolina no cap;
nothing in the product checks doc fee caps yet); non-resident purchases; and vehicles bought out of
state.

Title, registration, plate and other state fees are checked separately; see [government-fee-schedules.md](government-fee-schedules.md) (ticket 139).
