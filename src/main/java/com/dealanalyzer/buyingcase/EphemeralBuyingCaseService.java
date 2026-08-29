package com.dealanalyzer.buyingcase;

import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredEphemeralCase;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EphemeralBuyingCaseService {
  private final EphemeralCasePersistence persistence;
  private final CaseAccessService caseAccess;

  EphemeralBuyingCaseService(
      EphemeralCasePersistence persistence,
      CaseAccessService caseAccess) {
    this.persistence = persistence;
    this.caseAccess = caseAccess;
  }

  public CreatedBuyingCase create(CreateBuyingCaseRequest request, String idempotencyKey) {
    StoredEphemeralCase existing = persistence.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      return created(existing, caseAccess.reproduceToken(existing.id(), idempotencyKey));
    }

    String caseId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    var credential = caseAccess.issue(caseId, idempotencyKey, now);
    StoredEphemeralCase stored;
    try {
      stored = persistence.create(
          caseId,
          idempotencyKey,
          request,
          credential.protectedHash(),
          credential.expiresAt(),
          now);
    } catch (DataIntegrityViolationException duplicate) {
      stored = persistence.findByIdempotencyKey(idempotencyKey)
          .orElseThrow(() -> duplicate);
    }
    return created(stored, caseAccess.reproduceToken(stored.id(), idempotencyKey));
  }

  public BuyingCaseView reopen(String caseId, String accessToken) {
    StoredEphemeralCase stored = persistence.findById(caseId)
        .orElseThrow(this::notFound);
    if (accessToken == null
        || !caseAccess.verifies(
            stored.accessTokenHash(), stored.accessExpiresAt(), accessToken)) {
      throw notFound();
    }
    return view(stored);
  }

  public boolean isEphemeralCase(String caseId) {
    return persistence.findById(caseId)
        .map(stored -> stored.buyerJurisdiction() != null)
        .orElse(false);
  }

  public StoredConsent changeConsent(
      String caseId, String accessToken, ChangeConsentRequest request) {
    reopen(caseId, accessToken);
    return persistence.appendConsent(
        caseId,
        request.purpose(),
        request.granted(),
        request.policyVersion(),
        Instant.now());
  }

  private CreatedBuyingCase created(StoredEphemeralCase stored, String accessToken) {
    return new CreatedBuyingCase(view(stored), accessToken);
  }

  private BuyingCaseView view(StoredEphemeralCase stored) {
    BuyerLimitsView limits = stored.maxSellingPriceMinor() == null
            && stored.maxOutTheDoorPriceMinor() == null
        ? null
        : new BuyerLimitsView(
            stored.maxSellingPriceMinor(), stored.maxOutTheDoorPriceMinor());
    return new BuyingCaseView(
        stored.id(),
        stored.buyerJurisdiction(),
        stored.dealerLocation(),
        CreateBuyingCaseRequest.PurchaseMethod.valueOf(stored.purchaseMethod()),
        stored.status(),
        limits,
        stored.consents(),
        stored.accessExpiresAt(),
        stored.createdAt(),
        stored.updatedAt());
  }

  private ResourceNotFoundException notFound() {
    return new ResourceNotFoundException("Buying Case not found");
  }

  public record CreatedBuyingCase(
      @JsonProperty("case") BuyingCaseView buyingCase, String accessToken) {}

  public record BuyingCaseView(
      String id,
      String buyerJurisdiction,
      String dealerLocation,
      CreateBuyingCaseRequest.PurchaseMethod purchaseMethod,
      String status,
      BuyerLimitsView buyerLimits,
      java.util.List<StoredConsent> consents,
      Instant accessExpiresAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record BuyerLimitsView(Long maxSellingPriceMinor, Long maxOutTheDoorPriceMinor) {}
}
