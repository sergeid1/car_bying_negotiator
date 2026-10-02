-- First-party acquisition channels (ticket 42). A channel is operator configuration, not Buyer data.
-- Dealer-paid lead and dealer outreach kinds are excluded by specification section 2.2, so the
-- check constraint admits only first-party content and disclosed referral partnerships.
create table acquisition_channels (
  code varchar(32) primary key,
  kind varchar(32) not null check (kind in ('EXPLAINER_CONTENT', 'REFERRAL_PARTNERSHIP')),
  referral_disclosure varchar(600),
  active boolean not null,
  updated_by varchar(64) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  check ((kind = 'REFERRAL_PARTNERSHIP' and referral_disclosure is not null)
      or (kind <> 'REFERRAL_PARTNERSHIP' and referral_disclosure is null))
);

-- The only acquisition datum kept with a case. It is removed with the case row.
alter table analysis_cases add column acquisition_channel varchar(32) references acquisition_channels(code);

-- Operator-entered spend for a stated, inclusive period, in USD minor units.
create table acquisition_channel_spend (
  id varchar(36) primary key,
  channel_code varchar(32) not null references acquisition_channels(code),
  period_start date not null,
  period_end date not null,
  amount_minor bigint not null check (amount_minor >= 0),
  currency varchar(3) not null check (currency = 'USD'),
  recorded_by varchar(64) not null,
  created_at timestamp with time zone not null,
  check (period_end >= period_start)
);
create index acquisition_channel_spend_channel on acquisition_channel_spend(channel_code);

-- Irreversible anonymous per-channel counters added when a case is deleted, like pilot_cohort_totals.
-- No Buyer, case, Dealer, variant, price, document or time reference is kept. The '' row is no channel.
create table acquisition_channel_totals (
  channel_key varchar(32) primary key,
  removed_case_starts bigint not null default 0,
  removed_qualified_count bigint not null default 0,
  removed_qualified_uploads bigint not null default 0
);
insert into acquisition_channel_totals(channel_key) values('');
