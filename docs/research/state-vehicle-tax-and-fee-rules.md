# State vehicle tax and fee rules

Research for the `jurisdiction-pack` prerequisite in
[the pilot readiness record](../pilot-readiness-record.md). As of 2026-10-10.

This is research, not legal advice, and not a jurisdiction pack. Every value needs a check against
the state's own publication before it ships.

## Summary

Public information exists for all 50 states and DC, and the table below has a row for each. Most
states fit "one rate, maybe a local add-on, trade-in comes off first", but about 15 do not, and
those decide the shape of the jurisdiction pack.

- **No statewide sales tax (5):** Alaska, Delaware, Montana, New Hampshire, Oregon. Three of them
  still tax the purchase another way: Alaska towns, Delaware's 5.25% "document fee", and Oregon's
  0.5% privilege tax on new cars.
- **No trade-in credit (4):** California, Hawaii, Virginia, DC. **Partial credit (4):** Michigan
  caps it at $12,000 in 2026, Kentucky allows it only on used cars, Ohio's statute allows it only
  on new cars, and Rhode Island allows it only when a car is traded for a car.
- **Not a flat rate (10+):** Florida, Tennessee, South Carolina, Arkansas, Connecticut, Georgia,
  Kentucky, Virginia, Maryland and DC each need a cap, a tier, a minimum or a different tax base.
- **Doc fee caps:** 18 states cap the dealer doc fee, and Texas sets a $225 safe harbor instead of
  a cap. Florida has no cap.

The free comparison websites disagree with each other and with the statutes. They get Ohio, Rhode
Island, Mississippi and Oklahoma wrong or out of date. So each row is a lead to check against the
state's own publication, not a value to ship.

## How to read this

Each row is the rule a Buyer meets when buying a passenger car from a dealer in their home state
and registering it there.

- **State tax**: the state-level rate on a vehicle purchase, and what the tax is called when it is
  not a sales tax.
- **Local add-on**: whether county or city taxes stack on top, and any cap.
- **Trade-in credit**: whether the trade-in's value comes off the taxable price. "Yes" means in
  full.
- **Doc fee cap**: the legal maximum on the dealer's documentation fee, or "None" where no cap
  exists.
- **Confidence**: *Sourced* means confirmed on a state agency or statute page linked in the row;
  *Secondary* means confirmed only on a non-government source; *Unverified* means it still needs a
  check.

## All states

