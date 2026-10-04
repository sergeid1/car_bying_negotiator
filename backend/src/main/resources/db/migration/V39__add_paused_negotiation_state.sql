alter table case_negotiation_states add column negotiation_state varchar(32);
update case_negotiation_states set negotiation_state = state;
alter table case_negotiation_states drop column state;
alter table case_negotiation_states rename column negotiation_state to state;
alter table case_negotiation_states alter column state set not null;
alter table case_negotiation_states add constraint case_negotiation_state_values check (
  state in ('NO_MANDATE','MANDATE_ACTIVE','AWAITING_DEALER','AWAITING_BUYER','PAUSED','AGREED','STOPPED','EXPIRED'));
