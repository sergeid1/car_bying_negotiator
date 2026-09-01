create table source_documents (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  original_filename varchar(255) not null,
  object_key varchar(255) not null unique,
  checksum_sha256 varchar(64) not null,
  verified_media_type varchar(40) not null,
  size_bytes bigint not null,
  page_count integer,
  lifecycle varchar(32) not null,
  retention_deadline timestamp with time zone not null,
  encryption_algorithm varchar(32) not null,
  encryption_key_reference varchar(128) not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null
);

create index idx_source_documents_case on source_documents(case_id);

create table document_pages (
  id varchar(36) primary key,
  document_id varchar(36) not null references source_documents(id),
  page_number integer not null,
  width_pixels integer,
  height_pixels integer,
  resolution_dpi integer,
  likely_missing boolean not null,
  likely_duplicate boolean not null,
  likely_rotated boolean not null,
  likely_cropped boolean not null,
  normalized_object_key varchar(255),
  created_at timestamp with time zone not null,
  unique(document_id, page_number)
);

create table document_processing_operations (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  document_id varchar(36) not null references source_documents(id),
  idempotency_key varchar(200) not null,
  stage varchar(40) not null,
  failed_stage varchar(40),
  error_code varchar(64),
  error_message varchar(500),
  retry_count integer not null,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  unique(case_id, idempotency_key)
);

create index idx_document_operations_stage on document_processing_operations(stage);
