-- The Buyer's own capture of a dealer's listing (ticket 115): a screenshot, a saved PDF of the page or pasted
-- text, taken through ordinary document intake. It is never read as dealer paperwork.
alter table source_documents drop constraint source_documents_purpose;
alter table source_documents add constraint source_documents_purpose
  check (purpose in ('DEALER_PAPERWORK', 'TRADE_OFFER_EVIDENCE', 'LISTING_CAPTURE'));

-- What was read from a capture, with each field's evidence region, before the Buyer confirms it.
create table listing_capture_readings (
  id varchar(36) primary key,
  case_id varchar(36) not null references analysis_cases(id),
  document_id varchar(36) not null references source_documents(id),
  extractor_version varchar(100) not null,
  model_version varchar(100) not null,
  status varchar(20) not null,
  fields_json text not null,
  warnings_json text not null,
  created_at timestamp with time zone not null,
  constraint listing_capture_reading_status check (status in ('READ', 'UNREADABLE'))
);
create index idx_listing_capture_readings_document on listing_capture_readings(document_id, created_at);
create index idx_listing_capture_readings_case on listing_capture_readings(case_id);

-- A confirmed capture becomes a Listing Snapshot with what the ad showed beside its price.
alter table listing_snapshots add column captured_on date;
alter table listing_snapshots add column price_conditions varchar(4000);
alter table listing_snapshots add column source_document_id varchar(36);
alter table listing_snapshots add column field_confirmations varchar(1000);
