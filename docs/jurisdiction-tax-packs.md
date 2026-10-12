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

`US-NC.cash.v1` is retained for deterministic development and acceptance fixtures and explicitly
labelled **synthetic development fixture**, including in intake. It is not a sourced North Carolina
pack. Retiring it would break existing fixtures; enabling production remains operator-controlled.

Out of scope: title, registration, plate, tire and battery fee schedules; non-resident purchases;
out-of-state purchases registered in Florida. Those need separate sourced rule passes.
