-- Spec 2.1 (ticket 83): a Buyer connects one Gmail or Outlook account with a send-only permission, and the
-- platform sends approved dealer messages through it (send path CONNECTED_MAILBOX). Tokens and the sending
-- address are AES-GCM encrypted with the dedicated mailbox key; nothing here holds a message body.

-- One connection per account. Disconnecting, account deletion and a provider revocation delete the row.
create table mailbox_connections (
  id varchar(36) primary key,
  account_id varchar(36) not null unique references buyer_accounts(id),
  provider varchar(16) not null,
  address_ciphertext varchar(1024) not null,
  granted_scope varchar(1024) not null,
  refresh_token_ciphertext varchar(8192) not null,
  access_token_ciphertext varchar(8192),
  access_expires_at timestamp with time zone,
  connected_at timestamp with time zone not null,
  constraint mailbox_connection_providers check (provider in ('GOOGLE', 'MICROSOFT'))
);

-- An OAuth authorization in progress: the state value (stored as a keyed hash) and the PKCE verifier.
create table mailbox_authorizations (
  state_hash varchar(64) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  provider varchar(16) not null,
  verifier_ciphertext varchar(1024) not null,
  created_at timestamp with time zone not null,
  expires_at timestamp with time zone not null,
  constraint mailbox_authorization_providers check (provider in ('GOOGLE', 'MICROSOFT'))
);
create index mailbox_authorizations_account on mailbox_authorizations(account_id);

-- Every Connected-mailbox send attempt, for metering and caps (AGT-11). No address or content.
create table mailbox_sends (
  id varchar(36) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  case_id varchar(36) not null references analysis_cases(id),
  message_id varchar(36) not null,
  dealer_address_hash varchar(128) not null,
  provider varchar(16) not null,
  outcome varchar(24) not null,
  started_at timestamp with time zone not null,
  completed_at timestamp with time zone,
  constraint mailbox_send_outcomes check (outcome in (
    'SENDING', 'SENT', 'DELIVERY_UNKNOWN', 'RATE_LIMITED', 'REVOKED', 'REJECTED', 'UNAVAILABLE'))
);
create index mailbox_sends_account on mailbox_sends(account_id, started_at);
create index mailbox_sends_case on mailbox_sends(case_id, started_at);
create index mailbox_sends_message on mailbox_sends(message_id);
