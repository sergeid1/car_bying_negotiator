-- Model calls record the full version tuple behind each output and the tokens the vendor reported.
-- Deterministic adapters leave these null; they have no prompt, evaluation set, or token spend.
alter table provider_usage add column prompt_version varchar(100);
alter table provider_usage add column threshold_set_version varchar(100);
alter table provider_usage add column evaluation_set_version varchar(100);
alter table provider_usage add column input_tokens bigint;
alter table provider_usage add column output_tokens bigint;
alter table provider_usage add constraint provider_usage_input_tokens_nonnegative check (input_tokens >= 0);
alter table provider_usage add constraint provider_usage_output_tokens_nonnegative check (output_tokens >= 0);
