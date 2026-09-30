-- Report-open sessions carry no submitted amounts or question text.
alter table pilot_events add column report_version integer;
