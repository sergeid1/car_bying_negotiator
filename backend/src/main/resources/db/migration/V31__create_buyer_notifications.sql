create table buyer_notification_preferences (
  account_id varchar(36) not null references buyer_accounts(id),
  notification_type varchar(32) not null,
  enabled boolean not null,
  primary key (account_id, notification_type),
  check (notification_type in ('DEALER_REPLY', 'DRAFT_NEEDS_APPROVAL', 'MANDATE_EXPIRING', 'APPOINTMENT'))
);
-- No addresses, message bodies, links or raw producer keys are retained.
create table buyer_notification_deliveries (
  id varchar(36) primary key,
  account_id varchar(36) not null references buyer_accounts(id),
  notification_type varchar(32) not null,
  dedupe_hash varchar(64) not null,
  state varchar(24) not null,
  created_at timestamp with time zone not null,
  completed_at timestamp with time zone,
  unique (account_id, notification_type, dedupe_hash),
  check (notification_type in ('DEALER_REPLY', 'DRAFT_NEEDS_APPROVAL', 'MANDATE_EXPIRING', 'APPOINTMENT', 'ACCOUNT_SECURITY')),
  check (state in ('DELIVERY_UNKNOWN', 'SENT', 'FAILED', 'OPTED_OUT', 'RATE_LIMITED'))
);
create index buyer_notification_rate on buyer_notification_deliveries(account_id, created_at);
