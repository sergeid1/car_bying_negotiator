create table deal_term_corrections (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  extraction_id varchar(36) not null references document_extractions(id),
  target_type varchar(20) not null,
  target_id varchar(36) not null,
  correction_version integer not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  normalized_value varchar(500),
  normalized_unit varchar(32),
  normalized_category varchar(64),
  amount_minor bigint,
  actor varchar(32) not null,
  created_at timestamp with time zone not null,
  unique(extraction_id, target_type, target_id, correction_version),
  unique(case_id, idempotency_key),
  constraint ck_correction_version_positive check (correction_version > 0),
  constraint ck_correction_target_type check (target_type in ('CANDIDATE', 'LINE_ITEM'))
);

create index idx_corrections_target
  on deal_term_corrections(extraction_id, target_type, target_id, correction_version desc);

create table dealer_offers (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  extraction_id varchar(36) not null references document_extractions(id),
  offer_version integer not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  status varchar(20) not null,
  schema_version varchar(100) not null,
  terms_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, offer_version),
  unique(case_id, idempotency_key),
  constraint ck_offer_version_positive check (offer_version > 0)
);

create index idx_dealer_offers_latest on dealer_offers(case_id, offer_version desc);

create table deal_term_verifications (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  extraction_id varchar(36) not null references document_extractions(id),
  dealer_offer_id varchar(36) not null references dealer_offers(id),
  target_type varchar(20) not null,
  target_id varchar(36) not null,
  verification_version integer not null,
  effective_value varchar(500) not null,
  actor varchar(32) not null,
  created_at timestamp with time zone not null,
  unique(dealer_offer_id, target_type, target_id),
  unique(extraction_id, target_type, target_id, verification_version),
  constraint ck_verification_version_positive check (verification_version > 0)
);

create index idx_term_verifications_extraction
  on deal_term_verifications(extraction_id, target_type, target_id, verification_version desc);

create table audit_events (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  event_type varchar(64) not null,
  aggregate_type varchar(32) not null,
  aggregate_id varchar(36) not null,
  aggregate_version integer not null,
  idempotency_key varchar(200) not null,
  actor varchar(32) not null,
  metadata_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, event_type, idempotency_key),
  constraint ck_audit_version_positive check (aggregate_version > 0)
);

create index idx_audit_events_case on audit_events(case_id, created_at, id);
