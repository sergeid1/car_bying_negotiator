alter table analysis_reports add column operation_id varchar(36) references operations(id);
create unique index uq_analysis_report_operation on analysis_reports(operation_id);
alter table analysis_cases add column core_review_blocked boolean not null default false;
