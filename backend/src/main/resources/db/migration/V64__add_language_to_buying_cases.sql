alter table analysis_cases add column language varchar(2) not null default 'en';
alter table analysis_cases add constraint buying_case_language check (language in ('en', 'es'));
