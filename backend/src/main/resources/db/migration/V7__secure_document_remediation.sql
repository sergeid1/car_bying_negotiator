alter table source_documents
  add column original_deleted_at timestamp with time zone;

alter table document_pages
  add column normalized_deleted_at timestamp with time zone;

create table document_operation_retries (
  id varchar(36) primary key,
  operation_id varchar(36) not null references document_processing_operations(id),
  idempotency_key varchar(200) not null,
  created_at timestamp with time zone not null,
  unique (operation_id, idempotency_key)
);

create index idx_source_documents_retention
  on source_documents(lifecycle, retention_deadline);
