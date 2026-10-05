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
  private final com.dealanalyzer.operations.ReviewConsentLifecycle reviews;
  private final jakarta.persistence.EntityManager entities;

  EphemeralCasePersistence(
      AnalysisCaseRepository cases, ConsentRecordRepository consents, com.dealanalyzer.operations.ReviewConsentLifecycle reviews,
      jakarta.persistence.EntityManager entities) {
    this.reviews = reviews;
    this.entities = entities;
    this.cases = cases;
    this.consents = consents;
  }

  public Optional<StoredEphemeralCase> findByIdempotencyKey(String idempotencyKey) {
    return cases.findByCreationIdempotencyKey(idempotencyKey).map(this::stored);
  }

  public Optional<StoredEphemeralCase> findById(String caseId) {
    return cases.findById(caseId).map(this::stored);
  }

  public boolean requiresEvidenceReview(String caseId) {
    return cases.findById(caseId).map(value -> value.coreReviewBlocked
        && !"READY_TO_ANALYZE".equals(value.status)).orElse(true);
  }

  /**
   * An unknown case is refused like a wrong credential, so the lock taken before authorization reveals nothing.
   * A case this transaction already read is refreshed under the row lock: a write committed between that read and
   * the lock is then waited for and seen, instead of failing the entity's version check.
   */
  @Transactional
  public void lock(String caseId) {
    // Looked up without creating a proxy: entity fields are written directly, and a write to a proxy is lost.
    var session = entities.unwrap(org.hibernate.engine.spi.SessionImplementor.class);
    var persister = session.getFactory().getMappingMetamodel().getEntityDescriptor(AnalysisCaseEntity.class);
    Object managed = session.getPersistenceContextInternal().getEntity(session.generateEntityKey(caseId, persister));
    if (managed != null) {
      // Changes this transaction already made are written first, so the refresh cannot discard them.
      entities.flush();
      entities.refresh(managed, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
      return;
    }
    cases.findLockedById(caseId)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException("Buying Case not found"));
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
      String acquisitionChannel,
      String buyerJurisdiction,
      String dealerLocation,
      String accessTokenHash,
      Instant expiresAt,
      String consentActor,
      String consentSource,
      String consentSchemaVersion,
      Instant now) {
    var limits = request.buyerLimits();
    AnalysisCaseEntity saved = cases.saveAndFlush(AnalysisCaseEntity.ephemeral(
        caseId,
        buyerJurisdiction,
        dealerLocation,
        request.purchaseMethod().name(),
        limits == null ? null : limits.maxSellingPriceMinor(),
        limits == null ? null : limits.maxOutTheDoorPriceMinor(),
        accessTokenHash,
        expiresAt,
        idempotencyKey,
        now));
    if (acquisitionChannel != null) {
      // Written outside the entity so no case read path can reach the channel.
      entities.createNativeQuery("update analysis_cases set acquisition_channel = :channel where id = :id")
          .setParameter("channel", acquisitionChannel).setParameter("id", saved.id).executeUpdate();
    }
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
    if (purpose == ConsentPurpose.HUMAN_REVIEW || purpose == ConsentPurpose.SERVICE_PROCESSING && !granted) {
      reviews.invalidate(caseId);
    }
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
