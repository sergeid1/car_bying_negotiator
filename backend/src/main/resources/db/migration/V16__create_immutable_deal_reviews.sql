alter table dealer_offers add constraint uq_dealer_offer_case unique(case_id, id);
alter table price_plan_snapshots add constraint uq_price_plan_case unique(case_id, id);
alter table dealer_offers add column previous_offer_id varchar(36);
alter table dealer_offers add constraint fk_previous_offer_case
  foreign key(case_id, previous_offer_id) references dealer_offers(case_id, id);
update dealer_offers set previous_offer_id = (
  select previous.id from dealer_offers previous
  where previous.case_id = dealer_offers.case_id and previous.offer_version = dealer_offers.offer_version - 1
) where offer_version > 1;

create table deal_reviews (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  version integer not null,
  baseline_id varchar(36),
  previous_offer_id varchar(36) not null,
  current_offer_id varchar(36) not null,
  tolerance_policy varchar(300) not null,
  payload_json text not null,
  created_at timestamp with time zone not null,
  unique(case_id, version),
  foreign key(case_id, baseline_id) references price_plan_snapshots(case_id, id),
  foreign key(case_id, previous_offer_id) references dealer_offers(case_id, id),
  foreign key(case_id, current_offer_id) references dealer_offers(case_id, id)
);
