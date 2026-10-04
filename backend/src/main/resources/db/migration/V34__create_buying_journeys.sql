-- A Buying Journey is one Buyer Account's search for one car. Account deletion removes both
-- tables by account_id (AccountDeletionWorker), child before parent.
create table buying_journeys (
  id varchar(36) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  request_key_hash varchar(64) not null,
  state varchar(24) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique (account_id, request_key_hash),
  check (state in ('SHOPPING', 'NEGOTIATING', 'AGREED', 'TEST_DRIVE_SCHEDULED', 'CLOSED', 'ABANDONED'))
);
create index buying_journeys_account on buying_journeys(account_id, created_at);

-- Shopping Brief versions are immutable: an edit inserts the next version. Make, model, trim and
-- feature columns hold identifiers from the vehicle reference list named by reference_list_version;
-- list columns are JSON arrays of those identifiers.
create table shopping_brief_versions (
  journey_id varchar(36) not null references buying_journeys(id),
  version integer not null,
  account_id varchar(36) not null references buyer_accounts(id),
  reference_list_version varchar(64) not null,
  year_from integer not null,
  year_to integer not null,
  make_id varchar(64) not null,
  model_id varchar(64) not null,
  trim_ids varchar(2048) not null,
  max_mileage integer not null,
  price_ceiling_minor bigint not null,
  currency varchar(3) not null,
  postal_code varchar(5) not null,
  jurisdiction varchar(64) not null,
  radius_miles integer not null,
  must_have_features varchar(2048) not null,
  nice_to_have_features varchar(2048) not null,
  created_at timestamp with time zone not null,
  primary key (journey_id, version),
  check (version >= 1),
  check (year_from <= year_to),
  check (max_mileage >= 0),
  check (price_ceiling_minor > 0),
  check (radius_miles > 0)
);
create index shopping_brief_versions_account on shopping_brief_versions(account_id);
