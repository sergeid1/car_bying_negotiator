create table price_plan_snapshots (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  kind varchar(16) not null,
  version integer not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, kind, version),
  unique(case_id, kind, idempotency_key)
);
