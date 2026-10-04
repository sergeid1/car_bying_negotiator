alter table provider_usage drop constraint provider_usage_scope;
alter table provider_usage add constraint provider_usage_scope check (
  (case_id is not null and journey_id is null and account_id is null) or
  (case_id is null and journey_id is not null and account_id is not null and
    ((operation = 'INVENTORY_SEARCH' and query_json is not null and result_count >= 0 and retrieved_at is not null)
      or operation = 'MARKET_RETRIEVAL'))
);

-- Inventory providers identify a listing by source and ID even when they provide no public URL.
alter table listing_snapshots alter column listing_url drop not null;
-- Incomplete captured inventory is evidence awaiting Buyer confirmation, never an invented identity.
alter table vehicles drop constraint ck_vehicles_supported_identity;
alter table vehicles add constraint ck_vehicles_supported_identity check (
  confirmation_source = 'LISTING_EVIDENCE' or vin is not null or
  (model_year is not null and make is not null and model is not null and mileage is not null)
);

create table journey_candidates (
  id varchar(36) primary key,
  journey_id varchar(36) not null references buying_journeys(id),
  account_id varchar(36) not null references buyer_accounts(id),
  brief_version integer not null,
  search_run_id varchar(36) not null,
  matcher_version varchar(100) not null,
  candidate_json text not null,
  listing_json text not null,
  market_json text not null,
  provider_version varchar(200) not null,
  shortlist_position integer,
  created_at timestamp with time zone not null,
  constraint shortlist_position_nonnegative check (shortlist_position is null or shortlist_position >= 0)
);
create index journey_candidates_journey on journey_candidates(journey_id);
create index journey_candidates_account on journey_candidates(account_id);

create table journey_candidate_cases (
  case_id varchar(36) primary key references analysis_cases(id),
  journey_id varchar(36) not null references buying_journeys(id),
  account_id varchar(36) not null references buyer_accounts(id),
  candidate_id varchar(36) not null unique references journey_candidates(id),
  credential_ciphertext text not null,
  created_at timestamp with time zone not null
);
create index journey_candidate_cases_journey on journey_candidate_cases(journey_id);
create index journey_candidate_cases_account on journey_candidate_cases(account_id);
