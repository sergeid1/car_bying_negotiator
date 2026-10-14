-- Google and Apple sign-in (ticket 113). An attempt is keyed by the keyed hash of its single-use state and bound to
-- the starting browser by the keyed hash of an HttpOnly cookie. The PKCE verifier, nonce and code are encrypted.
-- A consumed attempt keeps only its provider until expiry, so a reused state is refused with the provider's name.
CREATE TABLE buyer_account_social_sign_in_attempts (
  state_hash VARCHAR(128) PRIMARY KEY,
  provider VARCHAR(20) NOT NULL,
  binding_hash VARCHAR(128) NOT NULL,
  verifier_ciphertext TEXT NOT NULL,
  nonce_ciphertext TEXT NOT NULL,
  code_ciphertext TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX social_sign_in_attempt_expiry ON buyer_account_social_sign_in_attempts(expires_at);
-- A provider identity linked to an account: the provider and a keyed hash of its subject, never the subject itself.
CREATE TABLE buyer_account_identities (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL REFERENCES buyer_accounts(id),
  provider VARCHAR(20) NOT NULL,
  subject_hash VARCHAR(128) NOT NULL,
  linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT account_identity_subject UNIQUE (provider, subject_hash)
);
CREATE INDEX account_identities_owner ON buyer_account_identities(account_id);
