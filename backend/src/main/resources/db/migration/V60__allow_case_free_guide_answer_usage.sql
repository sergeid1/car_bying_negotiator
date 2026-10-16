alter table provider_usage drop constraint provider_usage_scope;
alter table provider_usage add constraint provider_usage_scope check (
  (case_id is not null and journey_id is null and account_id is null and operation <> 'GUIDE_ANSWER') or
  (case_id is null and journey_id is not null and account_id is not null and
    ((operation = 'INVENTORY_SEARCH' and query_json is not null and result_count >= 0 and retrieved_at is not null)
      or operation in ('MARKET_RETRIEVAL', 'INSURANCE_QUOTE', 'PRE_APPROVAL'))) or
  (case_id is null and journey_id is null and operation = 'GUIDE_ANSWER' and query_json is null)
);
create index provider_usage_guide_created_idx on provider_usage(operation, created_at);
