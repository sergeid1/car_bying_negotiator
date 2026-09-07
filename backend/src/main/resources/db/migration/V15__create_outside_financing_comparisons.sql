-- Composite foreign keys enforce ownership without cascading into verified offers.
alter table finance_offers add constraint uq_finance_offer_case unique(case_id, id);
create table outside_finance_offers (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  dealer_finance_offer_id varchar(36) not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, id),
  unique(case_id, version),
  unique(case_id, idempotency_key),
  foreign key(case_id, dealer_finance_offer_id) references finance_offers(case_id, id)
);
create table financing_comparisons (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  outside_finance_offer_id varchar(36) not null,
  policy_version varchar(100) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, version),
  unique(outside_finance_offer_id, policy_version),
  foreign key(case_id, outside_finance_offer_id) references outside_finance_offers(case_id, id)
);
