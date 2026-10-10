-- Trade value estimates the Buyer looked up themselves (ticket 106), such as a Carfax value. An estimate is
-- never an offer: it has no expiry or evidence and is never compared as something the Buyer can take to a dealer.
create table trade_estimates (
  id varchar(36) primary key,
  trade_id varchar(36) not null references trade_vehicles(id),
  account_id varchar(36) references buyer_accounts(id),
  idempotency_key_hash varchar(128) not null,
  source varchar(120) not null,
  low_minor bigint not null,
  -- Null for a single value; otherwise the top of the low-high range.
  high_minor bigint,
  checked_on date not null,
  created_at timestamp with time zone not null,
  constraint trade_estimate_amount check (low_minor > 0),
  constraint trade_estimate_range check (high_minor is null or high_minor >= low_minor),
  constraint trade_estimate_request unique (trade_id, idempotency_key_hash)
);
create index trade_estimates_trade on trade_estimates(trade_id);
create index trade_estimates_account on trade_estimates(account_id);
