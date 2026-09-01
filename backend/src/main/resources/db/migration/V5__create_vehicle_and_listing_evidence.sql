CREATE TABLE vehicles (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  vin VARCHAR(17),
  model_year INTEGER,
  make VARCHAR(100),
  model VARCHAR(100),
  trim_name VARCHAR(100),
  mileage BIGINT,
  confirmation_source VARCHAR(32) NOT NULL,
  confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_vehicles_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id),
  CONSTRAINT uq_vehicles_case UNIQUE (case_id),
  CONSTRAINT ck_vehicles_supported_identity CHECK (
    vin IS NOT NULL OR (
      model_year IS NOT NULL
      AND make IS NOT NULL
      AND model IS NOT NULL
      AND mileage IS NOT NULL
    )
  )
);

CREATE TABLE dealers (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  source VARCHAR(80) NOT NULL,
  provider_dealer_id VARCHAR(120) NOT NULL,
  name VARCHAR(200) NOT NULL,
  city VARCHAR(100),
  state_code VARCHAR(20),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_dealers_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id),
  CONSTRAINT uq_dealers_case_provider UNIQUE (case_id, source, provider_dealer_id)
);

CREATE TABLE listing_snapshots (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  dealer_id VARCHAR(36) NOT NULL,
  provider_listing_id VARCHAR(120) NOT NULL,
  listing_url VARCHAR(1000) NOT NULL,
  vin VARCHAR(17),
  model_year INTEGER,
  make VARCHAR(100),
  model VARCHAR(100),
  trim_name VARCHAR(100),
  mileage BIGINT,
  advertised_price_minor BIGINT,
  currency VARCHAR(3),
  source VARCHAR(80) NOT NULL,
  retrieved_at TIMESTAMP WITH TIME ZONE NOT NULL,
  provider_version VARCHAR(100) NOT NULL,
  license_metadata VARCHAR(500) NOT NULL,
  artifact_reference VARCHAR(500),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_listing_snapshots_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id),
  CONSTRAINT fk_listing_snapshots_dealer FOREIGN KEY (dealer_id) REFERENCES dealers(id)
);

CREATE INDEX idx_listing_snapshots_case ON listing_snapshots(case_id, retrieved_at);

CREATE TABLE vehicle_identity_conflicts (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  listing_snapshot_id VARCHAR(36) NOT NULL,
  field_name VARCHAR(40) NOT NULL,
  confirmed_value VARCHAR(200) NOT NULL,
  listing_value VARCHAR(200) NOT NULL,
  status VARCHAR(40) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_vehicle_identity_conflicts_case
    FOREIGN KEY (case_id) REFERENCES analysis_cases(id),
  CONSTRAINT fk_vehicle_identity_conflicts_snapshot
    FOREIGN KEY (listing_snapshot_id) REFERENCES listing_snapshots(id),
  CONSTRAINT uq_vehicle_identity_conflict_snapshot_field
    UNIQUE (listing_snapshot_id, field_name)
);

CREATE INDEX idx_vehicle_identity_conflicts_case
  ON vehicle_identity_conflicts(case_id, created_at);
