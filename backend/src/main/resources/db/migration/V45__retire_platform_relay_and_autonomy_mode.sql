-- Spec 2.1 (ticket 84): the platform never emails a dealer in its own name and never sends without the Buyer.
-- Stored rows stay readable and exported: mandates approved with an autonomy mode keep it, and messages the
-- relay sent keep send_path RELAY, their state, and their relay identity.

-- New mandates have no autonomy mode; only the Buyer sends.
alter table negotiation_mandates alter column autonomy_mode drop not null;

-- Each case's relay identity is now its private reply-intake address. New addresses carry no dealer opt-out link.
alter table dealer_relay_identities alter column opt_out_token_hash drop not null;
