-- One insurance application per Buying Journey, or per Buying Case without a journey (INS-01).
-- The application holds rating facts only: no name, date of birth, licence number, street address or
-- Social Security number. Identity details go in the licensed partner's own form (spec section 13).
create table insurance_applications (
  id varchar(36) primary key,
  journey_id varchar(36) unique references buying_journeys(id),
  account_id varchar(36) references buyer_accounts(id),
  case_id varchar(36) unique references analysis_cases(id),
  -- Drivers, garaging ZIP and state, vehicle, usage, mileage, chosen coverage and the optional current policy.
  application_json text not null,
  schema_version varchar(40) not null,
  revision integer not null,
  changed_at timestamp with time zone not null,
  created_at timestamp with time zone not null,
  constraint insurance_application_holder check ((journey_id is null) <> (case_id is null)),
  constraint insurance_application_account check ((journey_id is null) = (account_id is null)),
  constraint insurance_application_revision check (revision >= 1)
);
create index insurance_applications_account on insurance_applications(account_id);

-- Each time the Buyer agreed to the partner's consent and asked for quotes (INS-02). The consent version and
-- time are the record of what the Buyer agreed to; the partner's identity is code-owned metadata.
create table insurance_quote_requests (
  id varchar(36) primary key,
  application_id varchar(36) not null references insurance_applications(id),
  account_id varchar(36) references buyer_accounts(id),
  idempotency_key_hash varchar(128) not null,
  application_revision integer not null,
  consent_text_version varchar(100) not null,
  consent_agreed_at timestamp with time zone not null,
  provider varchar(64) not null,
  provider_version varchar(100) not null,
  licence_metadata varchar(100) not null,
  synthetic boolean not null,
  availability varchar(20) not null,
  reason varchar(30),
  retrieved_at timestamp with time zone not null,
  created_at timestamp with time zone not null,
  constraint insurance_quote_request_key unique (application_id, idempotency_key_hash),
  constraint insurance_quote_request_availability check (availability in ('AVAILABLE', 'UNAVAILABLE', 'TIMED_OUT', 'RATE_LIMITED')),
  constraint insurance_quote_request_reason check ((availability = 'AVAILABLE') = (reason is null))
);
create index insurance_quote_requests_application on insurance_quote_requests(application_id);
create index insurance_quote_requests_account on insurance_quote_requests(account_id);

-- What each insurer returned through the partner, and quotes the Buyer entered from insurers that do not
-- participate (PAY-02). No compensation, placement or participation field exists here (PAY-01).
create table insurance_quotes (
  id varchar(36) primary key,
  application_id varchar(36) not null references insurance_applications(id),
  request_id varchar(36) references insurance_quote_requests(id),
  account_id varchar(36) references buyer_accounts(id),
  source varchar(16) not null,
  idempotency_key_hash varchar(128),
  insurer varchar(100) not null,
  outcome varchar(30) not null,
  premium_minor bigint,
  term varchar(16),
  coverage_json text,
  quoted_at timestamp with time zone,
  expires_at timestamp with time zone,
  continue_url varchar(2000),
  created_at timestamp with time zone not null,
  constraint insurance_quote_source check (
    (source = 'PARTNER' and request_id is not null and idempotency_key_hash is null)
    or (source = 'BUYER_ENTERED' and request_id is null and idempotency_key_hash is not null and outcome = 'OFFERED'
      and continue_url is null)),
  constraint insurance_quote_outcome check (outcome in ('OFFERED', 'DECLINED', 'NEEDS_MORE_INFORMATION', 'UNAVAILABLE', 'TIMED_OUT')),
  -- Only an offer carries a premium, and an offer always carries one.
  constraint insurance_quote_offer check (
    (outcome = 'OFFERED' and premium_minor > 0 and term in ('SIX_MONTHS', 'TWELVE_MONTHS') and coverage_json is not null
      and quoted_at is not null)
    or (outcome <> 'OFFERED' and premium_minor is null and term is null and coverage_json is null and quoted_at is null
      and expires_at is null and continue_url is null)),
  constraint insurance_quote_buyer_key unique (application_id, idempotency_key_hash)
);
create index insurance_quotes_application on insurance_quotes(application_id);
create index insurance_quotes_request on insurance_quotes(request_id);
create index insurance_quotes_account on insurance_quotes(account_id);
