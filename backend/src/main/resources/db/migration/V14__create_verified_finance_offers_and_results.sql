create table finance_offers (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  dealer_offer_id varchar(36) not null references dealer_offers(id),
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, version),
  unique(case_id, idempotency_key)
);
create table financing_results (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  finance_offer_id varchar(36) not null references finance_offers(id),
  policy_version varchar(100) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, version),
  unique(finance_offer_id, policy_version)
);
