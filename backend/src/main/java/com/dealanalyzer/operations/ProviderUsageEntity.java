package com.dealanalyzer.operations;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "provider_usage")
class ProviderUsageEntity {
  @Id String id;
  @Column(name = "case_id") String caseId;
  @Column(name = "journey_id") String journeyId;
  @Column(name = "account_id") String accountId;
  @Column(name = "query_json", columnDefinition = "text") String queryJson;
  @Column(name = "result_count") Integer resultCount;
  @Column(name = "retrieved_at") Instant retrievedAt;
  @Column(name = "latency_millis") Long latencyMillis;
  @Column(name = "inventory_availability") String inventoryAvailability;
  String operation;
  String provider;
  @Column(name = "provider_version") String providerVersion;
  @Column(name = "model_version") String modelVersion;
  @Column(name = "schema_version") String schemaVersion;
  @Column(name = "prompt_version") String promptVersion;
  @Column(name = "threshold_set_version") String thresholdSetVersion;
  @Column(name = "evaluation_set_version") String evaluationSetVersion;
  @Column(name = "input_tokens") Long inputTokens;
  @Column(name = "output_tokens") Long outputTokens;
  String license;
  String unit;
  long units;
  @Column(name = "cost_usd", precision = 18, scale = 8) BigDecimal costUsd;
  String status;
  @Column(name = "created_at") Instant createdAt;
  @Column(name = "finished_at") Instant finishedAt;
}
