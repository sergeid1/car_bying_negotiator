-- Florida resident dealer purchases, checked against the full official publications 2026-10-12.
-- County rates: DR-15DSS, Calendar Year 2026, R. 11/25, both pages.
-- https://floridarevenue.com/Forms_library/current/dr15dss_26.pdf
-- GT-800019 R. 10/25 and GT-800030 R. 08/22 are now under current/brochure/.
-- No title/registration/plate/tire/battery fee schedule is provided by this pack.
create table florida_county_surtaxes (
  county_name varchar(64) primary key,
  rate numeric(12,10) not null,
  publication_year integer not null default 2026,
  citation varchar(500) not null default 'Florida DOR DR-15DSS Calendar Year 2026 R.11/25 https://floridarevenue.com/Forms_library/current/dr15dss_26.pdf',
  checked_on date not null default '2026-10-12',
  review_on date not null default '2027-01-01'
);

insert into florida_county_surtaxes (county_name, rate) values
('Alachua', .015), ('Baker', .01), ('Bay', .01), ('Bradford', .01),
('Brevard', .01), ('Broward', .01), ('Calhoun', .015), ('Charlotte', .01),
('Citrus', 0), ('Clay', .015), ('Collier', 0), ('Columbia', .015),
('DeSoto', .015), ('Dixie', .01), ('Duval', .015), ('Escambia', .015),
('Flagler', .01), ('Franklin', .015), ('Gadsden', .015), ('Gilchrist', .01),
('Glades', .01), ('Gulf', .01), ('Hamilton', .02), ('Hardee', .01),
('Hendry', .015), ('Hernando', .005), ('Highlands', .015), ('Hillsborough', .015),
('Holmes', .015), ('Indian River', .01), ('Jackson', .015), ('Jefferson', .01),
('Lafayette', .01), ('Lake', .01), ('Lee', .005), ('Leon', .015),
('Levy', .01), ('Liberty', .015), ('Madison', .015), ('Manatee', .01),
('Marion', .015), ('Martin', .005), ('Miami-Dade', .01), ('Monroe', .015),
('Nassau', .01), ('Okaloosa', .01), ('Okeechobee', .01), ('Orange', .005),
('Osceola', .015), ('Palm Beach', .005), ('Pasco', .01), ('Pinellas', .01),
('Polk', .01), ('Putnam', .01), ('St. Johns', .005), ('St. Lucie', .01),
('Santa Rosa', .01), ('Sarasota', .01), ('Seminole', .01), ('Sumter', .01),
('Suwannee', .01), ('Taylor', .01), ('Union', .01), ('Volusia', .005),
('Wakulla', .015), ('Walton', .01), ('Washington', .015);

-- Each county is an immutable scope of the US-FL.cash.2026.v1 pack. There is deliberately
-- no county-free calculation fallback. The case records its county in buyer_jurisdiction.
insert into jurisdiction_policies (
  policy_version, jurisdiction, currency, tax_rate, taxable_line_types,
  trade_tax_credit_enabled, currency_scale, rounding_mode, reconciliation_tolerance,
  policy_json, effective_at
)
select 'US-FL.cash.2026.v1:' || county_name, 'US-FL:' || county_name, 'USD', .06,
  'SELLING_PRICE,DEALER_FEE,ADD_ON', true, 2, 'HALF_UP', 1,
  '{"format":"jurisdiction-tax-components.v1","packVersion":"US-FL.cash.2026.v1",'
  || '"scope":"Florida resident purchase from Florida dealer","county":"' || county_name || '",'
  || '"reviewOn":"2027-01-01","checkedOn":"2026-10-12",'
  || '"tradeCreditCitation":"Florida Statutes 2026 s.212.09 https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.09.html",'
  || '"taxableLinesCitation":"Florida DOR GT-800030 R.08/22 https://floridarevenue.com/Forms_library/current/brochure/gt800030.pdf",'
  || '"countyResidenceCitation":"Florida Statutes 2026 s.212.054(3)(a)2 https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.054.html",'
  || '"taxComponents":[{"name":"Florida state sales tax","rate":0.06,"base":"TAXABLE_PRICE_AFTER_TRADE","baseCap":null,'
  || '"citation":"Florida Statutes 2026 s.212.05(1)(a)1 https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.05.html","checkedOn":"2026-10-12"},'
  || '{"name":"' || county_name || ' County surtax (first $5,000 after trade-in)","rate":' || cast(rate as varchar)
  || ',"base":"TAXABLE_PRICE_AFTER_TRADE","baseCap":5000,'
  || '"citation":"' || citation || '; cap: Florida Statutes 2026 s.212.054(2)(b)1 https://www.leg.state.fl.us/statutes/index.cfm?App_mode=Display_Statute&URL=0200-0299/0212/Sections/0212.054.html; Florida DOR GT-800019 R.10/25 https://floridarevenue.com/Forms_library/current/brochure/gt800019.pdf","checkedOn":"2026-10-12"}]}',
  '2026-01-01T00:00:00Z'
from florida_county_surtaxes;

-- Preserve deterministic development/acceptance fixtures. This is not a sourced NC rules pack.
update jurisdiction_policies set policy_json =
  '{"scope":"synthetic development fixture only; not a sourced North Carolina pack","taxCreditCap":"eligible taxable basis"}'
where policy_version = 'US-NC.cash.v1';
