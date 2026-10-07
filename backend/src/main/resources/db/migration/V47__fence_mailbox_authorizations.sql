-- Incremented under the account lock by authorization start and disconnect.
alter table buyer_accounts add column mailbox_generation bigint not null default 0;
