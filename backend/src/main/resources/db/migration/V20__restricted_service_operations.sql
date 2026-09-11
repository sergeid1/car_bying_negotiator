create table provider_usage (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  operation varchar(40) not null,
  provider varchar(100) not null,
  provider_version varchar(100) not null,
  model_version varchar(100) not null,
  schema_version varchar(100) not null,
  license varchar(100) not null,
  unit varchar(20) not null,
  units bigint not null check (units >= 0),
  cost_usd numeric(18,8),
  status varchar(40) not null,
  created_at timestamp with time zone not null,
  finished_at timestamp with time zone
);
create index provider_usage_case on provider_usage(case_id, created_at);

create table human_review_requests (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  extraction_id varchar(36) not null references document_extractions(id),
  candidate_id varchar(36) not null references candidate_deal_terms(id),
  consent_version integer not null,
  state varchar(32) not null,
  reviewer varchar(64),
  expires_at timestamp with time zone,
  outcome varchar(40),
  created_at timestamp with time zone not null
);
create index human_review_queue on human_review_requests(state, created_at);
create index human_review_case on human_review_requests(case_id);
create table operator_audit (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  review_id varchar(36) not null references human_review_requests(id),
  actor varchar(64) not null,
  action varchar(40) not null,
  occurred_at timestamp with time zone not null
);
create index operator_audit_case on operator_audit(case_id, occurred_at);

-- Historical payloads are deliberately not read for backfill; {} denotes unavailable metadata.
alter table analysis_reports add column policy_metadata text not null default '{}';

alter table operations add column failure_category varchar(40) not null default 'NONE';
update operations set failure_category = 'UNCLASSIFIED' where stage = 'FAILED';