| State | State tax on purchase | Local add-on | Trade-in credit | Doc fee cap | Notes | Confidence |
| --- | --- | --- | --- | --- | --- | --- |
| Alabama | 2% (automotive rate) | Yes, county and city | Yes | None | Lower state rate for vehicles than general sales tax | Secondary |
| Alaska | None | Some towns | Yes, where taxed | None | Local rules vary by municipality | Secondary |
| Arizona | 5.6% | Yes, county and city | Yes | None | Called transaction privilege tax | Secondary |
| Arkansas | 6.5% | Yes | Yes | $129 | Used cars: under $4,000 exempt, $4,000 to $9,999 at 3.5% | Secondary |
| [California](https://california.public.law/codes/vehicle_code_section_4456.5) | 7.25% | Yes, district taxes | No | $85 ($70 if not a DMV partner) | Tax on full price regardless of trade | Sourced (doc fee); Secondary (tax) |
| Colorado | 2.9% | Yes, often large | Yes | None | Local share often exceeds the state's | Secondary |
| [Connecticut](https://portal.ct.gov/DRS/Publications/Special-Notices/2011/SN-2011-10) | 6.35%; 7.75% over $50,000 | No | Yes, dealer sales | None | Rate jumps for the whole price above $50,000 | Sourced |
| Delaware | None; 5.25% document fee | No | Yes, Delaware-titled trade | None | Fee on the higher of price or NADA value; rose from 4.25% | Secondary |
| [DC](https://dmv.dc.gov/node/155502) | Excise 1% to 11% | No | No | None | Rate set by weight and city MPG, on fair market value | Sourced (rates); Secondary (trade-in) |
| [Florida](https://floridarevenue.com/Forms_library/current/gt800019.pdf) | 6% | County surtax, first $5,000 only | Yes | None | Surtax follows the Buyer's county of residence | Sourced |
| [Georgia](https://dor.georgia.gov/motor-vehicles/vehicle-taxes-title-ad-valorem-tax-tavt-and-annual-ad-valorem-tax) | 7% title ad valorem tax (TAVT) | No | Yes | None | No sales tax; TAVT on fair market value, not price | Sourced |
| Hawaii | 4% general excise tax | County surcharge | No | None | Dealers often pass on about 4.7% | Secondary |
| Idaho | 6% | Few resort cities | Yes | None | | Secondary |
| [Illinois](https://www.chicagoautoshow.com/new-illinois-law-removes-trade-in-tax-credit-cap-news/) | 6.25% | Yes | Yes, full since 2022 | $377.63, indexed | A $10,000 trade-in cap ran 2020 to 2021 | Secondary |
| Indiana | 7% | No | Yes | About $262, indexed | Sources give $251 or $262 | Secondary |
| Iowa | 5% fee for new registration | No | Yes | $180 | | Secondary |
| Kansas | 6.5% | Yes | Yes | None | | Secondary |
| [Kentucky](https://letchercountyclerk.ky.gov/motor/taxes-information/) | 6% usage tax | No | Used cars only | None | New cars taxed on total consideration or 90% of MSRP | Sourced |
| Louisiana | 5% | Yes, parish | Yes | About $436, indexed | State rate rose from 4.45% in 2025 | Secondary |
| Maine | 5.5% | No | Yes | None | | Secondary |
| [Maryland](https://mva.maryland.gov/your-mva-guide/businesses/bulletins-businesses/taxable-price) | 6.5% excise | No | Yes | $800 | Rose from 6% on July 1, 2025; cars 7+ years old taxed on at least $640 | Sourced |
| Massachusetts | 6.25% | No | Yes | None | | Secondary |
| [Michigan](https://archive.legislature.mi.gov/documents/2015-2016/billanalysis/Senate/htm/2015-SFA-0010-F.htm) | 6% | No | Capped, $12,000 in 2026 | $280 or 5%, lesser | Cap rises $1,000 a year until removed | Secondary |
| [Minnesota](https://dps.mn.gov/divisions/dvs/vehicle/vehicle-fees/vehicle-sales-tax) | 6.875% | No | Yes | $350 | | Sourced (tax); Secondary (doc fee) |
| [Mississippi](https://www.law.cornell.edu/regulations/mississippi/30-Miss-Code-R-SS-1301-8-1) | 5% | Some cities | Yes | $425 (franchised dealers) | Several fee sites wrongly say no cap | Sourced (doc fee); Secondary (tax) |
| [Missouri](https://dor.mo.gov/resources/official-final-rules/documents/12_CSR_10-26_231_Maximum_Dealer_Administrative_Fee.pdf) | 4.225% | Yes | Yes | $620.79, reset each Feb 1 | | Sourced (doc fee); Secondary (tax) |
| Montana | None | No | Not applicable | None | Registration fees depend on vehicle age | Secondary |
| Nebraska | 5.5% | Yes | Yes | None | | Secondary |
| Nevada | 6.85% | Yes | Yes | None | | Secondary |
| New Hampshire | None | No | Not applicable | None | Town permit fee based on MSRP and age | Secondary |
| New Jersey | 6.625% | No | Yes | None | | Secondary |
| [New Mexico](https://www.mvd.newmexico.gov/?p=3868) | 4% motor vehicle excise tax | No | Yes | None | | Sourced |
| New York | 4% | Yes, county and city | Yes | $175 | | Secondary |
| North Carolina | 3% highway use tax | No | Yes | None | | Secondary |
| North Dakota | 5% motor vehicle excise tax | No | Yes | None | | Secondary |
| [Ohio](https://codes.ohio.gov/ohio-revised-code/section-5739.01) | 5.75% | Yes, county | New cars only | Lesser of indexed amount or 10% | Dealer sites say used cars too; statute says new only | Sourced |
| [Oklahoma](https://oksenate.gov/press-releases/senate-approves-bill-eliminating-motor-vehicle-sales-tax) | 3.25% excise | No | Unclear | None | 1.25% sales tax repealed July 1, 2025 | Unverified (trade-in) |
| Oregon | None; 0.5% privilege tax on new cars | No | Not applicable | $250 ($200 without e-titling) | | Secondary |
| Pennsylvania | 6% | +1% Allegheny, +2% Philadelphia | Yes | $490 online, $409 manual | | Secondary |
| [Rhode Island](https://tax.ri.gov/sites/g/files/xkgbur541/files/regulations/salestax/11-109.pdf) | 7% | No | Car for car only | $400, plus $20 title prep | Trading in a truck earns no credit | Sourced |
| South Carolina | 5% infrastructure maintenance fee | No | Yes | None | Capped at $500 total | Secondary |
| South Dakota | 4% motor vehicle excise tax | No | Yes | None | | Secondary |
| [Tennessee](https://www.tn.gov/content/tn/revenue/taxes/sales-and-use-tax/single-article-special-tax-rates.html) | 7% | On first $1,600 only | Yes | None | Plus 2.75% on the $1,600 to $3,200 slice | Sourced |
| [Texas](https://occc.texas.gov/sites/default/files/2024-06/mvsf-docfee-adoption-fc-071124.pdf) | 6.25% | No | Yes | $225 safe harbor, not a cap | Above $225 needs a cost filing with the OCCC | Sourced (doc fee); Secondary (tax) |
| Utah | 4.85% | Yes | Yes | None | | Secondary |
| Vermont | 6% purchase and use tax | No | Yes | None | | Secondary |
| [Virginia](https://www.dmv.virginia.gov/vehicles/taxes-fees/sut) | 4.15% | No | No | None | Minimum $75; dealer processing fee is taxed | Sourced |
| [Washington](https://dor.wa.gov/taxes-rates/other-taxes/motor-vehicle-salesuse-tax) | 6.5% + 0.5% motor vehicle tax | Yes | Yes | $200 | Motor vehicle tax rose from 0.3% on Jan 1, 2026 | Sourced (tax); Secondary (doc fee) |
| West Virginia | 6% privilege tax | No | Yes | $575 | | Secondary |
| Wisconsin | 5% | Yes, county | Yes | None | | Secondary |
| Wyoming | 4% | Yes, county | Yes | None | | Secondary |

## Rules a single rate cannot express

The current `jurisdiction_policies` row holds one `tax_rate`, a list of taxable line types and a
trade-in on/off switch. These six patterns need more than that.

| Pattern | States | What the pack needs |
| --- | --- | --- |
| Local tax on only part of the price | Florida (first $5,000), Tennessee (first $1,600) | A capped base per tax component |
| Tiered rates | Tennessee (2.75% on $1,600 to $3,200), Arkansas used cars, Connecticut over $50,000 | Rate bands by price |
| Total tax capped | South Carolina ($500) | A maximum tax amount |
| Floors | Virginia ($75 minimum tax), Maryland ($640 minimum value for old cars), Delaware and Georgia (book or fair market value) | A minimum tax or a book-value base |
| Base is not the price | Georgia (fair market value), Kentucky new cars (90% of MSRP), DC (weight and MPG) | A base other than the agreed price, and inputs the deal doesn't contain |
| Partial trade-in credit | Michigan ($12,000 cap), Kentucky (used only), Ohio (new only), Rhode Island (car for car) | A trade-in cap and conditions on new or used and vehicle type |

Florida, the pilot state, already needs the first pattern: on a $35,000 car in a 1% surtax county
the surtax is $50, not $350.

## Doc fee caps

The 18 capped states set their caps three ways, and the pack needs to know which, because an
indexed cap goes stale on a known date.

- **Fixed amount:** Arkansas, California, Iowa, Maryland, Mississippi, New York, Oregon, Rhode
  Island, Washington. California and Oregon set a lower cap for dealers outside the state's
  electronic titling program.
- **Indexed, reset on a schedule:** Illinois (Jan 1), Indiana (Jul 1), Louisiana, Michigan (every
  two years), Missouri (Feb 1), Ohio (Sep 30), Pennsylvania, West Virginia.
- **Percentage limit too:** Michigan (5%) and Ohio (10%) cap the fee at the lower of a dollar
  amount or a share of the price. Minnesota is reported to work the same way, unconfirmed.

In a capped state, a doc fee over the cap is a legal violation the Buyer can cite. In the 32
uncapped states, including Florida, the check can only compare the fee to what other dealers
charge.

## Gaps

Nothing was missing outright. The gaps are values not confirmed on an official page, or where
sources conflict.

| State | Open question | Where to check |
| --- | --- | --- |
| Oklahoma | Does a trade-in reduce the 3.25% excise tax since the July 2025 repeal? Sources disagree. | Oklahoma Tax Commission, motor vehicle excise guidance |
| Ohio | The statute limits the trade-in credit to new cars, but dealers advertise it on used cars too. | Ohio Department of Taxation vehicle FAQ; ORC 5739.01(H)(2) |
| Michigan | Which year the trade-in cap ends: sources say 2027 or 2029. | Michigan Department of Treasury |
| Indiana, Illinois, Louisiana, Pennsylvania, West Virginia | This year's indexed doc fee amounts. | Each state's dealer licensing agency bulletin |
| Ohio | The current dollar doc fee cap after the September 2026 reset. | Ohio BMV dealer notice under ORC 4517.261 |
| Minnesota | Whether the $350 doc fee cap also has a percentage limit. | Minnesota statute on dealer doc fees |
| Delaware | Effective date of the 5.25% rate. | Delaware DMV fee page |
| DC | Whether a trade-in reduces the excise base. | DC DMV vehicle title page |
| All Secondary rows | Local rate tables by ZIP code or county. | Each state's revenue department rate lookup |

Some rows marked Sourced came from excerpts of official pages returned by search, not a full read.
Read each linked page in full before freezing a jurisdiction pack. Title, registration and plate
fees are not covered here; they vary as much as tax and are a separate pass.

## What this means for the pack

Extend the policy format once so Florida's $5,000 surtax cap fits, and most other states become
data entry. The six patterns above are what the format has to grow to hold.

1. **Florida first.** It needs a capped local component, county surtax rates by county of
   residence, and no doc fee cap to check.
2. **Then one state from each hard pattern,** to prove the format: Tennessee (partial-price local
   tax and a tier), Georgia (fair market value base), and California (no trade-in credit, capped
   doc fee).
3. **Then the simple majority** of about 30 states that fit one rate, a local add-on and a full
   trade-in credit, in order of Buyer demand.
4. **Last, the edge cases:** DC, Kentucky, South Carolina, Michigan and Ohio, once their open
   questions above are closed.

Indexed doc fee caps and county rates change on fixed dates, so each state also needs a review date
in the pack.

## Sources

Official:

- [Florida DOR GT-800019](https://floridarevenue.com/Forms_library/current/gt800019.pdf)
- [Georgia DOR TAVT](https://dor.georgia.gov/motor-vehicles/vehicle-taxes-title-ad-valorem-tax-tavt-and-annual-ad-valorem-tax)
- [Maryland MVA taxable price](https://mva.maryland.gov/your-mva-guide/businesses/bulletins-businesses/taxable-price)
- [Maryland MVA excise tax](https://mva.maryland.gov/title-registration/fees-payment-options/excise-tax)
- [Minnesota DVS sales tax](https://dps.mn.gov/divisions/dvs/vehicle/vehicle-fees/vehicle-sales-tax)
- [Ohio ORC 5739.01](https://codes.ohio.gov/ohio-revised-code/section-5739.01)
- [Ohio ORC 4517.261](https://codes.ohio.gov/ohio-revised-code/section-4517.261/10-24-2024)
- [Tennessee DOR single article tax](https://www.tn.gov/content/tn/revenue/taxes/sales-and-use-tax/single-article-special-tax-rates.html)
- [Kentucky county clerk usage tax](https://letchercountyclerk.ky.gov/motor/taxes-information/)
- [Virginia DMV sales and use tax](https://www.dmv.virginia.gov/vehicles/taxes-fees/sut)
- [Connecticut DRS SN 2011-10](https://portal.ct.gov/DRS/Publications/Special-Notices/2011/SN-2011-10)
- [DC DMV excise tax](https://dmv.dc.gov/node/155502)
- [Rhode Island sales tax regulation](https://tax.ri.gov/sites/g/files/xkgbur541/files/regulations/salestax/11-109.pdf)
- [Rhode Island AG dealer fees](https://riag.ri.gov/autodealers)
- [New Mexico MVD](https://www.mvd.newmexico.gov/?p=3868)
- [Washington DOR motor vehicle tax](https://dor.wa.gov/taxes-rates/other-taxes/motor-vehicle-salesuse-tax)
- [Oklahoma Senate on HB 1295](https://oksenate.gov/press-releases/senate-approves-bill-eliminating-motor-vehicle-sales-tax)
- [California Vehicle Code 4456.5](https://california.public.law/codes/vehicle_code_section_4456.5)
- [Mississippi MMVC doc fee rule](https://www.law.cornell.edu/regulations/mississippi/30-Miss-Code-R-SS-1301-8-1)
- [Missouri DOR administrative fee rule](https://dor.mo.gov/resources/official-final-rules/documents/12_CSR_10-26_231_Maximum_Dealer_Administrative_Fee.pdf)
- [Texas OCCC doc fee rule](https://occc.texas.gov/sites/default/files/2024-06/mvsf-docfee-adoption-fc-071124.pdf)
- [Michigan Senate bill analysis](https://archive.legislature.mi.gov/documents/2015-2016/billanalysis/Senate/htm/2015-SFA-0010-F.htm)

Secondary:

- [statefeecalculator.com tax table](https://statefeecalculator.com/car-sales-tax-by-state/)
- [gettruelane.com doc fee table](https://gettruelane.com/articles/dealer-doc-fee-by-state)
- [carfeecalc.com trade-in guide](https://carfeecalc.com/guides/trade-in-tax-credit-by-state/)
- [Chicago Auto Show on the Illinois trade-in cap](https://www.chicagoautoshow.com/new-illinois-law-removes-trade-in-tax-credit-cap-news/)
