-- A single guard serializes threshold freezing and the first outcome read.
create table contribution_review_guard (
  id integer primary key check (id = 1),
  revision bigint not null
);
insert into contribution_review_guard values (1, 0);

create table contribution_decision_records (
  id varchar(36) primary key,
  window_from date not null,
  window_to date not null,
  record_kind varchar(10) not null check (record_kind in ('FREEZE', 'REVIEW', 'DECISION')),
  version integer not null check (version >= 0),
  payload_json text not null,
  created_at timestamp with time zone not null,
  check (window_from < window_to),
  unique (window_from, window_to, record_kind, version)
);
