create table quote_requests (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  draft_id varchar(36) not null references outbound_drafts(id),
  dealer_address varchar(254) not null,
  state varchar(32) not null,
  message_id varchar(36),
  approved_at timestamp with time zone,
  created_at timestamp with time zone not null,
  unique(draft_id)
);
create index idx_quote_requests_case on quote_requests(case_id);
create table case_negotiation_states (
  case_id varchar(36) primary key references analysis_cases(id),
  mandate_id varchar(36),
  state varchar(32) not null check (state in ('NO_MANDATE','MANDATE_ACTIVE','AWAITING_DEALER','AWAITING_BUYER','AGREED','STOPPED','EXPIRED')),
  updated_at timestamp with time zone not null
);
