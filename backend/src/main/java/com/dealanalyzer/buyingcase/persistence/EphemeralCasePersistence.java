package com.dealanalyzer.buyingcase.persistence;

import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest;
import com.dealanalyzer.buyingcase.ChangeConsentRequest.ConsentPurpose;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EphemeralCasePersistence {
  private final AnalysisCaseRepository cases;
  private final ConsentRecordRepository consents;

  EphemeralCasePersistence(
      AnalysisCaseRepository cases, ConsentRecordRepository consents) {
    this.cases = cases;
    this.consents = consents;
  }

  public Optional<StoredEphemeralCase> findByIdempotencyKey(String idempotencyKey) {
    return cases.findByCreationIdempotencyKey(idempotencyKey).map(this::stored);
  }

  public Optional<StoredEphemeralCase> findById(String caseId) {
    return cases.findById(caseId).map(this::stored);
  }

  @Transactional
  public void updateStatus(String caseId, String status, Instant now) {
    AnalysisCaseEntity buyingCase = cases.findLockedById(caseId)
        .orElseThrow(() -> new IllegalArgumentException("Buying Case not found"));
    buyingCase.status = status;
    buyingCase.updatedAt = now;
  }

  @Transactional
  public StoredEphemeralCase create(
      String caseId,
      String idempotencyKey,
      CreateBuyingCaseRequest request,
      String accessTokenHash,
      Instant expiresAt,
      String consentActor,
      String consentSource,
      String consentSchemaVersion,
      Instant now) {
    var limits = request.buyerLimits();
    AnalysisCaseEntity saved = cases.saveAndFlush(AnalysisCaseEntity.ephemeral(
        caseId,
        request.buyerJurisdiction(),
        request.dealerLocation(),
        request.purchaseMethod().name(),
        limits == null ? null : limits.maxSellingPriceMinor(),
        limits == null ? null : limits.maxOutTheDoorPriceMinor(),
        accessTokenHash,
        expiresAt,
        idempotencyKey,
        now));
    saveInitialConsents(
        saved.id,
        request.consents(),
        consentActor,
        consentSource,
        consentSchemaVersion,
        now);
    return stored(saved);
  }

  @Transactional
  public StoredConsent appendConsent(
      String caseId,
      ConsentPurpose purpose,
      boolean granted,
      String policyVersion,
      String actor,
      String source,
      String schemaVersion,
      Instant now) {
    cases.findLockedById(caseId)
        .orElseThrow(() -> new IllegalArgumentException("Buying Case not found"));
    Optional<ConsentRecordEntity> previous =
        consents.findTopByCaseIdAndPurposeOrderByConsentVersionDesc(caseId, purpose.name());
    int version = previous.map(record -> record.consentVersion + 1).orElse(1);
    String action = granted
        ? "GRANTED"
        : previous.filter(record -> record.action.equals("GRANTED")).isPresent()
            ? "REVOKED"
            : "DECLINED";
    return stored(consents.save(new ConsentRecordEntity(
        UUID.randomUUID().toString(),
        caseId,
        purpose.name(),
        version,
        action,
        policyVersion,
        actor,
        source,
        schemaVersion,
        now)));
  }

  private void saveInitialConsents(
      String caseId,
      CreateBuyingCaseRequest.InitialConsents decisions,
      String actor,
      String source,
      String schemaVersion,
      Instant now) {
    consents.saveAll(List.of(
        initialConsent(
            caseId,
            ConsentPurpose.SERVICE_PROCESSING,
            decisions.serviceProcessing(),
            actor,
            source,
            schemaVersion,
            now),
        initialConsent(
            caseId,
            ConsentPurpose.HUMAN_REVIEW,
            decisions.humanReview(),
            actor,
            source,
            schemaVersion,
            now),
        initialConsent(
            caseId,
            ConsentPurpose.ANONYMIZED_RESEARCH,
            decisions.anonymizedResearch(),
            actor,
            source,
            schemaVersion,
            now)));
  }

  private ConsentRecordEntity initialConsent(
      String caseId,
      ConsentPurpose purpose,
      CreateBuyingCaseRequest.ConsentDecision decision,
      String actor,
      String source,
      String schemaVersion,
      Instant now) {
    return new ConsentRecordEntity(
        UUID.randomUUID().toString(),
        caseId,
        purpose.name(),
        1,
        decision.granted() ? "GRANTED" : "DECLINED",
        decision.policyVersion(),
        actor,
        source,
        schemaVersion,
        now);
  }

  private StoredEphemeralCase stored(AnalysisCaseEntity entity) {
    List<StoredConsent> consentHistory = consents.findByCaseId(entity.id).stream()
        .map(this::stored)
        .sorted(Comparator
            .comparing(StoredConsent::occurredAt)
            .thenComparing(consent -> consent.purpose().ordinal())
            .thenComparingInt(StoredConsent::consentVersion))
        .toList();
    return new StoredEphemeralCase(
        entity.id,
        entity.buyerJurisdiction,
        entity.dealerLocation,
        entity.purchaseMethod,
        entity.status,
        entity.maxSellingPriceMinor,
        entity.maxOutTheDoorPriceMinor,
        entity.accessTokenHash,
        entity.accessExpiresAt,
        entity.createdAt,
        entity.updatedAt,
        consentHistory);
  }

  private StoredConsent stored(ConsentRecordEntity entity) {
    return new StoredConsent(
        ConsentPurpose.valueOf(entity.purpose),
        entity.consentVersion,
        ConsentAction.valueOf(entity.action),
        entity.policyVersion,
        entity.actor,
        entity.source,
        entity.schemaVersion,
        entity.occurredAt);
  }

  public record StoredEphemeralCase(
      String id,
      String buyerJurisdiction,
      String dealerLocation,
      String purchaseMethod,
      String status,
      Long maxSellingPriceMinor,
      Long maxOutTheDoorPriceMinor,
      String accessTokenHash,
      Instant accessExpiresAt,
      Instant createdAt,
      Instant updatedAt,
      List<StoredConsent> consents) {}

  public enum ConsentAction { GRANTED, DECLINED, REVOKED }

  public record StoredConsent(
      ConsentPurpose purpose,
      int consentVersion,
      ConsentAction action,
      String policyVersion,
      String actor,
      String source,
      String schemaVersion,
      Instant occurredAt) {}
}
