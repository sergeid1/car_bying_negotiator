package com.dealanalyzer.operations;

import com.dealanalyzer.ai.ModelProvenance;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProviderUsageStore {
  private final EntityManager manager;
  ProviderUsageStore(EntityManager manager) { this.manager = manager; }
  private void requireActiveCase(String caseId) {
    if (manager.createQuery("select c.id from AnalysisCaseEntity c where c.id = :id and c.lifecycle = 'ACTIVE' and c.accessExpiresAt > :now", String.class)
        .setParameter("id", caseId).setParameter("now", Instant.now()).getResultList().isEmpty()) {
      throw new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found");
    }
  }
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String start(String caseId, ProviderMeter.Operation operation, Object provider, long units) {
    requireActiveCase(caseId);
    var row = new ProviderUsageEntity();
    row.id = UUID.randomUUID().toString(); row.caseId = caseId; row.operation = operation.name();
    // This catalog is code-owned. Never copy remote metadata or exception messages into telemetry.
    String type = provider.getClass().getName();
    String version = switch (type) {
      case "com.dealanalyzer.extraction.DeterministicDealerDocumentProvider" -> "deterministic-dealer-layout.v1";
      case "com.dealanalyzer.market.DeterministicFakeMarketDataProvider" -> "deterministic-market-provider.v1";
      case "com.dealanalyzer.listing.DeterministicListingEvidenceProvider" -> "listing-provider.v1";
      default -> "UNREGISTERED";
    };
    row.provider = version.equals("UNREGISTERED") ? "UNREGISTERED" : "LOCAL_DETERMINISTIC";
    row.providerVersion = version; row.modelVersion = version.equals("UNREGISTERED") ? "UNKNOWN" : "none";
    row.schemaVersion = operation == ProviderMeter.Operation.EXTRACTION ? "deal-terms.v1" :
        operation == ProviderMeter.Operation.MARKET_RETRIEVAL ? "market-provider.v1" : "NOT_APPLICABLE";
    row.license = version.equals("UNREGISTERED") ? "UNVERIFIED" :
        operation == ProviderMeter.Operation.EXTRACTION ? "LOCAL_PROCESSING" : "SYNTHETIC_DISPLAY_AND_CACHE_PERMITTED";
    row.unit = operation == ProviderMeter.Operation.EXTRACTION ? "PAGE" : "REQUEST";
    row.units = units; row.costUsd = version.equals("UNREGISTERED") ? null : BigDecimal.ZERO;
    row.status = "STARTED"; row.createdAt = Instant.now(); manager.persist(row);
    return row.id;
  }
  /**
   * Starts usage for a model call. Every value comes from code-owned configuration and task
   * definitions; nothing the vendor returns is copied here.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String startModel(String caseId, ProviderMeter.Operation operation, ModelProvenance provenance,
      String license, long units) {
    requireActiveCase(caseId);
    var row = new ProviderUsageEntity();
    row.id = UUID.randomUUID().toString(); row.caseId = caseId; row.operation = operation.name();
    row.provider = "ANTHROPIC"; row.providerVersion = "anthropic-messages-api";
    row.modelVersion = provenance.modelId(); row.schemaVersion = provenance.schemaVersion();
    row.promptVersion = provenance.promptVersion();
    row.thresholdSetVersion = provenance.thresholdSetVersion();
    row.evaluationSetVersion = provenance.evaluationSetVersion();
    row.license = license;
    row.unit = operation == ProviderMeter.Operation.EXTRACTION ? "PAGE" : "REQUEST";
    // Cost is unknown until the vendor reports tokens; a call that never finishes keeps null.
    row.units = units; row.costUsd = null;
    row.status = "STARTED"; row.createdAt = Instant.now(); manager.persist(row);
    return row.id;
  }
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, ProviderMeter.Status status, ProviderMeter.ModelUsage usage) {
    manager.createQuery("update ProviderUsageEntity u set u.status = :status, u.finishedAt = :now, u.inputTokens = :input, u.outputTokens = :output, u.costUsd = :cost where u.id = :id")
        .setParameter("status", status.name()).setParameter("now", Instant.now())
        .setParameter("input", usage.inputTokens()).setParameter("output", usage.outputTokens())
        .setParameter("cost", usage.costUsd()).setParameter("id", id).executeUpdate();
  }
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String id, ProviderMeter.Status status) {
    manager.createQuery("update ProviderUsageEntity u set u.status = :status, u.finishedAt = :now where u.id = :id")
        .setParameter("status", status.name()).setParameter("now", Instant.now()).setParameter("id", id).executeUpdate();
  }
}
