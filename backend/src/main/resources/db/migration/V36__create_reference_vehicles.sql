-- Reference Vehicles belong to one immutable Shopping Brief version: adding, removing or re-marking
-- one inserts the next brief version with copied rows. Reference VINs are personal data; journey
-- deletion (JourneyService) and account deletion (AccountDeletionWorker) remove these rows before
-- the brief versions they belong to. attributes is a JSON array of {attribute, value, relevant}
-- holding only what the decoder supplied; source and decoder_version name the decoder that did.
create table reference_vehicles (
  journey_id varchar(36) not null,
  brief_version integer not null,
  position integer not null,
  reference_id varchar(36) not null,
  account_id varchar(36) not null references buyer_accounts(id),
  vin varchar(17) not null,
  decode_outcome varchar(16) not null,
  source varchar(64) not null,
  decoder_version varchar(64) not null,
  decoded_at timestamp with time zone not null,
  attributes varchar(4096) not null,
  primary key (journey_id, brief_version, position),
  foreign key (journey_id, brief_version) references shopping_brief_versions(journey_id, version),
  unique (journey_id, brief_version, reference_id),
  unique (journey_id, brief_version, vin),
  check (position between 1 and 5),
  check (decode_outcome in ('DECODED', 'UNDECODABLE'))
);
create index reference_vehicles_account on reference_vehicles(account_id);
