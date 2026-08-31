ALTER TABLE analysis_cases ALTER COLUMN buyer_zip DROP NOT NULL;
ALTER TABLE analysis_cases ALTER COLUMN dealer_zip DROP NOT NULL;
ALTER TABLE analysis_cases ALTER COLUMN vehicle_json DROP NOT NULL;
ALTER TABLE analysis_cases ALTER COLUMN dealer_offer_json DROP NOT NULL;
ALTER TABLE analysis_cases ALTER COLUMN input_json DROP NOT NULL;
ALTER TABLE analysis_cases ALTER COLUMN input_fingerprint DROP NOT NULL;

ALTER TABLE analysis_cases ADD COLUMN buyer_jurisdiction VARCHAR(64);
ALTER TABLE analysis_cases ADD COLUMN dealer_location VARCHAR(200);
ALTER TABLE analysis_cases ADD COLUMN max_selling_price_minor BIGINT;
ALTER TABLE analysis_cases ADD COLUMN max_out_the_door_price_minor BIGINT;
ALTER TABLE analysis_cases ADD COLUMN access_token_hash VARCHAR(64);
ALTER TABLE analysis_cases ADD COLUMN access_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE analysis_cases ADD COLUMN creation_idempotency_key VARCHAR(200);
ALTER TABLE analysis_cases ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_analysis_cases_creation_idempotency_key
  ON analysis_cases(creation_idempotency_key);

CREATE TABLE consent_records (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  purpose VARCHAR(40) NOT NULL,
  consent_version INTEGER NOT NULL,
  action VARCHAR(16) NOT NULL,
  policy_version VARCHAR(100) NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_consent_records_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id),
  CONSTRAINT uq_consent_records_case_purpose_version
    UNIQUE (case_id, purpose, consent_version)
);

CREATE INDEX idx_consent_records_case ON consent_records(case_id);
