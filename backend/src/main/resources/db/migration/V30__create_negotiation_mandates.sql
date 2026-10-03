-- Buyer-approved Negotiation Mandates (AGT-01, AGT-02, AGT-06). Each row is one immutable approved
-- version; changing any term means a new approval and a new version. Revocation is a separate row so
-- the approved version itself never changes. Expiry is the approved expires_at, compared at read time.
create table negotiation_mandates (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  plan_snapshot_id varchar(36) not null,
  plan_version integer not null,
  currency varchar(3) not null,
  target_minor bigint not null,
  ceiling_minor bigint not null,
  plan_target_minor bigint not null,
  plan_ceiling_minor bigint not null,
  disclosures varchar(200) not null,
  autonomy_mode varchar(16) not null,
  expires_at timestamp with time zone not null,
  approved_by varchar(32) not null,
  approved_at timestamp with time zone not null,
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  unique(case_id, version),
  unique(case_id, idempotency_key),
  foreign key (case_id, plan_snapshot_id) references price_plan_snapshots(case_id, id),
  constraint ck_mandate_version_positive check (version > 0),
  constraint ck_mandate_amounts check (target_minor > 0 and target_minor <= ceiling_minor
    and target_minor <= plan_target_minor and ceiling_minor <= plan_ceiling_minor),
  constraint ck_mandate_autonomy check (autonomy_mode in ('SUPERVISED', 'DELEGATED')),
  constraint ck_mandate_expiry check (expires_at > approved_at)
);

create index idx_negotiation_mandates_expiry on negotiation_mandates(expires_at);

create table negotiation_mandate_revocations (
  mandate_id varchar(36) primary key references negotiation_mandates(id),
  case_id varchar(36) not null references analysis_cases(id),
  idempotency_key varchar(200) not null,
  revoked_by varchar(32) not null,
  revoked_at timestamp with time zone not null
);
