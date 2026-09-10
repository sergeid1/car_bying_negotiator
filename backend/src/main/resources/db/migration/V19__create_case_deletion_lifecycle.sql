alter table analysis_cases add column lifecycle varchar(32) not null default 'ACTIVE';
alter table analysis_cases add column document_retention_days integer;
create table case_deletion_jobs (
  id varchar(36) primary key,
  case_id varchar(36) not null unique,
  receipt_hash varchar(64),
  state varchar(32) not null,
  reason varchar(32) not null,
  requested_at timestamp with time zone not null,
  last_attempt_at timestamp with time zone,
  completed_at timestamp with time zone,
  attempts integer not null,
  failure_code varchar(64),
  removed_rows bigint not null,
  graph_duration_ms bigint not null,
  verification varchar(100),
  backup_status varchar(32) not null,
  backup_expires_at timestamp with time zone,
  lock_version bigint not null default 0,
  check (state in ('DELETION_PENDING', 'DELETED'))
);
create index idx_case_deletion_pending on case_deletion_jobs(state, requested_at);
create index idx_case_expiration on analysis_cases(lifecycle, access_expires_at);
update source_documents set lifecycle='DELETION_PENDING' where lifecycle='DELETION_IN_PROGRESS';
create table case_deletion_attempts (
  id varchar(36) primary key,
  job_id varchar(36) not null references case_deletion_jobs(id),
  started_at timestamp with time zone not null,
  finished_at timestamp with time zone,
  outcome varchar(32) not null,
  verified_participants integer not null,
  failure_code varchar(64)
);
create index idx_deletion_attempt_job on case_deletion_attempts(job_id, started_at);

-- Existing documents also bound the complete case's retention; never extend an old deadline.
update analysis_cases set access_expires_at = (
  select min(d.retention_deadline) from source_documents d where d.case_id = analysis_cases.id
) where exists (
  select 1 from source_documents d where d.case_id = analysis_cases.id
    and d.retention_deadline < analysis_cases.access_expires_at
);
