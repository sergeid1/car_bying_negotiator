-- Insurance quotes and loan pre-approvals can be requested from a journey without a case (spec 5.10, INS-01).
alter table provider_usage drop constraint provider_usage_scope;
alter table provider_usage add constraint provider_usage_scope check (
  (case_id is not null and journey_id is null and account_id is null) or
  (case_id is null and journey_id is not null and account_id is not null and
    ((operation = 'INVENTORY_SEARCH' and query_json is not null and result_count >= 0 and retrieved_at is not null)
      or operation in ('MARKET_RETRIEVAL', 'INSURANCE_QUOTE', 'PRE_APPROVAL')))
);
