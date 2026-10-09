CREATE TABLE buyer_account_passkeys (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL REFERENCES buyer_accounts(id),
  credential_id VARCHAR(1400) NOT NULL UNIQUE,
  public_key_cose TEXT NOT NULL,
  nickname VARCHAR(80) NOT NULL,
  signature_count BIGINT NOT NULL,
  backup_eligible BOOLEAN NOT NULL,
  backup_state BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  last_used_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX account_passkeys_owner ON buyer_account_passkeys(account_id);
CREATE TABLE buyer_account_passkey_challenges (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) REFERENCES buyer_accounts(id),
  session_hash VARCHAR(128),
  ceremony VARCHAR(20) NOT NULL,
  options_json TEXT NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX account_passkey_challenge_expiry ON buyer_account_passkey_challenges(expires_at);
CREATE TABLE buyer_account_security_events (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL REFERENCES buyer_accounts(id),
  event_type VARCHAR(40) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX account_security_events_owner ON buyer_account_security_events(account_id);
