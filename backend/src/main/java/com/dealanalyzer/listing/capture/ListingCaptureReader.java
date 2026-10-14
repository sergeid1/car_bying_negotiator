package com.dealanalyzer.listing.capture;

import com.dealanalyzer.document.persistence.DocumentIntakePersistence;
import com.dealanalyzer.extraction.DocumentExtractionSource;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Capture;
import com.dealanalyzer.listing.capture.ListingCaptureProvider.Reading;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads a normalized listing capture for its fields and keeps what was read, with each field's evidence region,
 * until the Buyer confirms it. Called by document processing; nothing read here is used before confirmation.
 */
@Service
public class ListingCaptureReader {
  private final ListingCaptureProvider provider;
  private final DocumentExtractionSource source;
  private final DocumentIntakePersistence documents;
  private final ProviderMeter meter;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  ListingCaptureReader(ListingCaptureProvider provider, DocumentExtractionSource source,
      DocumentIntakePersistence documents, ProviderMeter meter, JdbcTemplate jdbc, ObjectMapper json) {
    this.provider = provider; this.source = source; this.documents = documents;
    this.meter = meter; this.jdbc = jdbc; this.json = json;
  }

  /** status: READ when any field was read, otherwise UNREADABLE, and the Buyer types what the ad shows. */
  public record StoredReading(String id, String documentId, String status, String extractorVersion,
      String modelVersion, List<ListingCaptureProvider.Field> fields, List<String> warnings, Instant createdAt) {}

  public StoredReading read(String documentId) {
    var document = source.load(documentId);
    boolean plainText = "text/plain".equals(documents.requireExtractionDocument(documentId).mediaType());
    var capture = new Capture(document.caseId(), documentId, plainText, document.pages());
    int pages = document.pages().size();
    Reading reading = provider.selfMetered() ? provider.read(capture)
        : meter.call(document.caseId(), ProviderMeter.Operation.EXTRACTION, provider, pages, () -> provider.read(capture),
            value -> {
              ListingCaptureValidator.validate(value, pages);
              return ProviderMeter.Status.SUCCESS;
            });
    Reading valid = ListingCaptureValidator.validate(reading, pages);
    String status = valid.fields().stream().anyMatch(field -> field.value() != null) ? "READ" : "UNREADABLE";
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    jdbc.update("""
        insert into listing_capture_readings(id, case_id, document_id, extractor_version, model_version, status,
          fields_json, warnings_json, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, id, document.caseId(), documentId, valid.extractorVersion(), valid.modelVersion(), status,
        write(valid.fields()), write(valid.warnings()), Timestamp.from(now));
    return new StoredReading(id, documentId, status, valid.extractorVersion(), valid.modelVersion(), valid.fields(),
        valid.warnings(), now);
  }

  /** The latest reading of a capture in this case. */
  public Optional<StoredReading> latest(String caseId, String documentId) {
    return jdbc.query("""
        select id, document_id, status, extractor_version, model_version, fields_json, warnings_json, created_at
        from listing_capture_readings where case_id = ? and document_id = ?
        order by created_at desc, id desc fetch first 1 rows only
        """, (r, n) -> new StoredReading(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
            read(r.getString(6), new TypeReference<List<ListingCaptureProvider.Field>>() {}),
            read(r.getString(7), new TypeReference<List<String>>() {}), r.getTimestamp(8).toInstant()),
        caseId, documentId).stream().findFirst();
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to store a listing reading", failure); }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try { return json.readValue(value, type); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Unable to read a listing reading", failure); }
  }
}
