-- Trade-in evidence goes through ordinary document intake but is never read as dealer paperwork.
alter table source_documents add column purpose varchar(32) not null default 'DEALER_PAPERWORK';
alter table source_documents add constraint source_documents_purpose
  check (purpose in ('DEALER_PAPERWORK', 'TRADE_OFFER_EVIDENCE'));

-- One Trade Vehicle per Buying Journey, or per Buying Case without a journey (TRADE-01).
create table trade_vehicles (
  id varchar(36) primary key,
  journey_id varchar(36) unique references buying_journeys(id),
  account_id varchar(36) references buyer_accounts(id),
  case_id varchar(36) unique references analysis_cases(id),
  vin varchar(17) not null,
  decode_outcome varchar(20) not null,
  decoder_source varchar(200) not null,
  decoder_version varchar(100) not null,
  decoded_at timestamp with time zone not null,
  model_year varchar(10),
  make varchar(100),
  model varchar(100),
  trim_name varchar(200),
  mileage integer not null,
  condition varchar(16) not null,
  money_owed varchar(16),
  lender varchar(120),
  payoff_minor bigint,
  payoff_good_through date,
  revision integer not null,
  changed_at timestamp with time zone not null,
  created_at timestamp with time zone not null,
  constraint trade_vehicle_holder check ((journey_id is null) <> (case_id is null)),
  constraint trade_vehicle_account check ((journey_id is null) = (account_id is null)),
  constraint trade_vehicle_mileage check (mileage >= 0 and mileage <= 999999),
  constraint trade_vehicle_condition check (condition in ('EXCELLENT', 'GOOD', 'FAIR', 'POOR')),
  constraint trade_vehicle_owed check (money_owed is null or money_owed in ('YES', 'NO', 'NOT_SURE')),
  -- An unknown payoff is null, never zero; an amount always carries its good-through date.
  constraint trade_vehicle_payoff_pair check ((payoff_minor is null) = (payoff_good_through is null)),
  constraint trade_vehicle_payoff_positive check (payoff_minor is null or payoff_minor > 0),
  constraint trade_vehicle_payoff_only_when_owed check (
    (money_owed = 'YES' and lender is not null)
    or (money_owed is distinct from 'YES' and lender is null and payoff_minor is null)),
  constraint trade_vehicle_revision check (revision >= 1)
);
create index trade_vehicles_account on trade_vehicles(account_id);

-- Written trade offers the Buyer obtained themselves (TRADE-02); the evidence lives in the case it was uploaded to.
create table trade_offers (
  id varchar(36) primary key,
  trade_id varchar(36) not null references trade_vehicles(id),
  account_id varchar(36) references buyer_accounts(id),
  evidence_case_id varchar(36) not null references analysis_cases(id),
  evidence_document_id varchar(36) not null unique references source_documents(id),
  idempotency_key_hash varchar(128) not null,
  amount_minor bigint not null,
  source varchar(120) not null,
  offer_date date not null,
  expires_on date not null,
  created_at timestamp with time zone not null,
  constraint trade_offer_amount check (amount_minor > 0),
  constraint trade_offer_dates check (expires_on >= offer_date),
  constraint trade_offer_request unique (trade_id, idempotency_key_hash)
);
create index trade_offers_trade on trade_offers(trade_id);
create index trade_offers_account on trade_offers(account_id);
create index trade_offers_case on trade_offers(evidence_case_id);

-- A case started at the Desk Check for another car joins its journey's trade.
create table trade_case_journeys (
  case_id varchar(36) primary key references analysis_cases(id),
  journey_id varchar(36) not null references buying_journeys(id),
  account_id varchar(36) not null references buyer_accounts(id),
  created_at timestamp with time zone not null
);
create index trade_case_journeys_journey on trade_case_journeys(journey_id);
create index trade_case_journeys_account on trade_case_journeys(account_id);
