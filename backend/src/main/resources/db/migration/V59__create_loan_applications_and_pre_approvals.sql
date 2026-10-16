-- One loan application per Buying Journey, or per Buying Case without a journey (spec 5.10, LOAN-01 to LOAN-03).
-- The application holds the amount, term, vehicle, income and housing only: no name, address, date of birth or
-- Social Security number. Identity details a soft inquiry needs go in the licensed partner's own form (section 13).
-- chosen_offer_id is the pre-approval or Buyer-entered offer the Buyer chose as their outside financing (LOAN-03).
create table loan_applications (
  id varchar(36) primary key,
  journey_id varchar(36) unique references buying_journeys(id),
  account_id varchar(36) references buyer_accounts(id),
  case_id varchar(36) unique references analysis_cases(id),
  -- Amount, term, vehicle, yearly income, housing and monthly housing cost.
  application_json text not null,
  schema_version varchar(40) not null,
  revision integer not null,
  chosen_offer_id varchar(36),
  chosen_at timestamp with time zone,
  changed_at timestamp with time zone not null,
  created_at timestamp with time zone not null,
  constraint loan_application_holder check ((journey_id is null) <> (case_id is null)),
  constraint loan_application_account check ((journey_id is null) = (account_id is null)),
  constraint loan_application_revision check (revision >= 1),
  constraint loan_application_choice check ((chosen_offer_id is null) = (chosen_at is null))
);
create index loan_applications_account on loan_applications(account_id);

-- Each time the Buyer gave the written credit authorization and asked for pre-approvals (LOAN-01). The
-- authorization version and time are the record of what the Buyer authorized; the partner's identity is
-- code-owned metadata.
create table loan_pre_approval_requests (
  id varchar(36) primary key,
  application_id varchar(36) not null references loan_applications(id),
  account_id varchar(36) references buyer_accounts(id),
  idempotency_key_hash varchar(128) not null,
  application_revision integer not null,
  authorization_text_version varchar(100) not null,
  authorized_at timestamp with time zone not null,
  provider varchar(64) not null,
  provider_version varchar(100) not null,
  licence_metadata varchar(100) not null,
  synthetic boolean not null,
  availability varchar(20) not null,
  reason varchar(30),
  retrieved_at timestamp with time zone not null,
  created_at timestamp with time zone not null,
  constraint loan_pre_approval_request_key unique (application_id, idempotency_key_hash),
  constraint loan_pre_approval_request_availability check (availability in ('AVAILABLE', 'UNAVAILABLE', 'TIMED_OUT', 'RATE_LIMITED')),
  constraint loan_pre_approval_request_reason check ((availability = 'AVAILABLE') = (reason is null))
);
create index loan_pre_approval_requests_application on loan_pre_approval_requests(application_id);
create index loan_pre_approval_requests_account on loan_pre_approval_requests(account_id);

-- What each lender returned through the partner, and offers the Buyer entered from lenders that do not
-- participate, such as a credit union (PAY-02). Only soft-inquiry pre-approvals are kept (LOAN-01). No
-- compensation, placement or participation field exists here (PAY-01).
create table loan_offers (
  id varchar(36) primary key,
  application_id varchar(36) not null references loan_applications(id),
  request_id varchar(36) references loan_pre_approval_requests(id),
  account_id varchar(36) references buyer_accounts(id),
  source varchar(16) not null,
  idempotency_key_hash varchar(128),
  lender varchar(100) not null,
  outcome varchar(30) not null,
  amount_minor bigint,
  apr_percent numeric(11, 8),
  term_months integer,
  upfront_fees_minor bigint,
  lender_payment_minor bigint,
  maximum_vehicle_age_years integer,
  maximum_mileage bigint,
  minimum_down_payment_minor bigint,
  inquiry_type varchar(10),
  offered_at timestamp with time zone,
  expires_at timestamp with time zone,
  continue_url varchar(2000),
  created_at timestamp with time zone not null,
  constraint loan_offer_source check (
    (source = 'PARTNER' and request_id is not null and idempotency_key_hash is null)
    or (source = 'BUYER_ENTERED' and request_id is null and idempotency_key_hash is not null and outcome = 'OFFERED'
      and continue_url is null and inquiry_type is null)),
  constraint loan_offer_outcome check (outcome in ('OFFERED', 'DECLINED', 'NEEDS_MORE_INFORMATION', 'UNAVAILABLE', 'TIMED_OUT')),
  -- Only an offer carries terms, and an offer always carries amount, APR, term and fees.
  constraint loan_offer_terms check (
    (outcome = 'OFFERED' and amount_minor > 0 and apr_percent >= 0 and apr_percent <= 100 and term_months between 1 and 1200
      and upfront_fees_minor >= 0 and offered_at is not null)
    or (outcome <> 'OFFERED' and amount_minor is null and apr_percent is null and term_months is null
      and upfront_fees_minor is null and lender_payment_minor is null and maximum_vehicle_age_years is null
      and maximum_mileage is null and minimum_down_payment_minor is null and inquiry_type is null and offered_at is null
      and expires_at is null and continue_url is null)),
  constraint loan_offer_soft check (source <> 'PARTNER' or outcome <> 'OFFERED' or inquiry_type = 'SOFT'),
  constraint loan_offer_buyer_key unique (application_id, idempotency_key_hash)
);
create index loan_offers_application on loan_offers(application_id);
create index loan_offers_request on loan_offers(request_id);
create index loan_offers_account on loan_offers(account_id);
