package com.dealanalyzer.verification.persistence;

import com.dealanalyzer.buyingcase.IdempotencyConflictException;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.verification.CorrectDealTermRequest;
import com.dealanalyzer.verification.VerifyDealerOfferRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DealerOfferPersistence {
  private final DealTermCorrectionRepository corrections;
  private final DealerOfferRepository offers;
  private final DealTermVerificationRepository verifications;
  private final AuditEventRepository auditEvents;
  private final ObjectMapper json;
  private final EphemeralCasePersistence cases;

  DealerOfferPersistence(
      DealTermCorrectionRepository corrections,
      DealerOfferRepository offers,
      DealTermVerificationRepository verifications,
      AuditEventRepository auditEvents,
      EphemeralCasePersistence cases,
      ObjectMapper json) {
    this.corrections = corrections;
    this.offers = offers;
    this.verifications = verifications;
    this.auditEvents = auditEvents;
    this.cases = cases;
    this.json = json;
  }

  public Optional<CorrectionView> findCorrection(String caseId, String idempotencyKey) {
    return corrections.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey).map(this::view);
  }

  public Optional<CorrectionView> currentCorrection(
      String extractionId, String targetType, String targetId) {
    return corrections
        .findTopByExtractionIdAndTargetTypeAndTargetIdOrderByCorrectionVersionDesc(
            extractionId, targetType, targetId)
        .map(this::view);
  }

  public List<CorrectionView> correctionHistory(
      String extractionId, String targetType, String targetId) {
    return corrections.findByExtractionIdAndTargetTypeAndTargetIdOrderByCorrectionVersionAsc(
        extractionId, targetType, targetId).stream().map(this::view).toList();
  }

  @Transactional
  public CorrectionView appendCorrection(
      String caseId, String extractionId, CorrectDealTermRequest request,
      String idempotencyKey, String fingerprint, Instant now) {
    int version = currentCorrection(extractionId, request.targetType().name(), request.targetId())
        .map(value -> value.version() + 1).orElse(1);
    DealTermCorrectionEntity entity = corrections.saveAndFlush(new DealTermCorrectionEntity(
        UUID.randomUUID().toString(), caseId, extractionId, request.targetType().name(),
        request.targetId(), version, idempotencyKey, fingerprint, request.normalizedValue(),
        request.normalizedUnit(), request.category(), request.amountMinor(), now));
    auditEvents.save(new AuditEventEntity(
        UUID.randomUUID().toString(), caseId, "DEAL_TERM_CORRECTED", "DEAL_TERM_CORRECTION",
        entity.id, version, idempotencyKey,
        "{\"targetType\":\"" + entity.targetType + "\",\"targetId\":\""
            + entity.targetId + "\"}", now));
    cases.updateStatus(caseId, "NEEDS_REVIEW", now);
    return view(entity);
  }

  public boolean isVerified(String extractionId, String targetType, String targetId) {
    return verifications
        .findTopByExtractionIdAndTargetTypeAndTargetIdOrderByVerificationVersionDesc(
            extractionId, targetType, targetId).isPresent();
  }

  public Optional<DealerOfferView> findOffer(String caseId, String idempotencyKey) {
    return offers.findByCaseIdAndIdempotencyKey(caseId, idempotencyKey).map(this::view);
  }

  public Optional<DealerOfferView> latestOffer(String caseId) {
    return offers.findTopByCaseIdOrderByOfferVersionDesc(caseId).map(this::view);
  }

  public DealerOfferView requireOffer(String caseId, int version) {
    return offers.findByCaseIdAndOfferVersion(caseId, version).map(this::view)
        .orElseThrow(() -> new ResourceNotFoundException("Dealer Offer not found"));
  }

  @Transactional
  public DealerOfferView appendOffer(
      String caseId, String extractionId, String idempotencyKey, String fingerprint,
      Object terms, List<VerifiedTarget> verifiedTargets, Instant now) {
    int version = offers.findTopByCaseIdOrderByOfferVersionDesc(caseId)
        .map(value -> value.offerVersion + 1).orElse(1);
    String offerId = UUID.randomUUID().toString();
    DealerOfferEntity offer = offers.saveAndFlush(new DealerOfferEntity(
        offerId, caseId, extractionId, version, idempotencyKey, fingerprint,
        writeJson(terms), now));
    verifications.saveAll(verifiedTargets.stream().map(target -> new DealTermVerificationEntity(
        UUID.randomUUID().toString(), caseId, extractionId, offerId, target.targetType(),
        target.targetId(), version, target.effectiveValue(), now)).toList());
    auditEvents.save(new AuditEventEntity(
        UUID.randomUUID().toString(), caseId, "DEALER_OFFER_VERIFIED", "DEALER_OFFER",
        offerId, version, idempotencyKey,
        "{\"extractionId\":\"" + extractionId + "\"}", now));
    cases.updateStatus(caseId, "READY_TO_ANALYZE", now);
    return view(offer);
  }

  public String fingerprint(Object value) {
    try {
      byte[] serialized = json.writeValueAsBytes(value);
      return java.util.HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(serialized));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to fingerprint verification request", exception);
    }
  }

  public void requireMatchingFingerprint(String expected, String actual) {
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
      throw new IdempotencyConflictException();
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to store immutable Dealer Offer", exception);
    }
  }

  private CorrectionView view(DealTermCorrectionEntity value) {
    return new CorrectionView(
        value.id, value.extractionId, value.targetType, value.targetId,
        value.correctionVersion, value.requestFingerprint, value.normalizedValue,
        value.normalizedUnit, value.normalizedCategory, value.amountMinor, value.createdAt);
  }

  private DealerOfferView view(DealerOfferEntity value) {
    try {
      return new DealerOfferView(
          value.id, value.caseId, value.extractionId, value.offerVersion, value.status,
          value.schemaVersion, value.requestFingerprint, json.readTree(value.termsJson),
          value.createdAt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read immutable Dealer Offer", exception);
    }
  }

  public record CorrectionView(
      String id, String extractionId, String targetType, String targetId, int version,
      String requestFingerprint, String normalizedValue, String normalizedUnit,
      String category, Long amountMinor, Instant createdAt) {}

  public record DealerOfferView(
      String id, String caseId, String extractionId, int version, String status,
      String schemaVersion, String requestFingerprint, JsonNode terms, Instant createdAt) {}

  public record VerifiedTarget(String targetType, String targetId, String effectiveValue) {}
}
