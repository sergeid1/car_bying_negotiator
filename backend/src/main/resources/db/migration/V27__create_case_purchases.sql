create table case_purchases (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  request_key varchar(200) not null,
  offer_id varchar(200) not null,
  amount_minor bigint not null check (amount_minor >= 0),
  currency varchar(3) not null,
  status varchar(16) not null check (status in ('PAID', 'PENDING')),
  checkout varchar(100) not null,
  checkout_url varchar(2000),
  created_at timestamp with time zone not null,
  refunded_at timestamp with time zone,
  refunded_by varchar(200),
  unique (case_id, request_key),
  unique (case_id)
);
create table case_purchase_requests (
  case_id varchar(36) not null references analysis_cases(id),
  request_key varchar(200) not null,
  purchase_id varchar(36) not null references case_purchases(id),
  offer_id varchar(200) not null,
  primary key (case_id, request_key)
);
create table purchase_totals (
  id integer primary key check (id = 1),
  removed_paid_count bigint not null default 0,
  removed_refund_count bigint not null default 0,
  removed_case_count bigint not null default 0
);
insert into purchase_totals (id) values (1);
alter table acquisition_channel_totals add column removed_paid_count bigint not null default 0;
