create table negotiation_drafts (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  quote_request_id varchar(36) not null references quote_requests(id),
  mandate_id varchar(36) not null references negotiation_mandates(id),
  report_version integer not null,
  dealer_offer_id varchar(36) not null references dealer_offers(id),
  proposal_minor bigint,
  grounds_json text not null,
  state varchar(32) not null,
  cancel_reason varchar(32),
  message_id varchar(36) references dealer_relay_messages(id),
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  created_at timestamp with time zone not null,
  decided_at timestamp with time zone,
  unique(case_id, idempotency_key)
);
create index idx_negotiation_drafts_case on negotiation_drafts(case_id, created_at);
create table negotiation_draft_revisions (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  negotiation_draft_id varchar(36) not null references negotiation_drafts(id),
  revision integer not null,
  outbound_draft_id varchar(36) not null unique references outbound_drafts(id),
  edited_by_buyer boolean not null,
  created_at timestamp with time zone not null,
  unique(negotiation_draft_id, revision)
);
