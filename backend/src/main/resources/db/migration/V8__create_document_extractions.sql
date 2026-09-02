create table document_extractions (
  id varchar(36) primary key,
  document_id varchar(36) not null references source_documents(id),
  case_id varchar(36) not null references analysis_cases(id),
  extraction_version integer not null,
  status varchar(32) not null,
  extractor_version varchar(100) not null,
  model_version varchar(100) not null,
  schema_version varchar(100) not null,
  overall_confidence decimal(6,5) not null,
  suspected_missing_pages boolean not null,
  document_warnings text not null,
  created_at timestamp with time zone not null,
  unique(document_id, extraction_version),
  constraint ck_extraction_version_positive check (extraction_version > 0),
  constraint ck_extraction_confidence check (overall_confidence between 0 and 1)
);

create index idx_document_extractions_latest
  on document_extractions(document_id, extraction_version desc);
create index idx_document_extractions_case
  on document_extractions(case_id, created_at desc);

create table candidate_deal_terms (
  id varchar(36) primary key,
  extraction_id varchar(36) not null references document_extractions(id),
  field_key varchar(64) not null,
  original_wording varchar(2000) not null,
  normalized_value varchar(500),
  normalized_unit varchar(32),
  page_number integer not null,
  region_x decimal(8,7) not null,
  region_y decimal(8,7) not null,
  region_width decimal(8,7) not null,
  region_height decimal(8,7) not null,
  confidence decimal(6,5) not null,
  material boolean not null,
  verification_state varchar(32) not null,
  created_at timestamp with time zone not null,
  constraint ck_candidate_page_positive check (page_number > 0),
  constraint ck_candidate_confidence check (confidence between 0 and 1),
  constraint ck_candidate_region check (
    region_x between 0 and 1 and region_y between 0 and 1
    and region_width > 0 and region_width <= 1
    and region_height > 0 and region_height <= 1
    and region_x + region_width <= 1
    and region_y + region_height <= 1)
);

create index idx_candidate_terms_extraction
  on candidate_deal_terms(extraction_id, field_key, id);

create table quote_line_items (
  id varchar(36) primary key,
  extraction_id varchar(36) not null references document_extractions(id),
  original_label varchar(500) not null,
  normalized_category varchar(64) not null,
  amount_minor bigint not null,
  currency varchar(3) not null,
  tax_claim varchar(32) not null,
  optionality_claim varchar(32) not null,
  page_number integer not null,
  region_x decimal(8,7) not null,
  region_y decimal(8,7) not null,
  region_width decimal(8,7) not null,
  region_height decimal(8,7) not null,
  confidence decimal(6,5) not null,
  verification_state varchar(32) not null,
  created_at timestamp with time zone not null,
  constraint ck_line_item_page_positive check (page_number > 0),
  constraint ck_line_item_confidence check (confidence between 0 and 1),
  constraint ck_line_item_region check (
    region_x between 0 and 1 and region_y between 0 and 1
    and region_width > 0 and region_width <= 1
    and region_height > 0 and region_height <= 1
    and region_x + region_width <= 1
    and region_y + region_height <= 1)
);

create index idx_quote_line_items_extraction
  on quote_line_items(extraction_id, id);

create table extraction_validation_issues (
  id varchar(36) primary key,
  extraction_id varchar(36) not null references document_extractions(id),
  severity varchar(16) not null,
  issue_code varchar(64) not null,
  field_key varchar(64),
  message varchar(500) not null,
  created_at timestamp with time zone not null
);

create index idx_extraction_issues_extraction
  on extraction_validation_issues(extraction_id, id);
