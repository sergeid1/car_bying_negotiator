CREATE TABLE analysis_cases (
  id VARCHAR(36) PRIMARY KEY,
  buyer_zip VARCHAR(16) NOT NULL,
  dealer_zip VARCHAR(16) NOT NULL,
  purchase_method VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL,
  vehicle_json TEXT NOT NULL,
  dealer_offer_json TEXT NOT NULL,
  input_json TEXT NOT NULL,
  input_fingerprint VARCHAR(64) NOT NULL,
  current_report_version INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE operations (
  id VARCHAR(36) PRIMARY KEY,
  case_id VARCHAR(36) NOT NULL,
  stage VARCHAR(40) NOT NULL,
  error_message VARCHAR(500),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_operations_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id)
);

CREATE INDEX idx_operations_case_id ON operations(case_id);

CREATE TABLE analysis_reports (
  case_id VARCHAR(36) NOT NULL,
  report_version INTEGER NOT NULL,
  payload_json TEXT NOT NULL,
  analysis_fingerprint VARCHAR(64) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (case_id, report_version),
  CONSTRAINT fk_reports_case FOREIGN KEY (case_id) REFERENCES analysis_cases(id)
);

