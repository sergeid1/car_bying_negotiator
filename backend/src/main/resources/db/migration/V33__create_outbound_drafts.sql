create table outbound_drafts (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  dealer_id varchar(36) not null references dealers(id),
  mandate_id varchar(36) references negotiation_mandates(id),
  draft_text text not null,
  gate_version varchar(64) not null,
  decision_json text not null,
  context_json text not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  created_at timestamp with time zone not null,
  unique(case_id, idempotency_key)
);
create index idx_outbound_drafts_case on outbound_drafts(case_id, created_at);
