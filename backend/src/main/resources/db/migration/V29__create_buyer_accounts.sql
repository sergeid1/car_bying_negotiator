-- Contact details live only in contact_ciphertext (AES-GCM). email_lookup is a keyed HMAC so
-- sign-in can find an account without storing the address in clear text.
create table buyer_accounts (
  id varchar(36) primary key,
  email_lookup varchar(64) not null unique,
  contact_ciphertext varchar(4096) not null,
  lifecycle varchar(24) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  verified_at timestamp with time zone,
  check (lifecycle in ('ACTIVE', 'DELETION_PENDING'))
);
create table buyer_account_sign_in_links (
  token_hash varchar(64) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  request_key_hash varchar(64) not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  consumed_at timestamp with time zone,
  unique(account_id, request_key_hash)
);
create table buyer_account_sessions (
  token_hash varchar(64) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  revoked_at timestamp with time zone
);
create index buyer_account_sessions_account on buyer_account_sessions(account_id);
-- A Buying Case belongs to at most one account. Case deletion removes the link first.
create table buyer_account_cases (
  case_id varchar(36) primary key references analysis_cases(id),
  account_id varchar(36) not null references buyer_accounts(id),
  attached_at timestamp with time zone not null
);
create index buyer_account_cases_account on buyer_account_cases(account_id);
-- Deletion receipts outlive the account and hold no contact details or credentials.
create table buyer_account_deletion_jobs (
  id varchar(36) primary key,
  account_id varchar(36) not null unique,
  receipt_hash varchar(64) not null unique,
  state varchar(24) not null,
  requested_at timestamp with time zone not null,
  attempts integer not null,
  last_attempt_at timestamp with time zone,
  account_removed_at timestamp with time zone,
  completed_at timestamp with time zone,
  failure_code varchar(64),
  verification varchar(64),
  backup_status varchar(24) not null,
  backup_expires_at timestamp with time zone,
  lock_version bigint not null,
  check (state in ('DELETION_PENDING', 'DELETED'))
);
create table buyer_account_deletion_cases (
  job_id varchar(36) not null references buyer_account_deletion_jobs(id),
  case_id varchar(36) not null,
  primary key (job_id, case_id)
);
