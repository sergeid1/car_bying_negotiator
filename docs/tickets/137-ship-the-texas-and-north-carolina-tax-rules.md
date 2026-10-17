# 137 — Ship the Texas and North Carolina tax rules

**What to build:** Sourced Texas and North Carolina jurisdiction packs, so Buyers in both states can open a case
and see the right tax, trade-in tax credit (ticket 103) and cash economics. The pilot now covers three states:
Florida, Texas and North Carolina (product owner decision, 2026-10-16, specification 2.4). Both new states fit the
`jurisdiction-tax-components.v1` format from ticket 108 as one state-level component with no county table:

- **Texas:** motor vehicle sales tax of 6.25% of the sales price, less the trade-in allowance when a licensed dealer
  takes the trade. No local motor vehicle sales tax.
- **North Carolina:** highway use tax of 3% of the retail value, less the trade-in allowance on a dealer sale. No
  local tax. The $2,000 maximum applies only to commercial and recreational vehicles, not to passenger cars.

Today `US-NC.cash.v1` is a synthetic development fixture, so a North Carolina Buyer gets made-up numbers labelled as a
fixture, and a Texas Buyer is told the state is not supported. Research:
`docs/research/state-vehicle-tax-and-fee-rules.md` (official-source check of 2026-10-16).

**Blocked by:** none in the dependency graph. Like ticket 108, it touches tax calculation, the trade check (103, 106)
and case intake, so do not run it in parallel with another ticket that changes those.

**Status:** open — filed on 2026-10-16.

**Sources to read in full, not from search excerpts** (the 2026-10-16 check used search excerpts of these pages;
each value is checked against the publication before it ships):

- Texas: Tax Code chapter 152 (motor vehicle sales and use tax, including the trade-in allowance); the Comptroller's
  [motor vehicle sales and use tax page](https://comptroller.texas.gov/taxes/motor-vehicle/sales-use.php) and the
  Motor Vehicle Tax Guide 96-254, in particular [Tax Rates](https://comptroller.texas.gov/taxes/publications/96-254/tax-rates.php),
  [Credit](https://comptroller.texas.gov/taxes/publications/96-254/credit.php) and
  [the sales section](https://comptroller.texas.gov/taxes/publications/96-254/mv-sales.php), which also say which
  dealer charges are part of the taxable sales price.
- North Carolina: G.S. chapter 105, [Article 5A](https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html)
  (105-187.1 definitions of retail value and sales price, 105-187.3 rate and maximums) and the NCDOR and NCDMV
  highway use tax guidance, including which dealer charges are in the taxable value.

## Scope

- A `US-TX` pack and a sourced `US-NC` pack, effective-dated, each with one state component on
  `TAXABLE_PRICE_AFTER_TRADE`. Every value stores its citation (statute section or publication, year, URL) and the
  date it was checked, and each pack has a review date. Use the next free migration.
- The pack's taxable line types follow each state's own definition of the taxable price. Where a state's rule cannot
  be expressed with the existing line types (for example a dealer charge that one state taxes and Florida does not),
  extend the pack data, not the shared calculation, and document it in `docs/jurisdiction-tax-packs.md`.
- The trade-in credit applies as each source states it: Texas when a licensed dealer takes the trade, North Carolina
  on a dealer sale. A private-party sale is out of scope for both.
- Shipping the packs makes both states supported in `SupportedJurisdictions`. Neither needs a county: intake must
  not ask a Texas or North Carolina Buyer for one, and a ZIP code that crosses a state line still asks for the state,
  as it does today.
- The synthetic `US-NC.cash.v1` must no longer be what a North Carolina Buyer gets. Either move the deterministic
  fixtures to a fixture-only jurisdiction that no Buyer location resolves to, or retire it and update the fixtures;
  record the choice in `docs/jurisdiction-tax-packs.md`. Frozen reports keep the pack version they were made with.
- Buyer-facing tax copy names the tax the way the state does: Texas "motor vehicle sales tax", North Carolina
  "highway use tax". In North Carolina the tax is paid when the car is titled, not always as a dealer invoice line, so
  copy and the desk check must not call a missing invoice line an error.
- Florida-specific Buyer copy that is not tax (for example the Florida insurance minimums in
  `InsuranceShoppingService` and `InsuranceQuotes.tsx`, and the Florida default state in the insurance form) is out
  of scope here; list each place found in the ticket's notes when it closes so it can be filed separately.

## Done when

- [ ] `US-TX` and `US-NC` packs ship with citations, checked dates and review dates, and
      `docs/jurisdiction-tax-packs.md` has a section for each with its sources and worked examples.
- [ ] Tests cover, with labelled synthetic cases, for each state: no trade-in; a trade-in that reduces the taxable
      price; a trade-in larger than the price (tax floors at zero); an Agreed Price and a fresh desk case; and the
      trade comparison showing the tax the Buyer would lose by selling the car elsewhere.
- [ ] An acceptance test opens a Texas case and a North Carolina case end to end without asking for a county, and a
      Buyer in a state with no pack still gets the existing "not supported yet" message.
- [ ] No North Carolina Buyer location resolves to the synthetic fixture, and the choice made for it is recorded.
- [ ] `docs/pilot-readiness-record.md` updates the `jurisdiction-pack` prerequisite with both packs' versions and
      sources. Its status changes only by the operator.
- [ ] Buyer-facing copy passes `ClaimVocabulary` and `PilotCopyClaimsTest`.
- [ ] Out of scope, and said in the ticket's notes when it closes: title, registration, plate and inspection fees;
      Texas new-resident and even-trade taxes and standard presumptive value (private sales); dealer doc fee caps (Texas
      has a $225 safe harbor, North Carolina no cap; nothing in the product checks doc fee caps yet); non-resident
      purchases; vehicles bought out of state; and the non-tax Florida-specific copy found above.
