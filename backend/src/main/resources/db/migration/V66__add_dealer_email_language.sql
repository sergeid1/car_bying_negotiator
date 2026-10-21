-- Ticket 149: the Buyer can choose Spanish for one dealer's emails. English stays the default for every dealer,
-- and each outbound draft records the language it was written in, so a later switch never changes it.
alter table dealers add column email_language varchar(2) not null default 'en';
alter table dealers add constraint dealer_email_language check (email_language in ('en', 'es'));
alter table outbound_drafts add column language varchar(2) not null default 'en';
alter table outbound_drafts add constraint outbound_draft_language check (language in ('en', 'es'));
