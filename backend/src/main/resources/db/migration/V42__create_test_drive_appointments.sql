create table test_drive_appointments (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  agreed_price_id varchar(36) not null references agreed_prices(id),
  proposal_id varchar(36) not null,
  slot_option integer not null,
  starts_at timestamp with time zone not null,
  ends_at timestamp with time zone not null,
  time_zone varchar(64) not null,
  confirmation_message_id varchar(36) not null references dealer_relay_messages(id),
  replaces_appointment_id varchar(36),
  state varchar(24) not null check (state in ('PENDING_BOOKING', 'WITHDRAWN', 'SCHEDULED', 'RESCHEDULING', 'RESCHEDULED', 'CANCELLED')),
  created_at timestamp with time zone not null,
  scheduled_at timestamp with time zone,
  ended_at timestamp with time zone
);
create index idx_test_drive_appointments_case on test_drive_appointments(case_id, created_at);
create table test_drive_messages (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  agreed_price_id varchar(36) not null references agreed_prices(id),
  quote_request_id varchar(36) not null references quote_requests(id),
  appointment_id varchar(36) references test_drive_appointments(id),
  kind varchar(24) not null check (kind in ('PROPOSAL', 'RESCHEDULE', 'BOOKING', 'CANCELLATION')),
  time_zone varchar(64),
  slots_json text not null,
  message_text text not null,
  draft_id varchar(36) references outbound_drafts(id),
  state varchar(24) not null,
  message_id varchar(36) references dealer_relay_messages(id),
  idempotency_key varchar(200) not null,
  request_fingerprint varchar(64) not null,
  created_at timestamp with time zone not null,
  sent_at timestamp with time zone,
  unique(case_id, idempotency_key)
);
create index idx_test_drive_messages_case on test_drive_messages(case_id, created_at);
create table test_drive_contact_releases (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  appointment_id varchar(36) not null unique references test_drive_appointments(id),
  account_id varchar(36) not null,
  dealer_id varchar(36) not null references dealers(id),
  released_fields varchar(80) not null,
  state varchar(24) not null check (state in ('PREPARED', 'RELEASED', 'WITHHELD')),
  decided_at timestamp with time zone not null,
  released_at timestamp with time zone
);
