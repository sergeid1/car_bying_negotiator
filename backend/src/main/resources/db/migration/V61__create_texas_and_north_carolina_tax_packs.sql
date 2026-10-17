-- Texas and North Carolina resident purchases from an in-state dealer (ticket 137), each checked against the
-- full official text on 2026-10-16. One state-level component, no county table, no local tax.
--
-- Texas: Tax Code ch. 152 (s.152.002 total consideration, s.152.021 6.25% rate, s.152.0411 dealer collection),
--   read at https://statutes.capitol.texas.gov/Docs/TX/htm/TX.152.htm (text served from
--   https://tcss.legis.texas.gov/resources/TX/htm/TX.152.htm), and Comptroller Motor Vehicle Tax Guide 96-254
--   (3/2026): Tax Rates, Motor Vehicle Sales Tax, Total Consideration, Trade-Ins, Accessories and Equipment.
--   A separately stated documentary fee, service contract, maintenance agreement, roadside assistance,
--   insurance, auto club membership, debt cancellation agreement and vehicle inventory tax reimbursement are
--   not total consideration; accessories attached at the sale are.
-- North Carolina: G.S. 105 Article 5A (105-187.3 base, rate and maximums; 105-187.4 payment at titling) and
--   G.S. 20-101.1 (dealer administrative fees), https://www.ncleg.gov/, and NCDMV MVR-106 Rev. 04/26:
--   "Dealer Sale - 3% of sale price plus any Administrative/Document Fee less any trade-in price."
--   The $2,000 maximum applies only to Class A/B commercial vehicles and recreational vehicles.
-- Labels in nonTaxableCharges match at a word start, case-insensitively, and only on separately stated lines.
-- No title, registration, plate or inspection fee schedule is provided by these packs.
insert into jurisdiction_policies (
  policy_version, jurisdiction, currency, tax_rate, taxable_line_types,
  trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance,
  policy_json, effective_at
) values (
  'US-TX.cash.2026.v1', 'US-TX', 'USD', .0625, 'SELLING_PRICE,DEALER_FEE,ADD_ON', true, 2, 'HALF_UP', 1,
  '{"format":"jurisdiction-tax-components.v1","packVersion":"US-TX.cash.2026.v1",'
  || '"scope":"Texas resident purchase from a Texas licensed dealer","reviewOn":"2027-01-01","checkedOn":"2026-10-16",'
  || '"taxName":"Texas motor vehicle sales tax","paidAtTitling":false,'
  || '"tradeCreditCitation":"Texas Tax Code s.152.002(b)(5) https://statutes.capitol.texas.gov/Docs/TX/htm/TX.152.htm; Comptroller 96-254 (3/2026) Trade-Ins https://comptroller.texas.gov/taxes/publications/96-254/trade-ins.php and Motor Vehicle Sales Tax https://comptroller.texas.gov/taxes/publications/96-254/mv-sales.php",'
  || '"taxableLinesCitation":"Texas Tax Code s.152.002 https://statutes.capitol.texas.gov/Docs/TX/htm/TX.152.htm; Comptroller 96-254 (3/2026) Total Consideration https://comptroller.texas.gov/taxes/publications/96-254/total-consideration.php and Accessories and Equipment https://comptroller.texas.gov/taxes/publications/96-254/accessories-equipment.php",'
  || '"nonTaxableCharges":{"DEALER_FEE":["doc","documentary","documentation","inventory tax"],'
  || '"ADD_ON":["service contract","warranty","extended warranty","maintenance","roadside","gap","debt cancellation","insurance","auto club","club membership"]},'
  || '"taxComponents":[{"name":"Texas motor vehicle sales tax","rate":0.0625,"base":"TAXABLE_PRICE_AFTER_TRADE","baseCap":null,'
  || '"citation":"Texas Tax Code s.152.021(b) https://statutes.capitol.texas.gov/Docs/TX/htm/TX.152.htm; Comptroller Motor Vehicle Tax Guide 96-254 (3/2026) Tax Rates https://comptroller.texas.gov/taxes/publications/96-254/tax-rates.php","checkedOn":"2026-10-16"}]}',
  '2026-01-01T00:00:00Z'
), (
  'US-NC.cash.2026.v1', 'US-NC', 'USD', .03, 'SELLING_PRICE,DEALER_FEE,ADD_ON', true, 2, 'HALF_UP', 1,
  '{"format":"jurisdiction-tax-components.v1","packVersion":"US-NC.cash.2026.v1",'
  || '"scope":"North Carolina resident purchase of a passenger vehicle from a North Carolina retailer","reviewOn":"2027-01-01","checkedOn":"2026-10-16",'
  || '"taxName":"North Carolina highway use tax","paidAtTitling":true,'
  || '"buyerNote":"North Carolina collects the highway use tax when the car is titled. The dealer may collect it with the sale, or you pay it when you title the car, so a quote can leave it out. It is still owed, and the total here includes it.",'
  || '"tradeCreditCitation":"N.C.G.S. 105-187.3(b) https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html; NCDMV MVR-106 Rev. 04/26 https://www.ncdot.gov/dmv/downloads/Documents/MVR-106.pdf",'
  || '"taxableLinesCitation":"N.C.G.S. 105-187.3(a) and (b) https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html; N.C.G.S. 20-101.1 https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/BySection/Chapter_20/GS_20-101.1.html",'
  || '"titlingCitation":"N.C.G.S. 105-187.4 https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html",'
  || '"nonTaxableCharges":{"DEALER_FEE":["online registration"],'
  || '"ADD_ON":["service contract","warranty","extended warranty","maintenance","roadside","gap","insurance"]},'
  || '"taxComponents":[{"name":"North Carolina highway use tax","rate":0.03,"base":"TAXABLE_PRICE_AFTER_TRADE","baseCap":null,'
  || '"citation":"N.C.G.S. 105-187.3(a1) https://www.ncleg.gov/EnactedLegislation/Statutes/HTML/ByArticle/Chapter_105/Article_5A.html; NCDMV MVR-106 Rev. 04/26 https://www.ncdot.gov/dmv/downloads/Documents/MVR-106.pdf","checkedOn":"2026-10-16"}]}',
  '2026-01-01T00:00:00Z'
);

-- The synthetic US-NC.cash.v1 moves to a fixture-only jurisdiction that no postal code resolves to, so no
-- North Carolina Buyer gets it. Its policy_version is unchanged, so frozen results still name it.
update jurisdiction_policies set jurisdiction = 'SYNTHETIC-FIXTURE'
where policy_version = 'US-NC.cash.v1';
