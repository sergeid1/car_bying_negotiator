-- Spec 2.1 (ticket 82): the Buyer sends approved dealer email from their own mailbox. Every outbound dealer
-- message records how it left: RELAY (every existing row), OWN_EMAIL_APP, or CONNECTED_MAILBOX (ticket 83).
-- A hand-off message waits as HANDED_OFF until the Buyer says they sent it, or is DISCARDED. Hand-off
-- messages and replies the Buyer pastes in have no relay identity.
alter table dealer_relay_messages add column send_path varchar(24) default 'RELAY' not null;
alter table dealer_relay_messages add constraint dealer_relay_message_send_paths
  check (send_path in ('RELAY', 'OWN_EMAIL_APP', 'CONNECTED_MAILBOX'));
alter table dealer_relay_messages alter column identity_id drop not null;
alter table dealer_relay_messages add constraint dealer_relay_message_identities
  check (send_path <> 'RELAY' or identity_id is not null);

-- Replace the unnamed state check with a named one that admits the hand-off states.
alter table dealer_relay_messages add column message_state varchar(24);
update dealer_relay_messages set message_state = state;
alter table dealer_relay_messages drop column state;
alter table dealer_relay_messages rename column message_state to state;
alter table dealer_relay_messages alter column state set not null;
alter table dealer_relay_messages add constraint dealer_relay_message_states check (state in (
  'DELIVERY_UNKNOWN', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED', 'RECEIVED', 'REJECTED_MALWARE', 'HANDED_OFF', 'DISCARDED'));
