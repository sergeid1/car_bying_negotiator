create table finding_feedback (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  report_version integer not null,
  finding_pointer varchar(160) not null,
  accurate varchar(10) not null,
  understandable varchar(10) not null,
  useful varchar(10) not null,
  created_at timestamp with time zone not null,
  unique(case_id, report_version, finding_pointer)
);

create table outcome_feedback (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  stage varchar(10) not null,
  action varchar(24),
  useful varchar(10),
  trust varchar(10),
  live_use boolean,
  understood_decision boolean,
  disappointment varchar(24),
  willing_to_pay boolean,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, stage)
);
create table pilot_events (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  event_type varchar(32) not null,
  category varchar(40) not null,
  duration_millis bigint,
  created_at timestamp with time zone not null
);
create index pilot_events_case on pilot_events(case_id, event_type);

create table pilot_protocol (
  id integer primary key,
  protocol_json text,
  frozen_at timestamp with time zone,
  reviewed_at timestamp with time zone,
  frozen_by varchar(64)
);
insert into pilot_protocol(id) values(1);
create table experiment_assignments (
  id varchar(36) primary key,
  case_id varchar(36) not null unique references analysis_cases(id),
  variant varchar(1) not null,
  price_minor bigint not null,
  qualified boolean not null,
  enrolled_by varchar(64) not null,
  created_at timestamp with time zone not null
);
create table pilot_observations (
  id varchar(36) primary key,
  metric varchar(48) not null,
  numerator bigint not null check (numerator >= 0),
  denominator bigint not null check (denominator > 0 and numerator <= denominator),
  source varchar(32) not null,
  recorded_by varchar(64) not null,
  created_at timestamp with time zone not null
);
create index pilot_observations_metric on pilot_observations(metric);

-- Fixed redacted workflow schema; no payloads, corrected values, keys, VINs, dealer identities or prose.
create view pilot_workflow_events as
select concat('pilot:', id) as id, case_id, event_type, category, duration_millis, created_at from pilot_events
union all
select concat('synthetic-report:', o.id), o.case_id, 'SYNTHETIC_REPORT_COMPLETED', 'COMPLETE',
  cast(extract(epoch from (o.updated_at - o.created_at)) * 1000 as bigint), o.updated_at
  from operations o join analysis_cases c on c.id = o.case_id where o.stage = 'COMPLETE' and c.buyer_jurisdiction is null
union all
select concat('correction:', id), case_id, 'CORRECTION', 'RECORDED', cast(null as bigint), created_at from deal_term_corrections
union all
select concat('verified:', id), case_id, 'VERIFIED_EXTRACTION', 'VERIFIED', cast(null as bigint), created_at from dealer_offers
union all
select concat('upload:', id), case_id, 'UPLOAD', 'ACCEPTED', cast(null as bigint), created_at from source_documents
union all
select concat('extraction:', e.id), e.case_id, 'REVIEW_READY_EXTRACTION', 'RECORDED',
  cast(extract(epoch from (e.created_at - d.created_at)) * 1000 as bigint), e.created_at
  from document_extractions e join source_documents d on d.id = e.document_id
union all
select concat('operation:', id), case_id, 'ANALYSIS_ERROR',
  case when failure_category in ('PROVIDER_FAILURE', 'CONTRACT_FAILURE', 'BUYER_INPUT') then failure_category else 'INTERNAL' end,
  cast(extract(epoch from (updated_at - created_at)) * 1000 as bigint), updated_at from operations where stage = 'FAILED'
union all
select concat('document:', id), case_id, 'DOCUMENT_ERROR',
  case when error_code in ('PROVIDER_FAILURE', 'CONTRACT_FAILURE') then error_code else 'PROCESSING_FAILURE' end,
  cast(extract(epoch from (updated_at - created_at)) * 1000 as bigint), updated_at from document_processing_operations where stage = 'FAILED'
union all
select concat('usage:', id), case_id, 'PROVIDER_USAGE',
  case when status in ('STARTED', 'SUCCESS', 'PROVIDER_FAILURE', 'CONTRACT_FAILURE', 'PROVIDER_UNAVAILABLE', 'BUYER_INPUT', 'INTERNAL') then status else 'OTHER' end,
  cast(extract(epoch from (finished_at - created_at)) * 1000 as bigint), created_at from provider_usage
union all
select concat('deletion:', id), case_id, 'DELETION', state,
  cast(extract(epoch from (completed_at - requested_at)) * 1000 as bigint), requested_at from case_deletion_jobs;

-- Irreversible anonymous counters retain only cohort size and completion numerators after case deletion.
-- No Buyer, case, dealer, variant, price, feedback text or document identifier is retained here.
create table pilot_cohort_totals (
  id integer primary key,
  enrolled_count bigint not null default 0,
  removed_count bigint not null default 0,
  removed_completed_count bigint not null default 0,
  removed_understood_count bigint not null default 0
);
insert into pilot_cohort_totals(id) values(1);
alter table pilot_observations add column total_delta_minor bigint;
