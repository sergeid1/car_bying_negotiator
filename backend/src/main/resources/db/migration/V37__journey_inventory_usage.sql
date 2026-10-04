-- V36 belongs to Reference Vehicles. Inventory usage depends only on V34 journeys.
alter table provider_usage alter column case_id drop not null;
alter table provider_usage add column journey_id varchar(36) references buying_journeys(id);
alter table provider_usage add column account_id varchar(36) references buyer_accounts(id);
alter table provider_usage add column query_json text;
alter table provider_usage add column result_count integer;
alter table provider_usage add column retrieved_at timestamp with time zone;
alter table provider_usage add column latency_millis bigint;
alter table provider_usage add column inventory_availability varchar(24);
alter table provider_usage add constraint provider_usage_scope check (
  (case_id is not null and journey_id is null and account_id is null) or
  (case_id is null and journey_id is not null and account_id is not null and operation = 'INVENTORY_SEARCH'
    and query_json is not null and result_count >= 0 and retrieved_at is not null)
);
create index provider_usage_journey on provider_usage(journey_id);
create index provider_usage_account on provider_usage(account_id);
