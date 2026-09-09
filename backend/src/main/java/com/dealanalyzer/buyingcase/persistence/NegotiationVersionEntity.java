package com.dealanalyzer.buyingcase.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Append-only text with a composite source report identifier and no entity associations. */
@Entity
@org.hibernate.annotations.Immutable
@Table(name = "negotiation_versions")
class NegotiationVersionEntity {
  @EmbeddedId
  AnalysisReportKey id;
  @Column(name = "payload_json", nullable = false, updatable = false)
  String payloadJson;
  protected NegotiationVersionEntity() {}
  NegotiationVersionEntity(AnalysisReportKey id, String payloadJson) {
    this.id = id; this.payloadJson = payloadJson;
  }
}
