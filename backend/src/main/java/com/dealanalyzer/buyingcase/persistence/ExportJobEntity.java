package com.dealanalyzer.buyingcase.persistence;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name = "case_export_jobs")
class ExportJobEntity {
  @Id @Column(name = "id") String id;
  @Column(name = "case_id") String caseId;
  @Column(name = "request_key") String requestKey;
  @Column(name = "format") String format;
  @Column(name = "report_version") int reportVersion;
  @Column(name = "content_base64") String contentBase64;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "expires_at") Instant expiresAt;
  protected ExportJobEntity() {}
}
