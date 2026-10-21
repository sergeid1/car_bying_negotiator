-- Ticket 146: the language a signed-in Buyer chose for the screens, so it follows them to another device.
-- Null until the Buyer chooses; the browser's own choice applies until then.
alter table buyer_accounts add column preferred_language varchar(8);
alter table buyer_accounts add constraint buyer_accounts_preferred_language_check
  check (preferred_language is null or preferred_language in ('en', 'es'));
