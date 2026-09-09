create table negotiation_versions (
  case_id varchar(36) not null,
  report_version integer not null,
  payload_json text not null,
  primary key (case_id, report_version),
  foreign key (case_id, report_version) references analysis_reports(case_id, report_version)
);
