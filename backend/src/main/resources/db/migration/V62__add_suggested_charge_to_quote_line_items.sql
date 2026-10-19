-- Ticket 142: a model-backed reader may suggest which catalogue charge a line is. The suggestion is shown to the
-- Buyer to confirm and never sets the line's category on its own.
alter table quote_line_items add column suggested_charge_id varchar(64);
