create table jurisdiction_policies (
  policy_version varchar(100) primary key,
  jurisdiction varchar(64) not null,
  currency varchar(3) not null,
  tax_rate numeric(12, 10) not null,
  taxable_line_types varchar(500) not null,
  trade_tax_credit_enabled boolean not null,
  currency_scale integer not null,
  rounding_mode varchar(32) not null,
  reconciliation_tolerance numeric(19, 4) not null,
  policy_json text not null,
  effective_at timestamp with time zone not null,
  unique(jurisdiction, policy_version)
);

insert into jurisdiction_policies (
  policy_version, jurisdiction, currency, tax_rate, taxable_line_types,
  trade_tax_credit_enabled, currency_scale, rounding_mode,
  reconciliation_tolerance, policy_json, effective_at
) values (
  'US-NC.cash.v1', 'US-NC', 'USD', 0.0300000000, 'SELLING_PRICE,DEALER_FEE,ADD_ON',
  true, 2, 'HALF_UP', 1.0000,
  '{"scope":"configured pilot cash calculation","taxCreditCap":"eligible taxable basis"}',
  '2026-09-04T00:00:00Z'
);

create table cash_economics_results (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  dealer_offer_id varchar(36) not null references dealer_offers(id),
  policy_version varchar(100) not null references jurisdiction_policies(policy_version),
  eligibility_fingerprint varchar(64) not null,
  schema_version varchar(100) not null,
  result_json text not null,
  created_at timestamp with time zone not null,
  unique(dealer_offer_id, policy_version, eligibility_fingerprint)
);

create index idx_cash_economics_latest on cash_economics_results(case_id, created_at desc, id);
