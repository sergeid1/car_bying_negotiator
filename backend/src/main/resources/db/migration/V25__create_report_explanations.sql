-- One checked explanation per finalized report version, produced once and served unchanged after.
-- The path, fallback reason and model provenance are columns so the producer is recorded without
-- parsing the payload; payload_json holds the full checked explanation as served.
create table report_explanations (
  case_id varchar(36) not null,
  report_version integer not null,
  policy_version varchar(40) not null,
  path varchar(16) not null,
  fallback_reason varchar(60),
  model_provider varchar(20),
  model_id varchar(100),
  prompt_version varchar(100),
  schema_version varchar(100),
  threshold_set_version varchar(100),
  evaluation_set_version varchar(100),
  payload_json text not null,
  created_at timestamp with time zone not null,
  primary key (case_id, report_version),
  foreign key (case_id, report_version) references analysis_reports(case_id, report_version),
  constraint report_explanations_path check (
    path = 'TEMPLATE' and fallback_reason is not null and model_provider is null and model_id is null
      and prompt_version is null and schema_version is null
      and threshold_set_version is null and evaluation_set_version is null
    or path = 'MODEL' and fallback_reason is null and model_provider is not null and model_id is not null
      and prompt_version is not null and schema_version is not null
      and threshold_set_version is not null and evaluation_set_version is not null)
);
