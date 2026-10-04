-- Dealer email relay (AGT-03, AGT-04, AGT-11). One unguessable relay identity per Buying Case; the
-- Buyer's own address never appears here. A dealer opt-out is permanent for the identity.
create table dealer_relay_identities (
  id varchar(36) primary key,
  case_id varchar(36) not null unique references analysis_cases(id),
  address varchar(254) not null unique,
  opt_out_token_hash varchar(64) not null unique,
  created_at timestamp with time zone not null,
  dealer_opted_out_at timestamp with time zone,
  opt_out_channel varchar(16),
  check (opt_out_channel is null or opt_out_channel in ('LINK', 'REPLY')),
  check ((dealer_opted_out_at is null) = (opt_out_channel is null))
);

-- Subject and body are AES-GCM ciphertext, erased at the case's document retention deadline.
-- Inbound content is always UNTRUSTED dealer content (DOC-08).
create table dealer_relay_messages (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  identity_id varchar(36) not null references dealer_relay_identities(id),
  direction varchar(8) not null,
  trust varchar(16) not null,
  dealer_address varchar(254) not null,
  dealer_address_hash varchar(64) not null,
  state varchar(24) not null,
  subject_ciphertext text,
  body_ciphertext text,
  size_bytes integer not null,
  provider_message_id varchar(200),
  created_at timestamp with time zone not null,
  completed_at timestamp with time zone,
  body_erased_at timestamp with time zone,
  check (direction in ('OUTBOUND', 'INBOUND')),
  check (trust in ('PLATFORM', 'UNTRUSTED')),
  check (direction = 'OUTBOUND' or trust = 'UNTRUSTED'),
  check (state in ('DELIVERY_UNKNOWN', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED', 'RECEIVED', 'REJECTED_MALWARE'))
);
create index idx_dealer_relay_messages_case on dealer_relay_messages(case_id, created_at);
create unique index idx_dealer_relay_messages_provider on dealer_relay_messages(direction, provider_message_id);

create table dealer_relay_attachments (
  id varchar(36) primary key,
  message_id varchar(36) not null references dealer_relay_messages(id),
  case_id varchar(36) not null references analysis_cases(id),
  ordinal integer not null,
  document_id varchar(36),
  outcome varchar(40) not null,
  created_at timestamp with time zone not null,
  unique (message_id, ordinal)
);

-- Meter of every send attempt, including refusals. No content, only the outcome.
create table dealer_relay_send_attempts (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  dealer_address_hash varchar(64) not null,
  message_id varchar(36),
  outcome varchar(24) not null,
  created_at timestamp with time zone not null,
  check (outcome in ('ACCEPTED', 'FAILED', 'CASE_CAP', 'DEALER_CAP', 'OPTED_OUT', 'BUYER_DATA', 'UNAVAILABLE'))
);
create index idx_dealer_relay_attempts_case on dealer_relay_send_attempts(case_id, created_at);
create index idx_dealer_relay_attempts_dealer on dealer_relay_send_attempts(dealer_address_hash, created_at);
