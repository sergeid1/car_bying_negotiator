create table case_recovery_contacts (
  case_id varchar(36) primary key references analysis_cases(id),
  email varchar(254) not null,
  expires_at timestamp with time zone not null
);
create table case_delivery_requests (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  request_key varchar(200) not null,
  email varchar(254) not null,
  expires_at timestamp with time zone not null,
  unique(case_id, request_key)
);
create table case_recovery_tokens (
  token_hash varchar(64) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  delivery_id varchar(36) not null unique references case_delivery_requests(id),
  expires_at timestamp with time zone not null,
  consumed_at timestamp with time zone
);
create table case_export_jobs (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  request_key varchar(200) not null,
  format varchar(16) not null,
  report_version integer not null,
  content_base64 text not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  unique(case_id, request_key),
  check (format in ('ZIP', 'HTML', 'PDF'))
);
