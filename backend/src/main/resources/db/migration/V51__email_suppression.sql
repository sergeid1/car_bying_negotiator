create table email_suppressions (
  email_hash varchar(64) primary key,
  reason varchar(20) not null,
  created_at timestamp with time zone not null
);
