CREATE TABLE market_provider_snapshots (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL REFERENCES analysis_cases(id),
  source VARCHAR(100) NOT NULL,
  provider_version VARCHAR(100) NOT NULL,
  schema_version VARCHAR(100) NOT NULL,
  license_metadata VARCHAR(500) NOT NULL,
  artifact_reference VARCHAR(500),
  availability VARCHAR(32) NOT NULL,
  unavailable_reason VARCHAR(500),
  retrieved_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE comparable_snapshots (
  id VARCHAR(36) PRIMARY KEY,
  provider_snapshot_id VARCHAR(36) NOT NULL REFERENCES market_provider_snapshots(id),
  provider_observation_id VARCHAR(160),
  listing_url VARCHAR(1000),
  model_year INTEGER,
  make VARCHAR(100),
  model VARCHAR(100),
  trim_name VARCHAR(100),
  mileage BIGINT,
  price_minor BIGINT,
  currency VARCHAR(3),
  geography VARCHAR(200),
  observed_at TIMESTAMP WITH TIME ZONE,
  decision VARCHAR(32) NOT NULL,
  reason_code VARCHAR(64),
  reason VARCHAR(500),
  match_factors_json TEXT NOT NULL,
  adjustment_minor BIGINT,
  adjustment_precision VARCHAR(32),
  adjustment_explanation VARCHAR(1000),
  adjusted_price_minor BIGINT,
  ordinal INTEGER NOT NULL
);

CREATE INDEX idx_comparable_snapshots_provider
  ON comparable_snapshots(provider_snapshot_id, ordinal);

CREATE TABLE market_assessments (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL REFERENCES analysis_cases(id),
  provider_snapshot_id VARCHAR(36) NOT NULL REFERENCES market_provider_snapshots(id),
  schema_version VARCHAR(100) NOT NULL,
  assessment_version INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL,
  range_low_minor BIGINT,
  range_high_minor BIGINT,
  currency VARCHAR(3),
  geography VARCHAR(200) NOT NULL,
  oldest_observation_at TIMESTAMP WITH TIME ZONE,
  newest_observation_at TIMESTAMP WITH TIME ZONE,
  sample_size INTEGER NOT NULL,
  method_version VARCHAR(100) NOT NULL,
  confidence VARCHAR(32) NOT NULL,
  limitation VARCHAR(1000),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_market_assessment_version UNIQUE (case_id, assessment_version)
);

CREATE INDEX idx_market_assessments_latest
  ON market_assessments(case_id, assessment_version DESC);
