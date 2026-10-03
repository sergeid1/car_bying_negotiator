create table case_support_time (
  case_id varchar(36) not null references analysis_cases(id),
  entry_id varchar(36) not null,
  minutes integer not null check (minutes > 0 and minutes <= 1440),
  primary key (case_id, entry_id)
);

-- Anonymous distribution, with no identifiers or precise event timestamps. Keeping
-- the distribution (rather than just its sum) permits an exact median after deletion.
create table contribution_totals (
  cohort_day date not null,
  qualified integer not null,
  verified integer not null,
  completed integer not null,
  paid integer not null,
  refunded integer not null,
  development integer not null,
  revenue_minor bigint not null,
  refund_minor bigint not null,
  currency varchar(3) not null,
  known_cost_usd numeric(24,8) not null,
  unknown_calls bigint not null,
  support_minutes bigint not null,
  case_count bigint not null check (case_count > 0),
  unique (cohort_day, qualified, verified, completed, paid, refunded, development,
    revenue_minor, refund_minor, currency, known_cost_usd, unknown_calls, support_minutes)
);
create index contribution_totals_day on contribution_totals(cohort_day);

create view case_contribution_facts as
-- Extract UTC fields explicitly: casting a zoned timestamp directly to date can
-- convert through the connection timezone (notably in the H2 acceptance database).
select c.id as case_id, cast(concat(
  extract(year from c.created_at at time zone 'UTC'), '-',
  extract(month from c.created_at at time zone 'UTC'), '-',
  extract(day from c.created_at at time zone 'UTC')) as date) as cohort_day,
  case when exists (select 1 from experiment_assignments a where a.case_id = c.id and a.qualified = true) then 1 else 0 end as qualified,
  case when exists (select 1 from dealer_offers o where o.case_id = c.id) then 1 else 0 end as verified,
  case when exists (select 1 from analysis_reports r where r.case_id = c.id) then 1 else 0 end as completed,
  case when p.status = 'PAID' and p.checkout <> 'DEVELOPMENT_NO_CHARGE' then 1 else 0 end as paid,
  case when p.status = 'PAID' and p.checkout <> 'DEVELOPMENT_NO_CHARGE' and p.refunded_at is not null then 1 else 0 end as refunded,
  case when p.status = 'PAID' and p.checkout = 'DEVELOPMENT_NO_CHARGE' then 1 else 0 end as development,
  case when p.status = 'PAID' and p.checkout <> 'DEVELOPMENT_NO_CHARGE' then p.amount_minor else 0 end as revenue_minor,
  case when p.status = 'PAID' and p.checkout <> 'DEVELOPMENT_NO_CHARGE' and p.refunded_at is not null then p.amount_minor else 0 end as refund_minor,
  coalesce(p.currency, 'USD') as currency,
  coalesce((select sum(u.cost_usd) from provider_usage u where u.case_id = c.id), 0) as known_cost_usd,
  (select count(*) from provider_usage u where u.case_id = c.id and u.cost_usd is null) as unknown_calls,
  coalesce((select sum(s.minutes) from case_support_time s where s.case_id = c.id), 0) as support_minutes
from analysis_cases c left join case_purchases p on p.case_id = c.id;
