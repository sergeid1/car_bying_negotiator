create table quote_acceptance_requests (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  quote_request_id varchar(36) not null references quote_requests(id),
  draft_id varchar(36) not null references outbound_drafts(id),
  offer_version integer,
  plan_version integer,
  evidence_message_id varchar(36) references dealer_relay_messages(id),
  evidence_document_id varchar(36) references source_documents(id),
  kind varchar(24) not null check (kind in ('ACCEPTANCE', 'WRITTEN_CONFIRMATION')),
  state varchar(24) not null,
  message_id varchar(36),
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  created_at timestamp with time zone not null,
  unique(case_id, idempotency_key)
);
create table agreed_prices (
  id varchar(36) primary key,
  case_id varchar(36) not null unique references analysis_cases(id),
  acceptance_request_id varchar(36) not null unique references quote_acceptance_requests(id),
  dealer_offer_id varchar(36) not null references dealer_offers(id),
  offer_version integer not null,
  baseline_id varchar(36) not null references price_plan_snapshots(id),
  baseline_version integer not null,
  actor varchar(24) not null check (actor = 'BUYER'),
  accepted_at timestamp with time zone not null,
  evidence_message_id varchar(36) not null references dealer_relay_messages(id),
  evidence_document_id varchar(36) not null references source_documents(id),
  journey_result_json text not null
);
