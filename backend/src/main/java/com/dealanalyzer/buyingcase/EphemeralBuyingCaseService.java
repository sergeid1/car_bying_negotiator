package com.dealanalyzer.buyingcase;

import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredEphemeralCase;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence.StoredConsent;
import com.dealanalyzer.pilot.AcquisitionChannels;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class EphemeralBuyingCaseService {
  private static final String CONSENT_ACTOR = "BUYER";
  private static final String CONSENT_SOURCE = "BUYER_API";
  private static final String CONSENT_SCHEMA_VERSION = "consent-record.v1";

  private final EphemeralCasePersistence persistence;
  private final CaseAccessService caseAccess;
  private final BuyingCaseLocation location;
  private final AcquisitionChannels channels;

  EphemeralBuyingCaseService(
      EphemeralCasePersistence persistence,
      CaseAccessService caseAccess,
      BuyingCaseLocation location,
      AcquisitionChannels channels) {
    this.persistence = persistence;
    this.caseAccess = caseAccess;
    this.location = location;
    this.channels = channels;
  }

  public CreatedBuyingCase create(
      CreateBuyingCaseRequest request, String idempotencyKey, String accessToken) {
    StoredEphemeralCase existing = persistence.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      return created(requireAuthorized(existing, accessToken), null);
    }

    // Refuse an unusable location before issuing a credential or storing anything.
    var resolved = location.resolve(request);

    // Only an allow-listed code is kept; anything else is no channel and never refuses the case.
    String channel = channels.admit(request.channel()).orElse(null);
    String caseId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    var credential = caseAccess.issue(now);
    StoredEphemeralCase stored;
    try {
      stored = persistence.create(
          caseId,
          idempotencyKey,
          request,
          channel,
          resolved.buyerJurisdiction(),
          resolved.dealerLocation(),
          credential.protectedHash(),
          credential.expiresAt(),
          CONSENT_ACTOR,
          CONSENT_SOURCE,
          CONSENT_SCHEMA_VERSION,
          now);
    } catch (DataIntegrityViolationException duplicate) {
      stored = persistence.findByIdempotencyKey(idempotencyKey)
          .orElseThrow(() -> duplicate);
      return created(requireAuthorized(stored, accessToken), null);
    }
    return created(stored, credential.token());
  }

  public BuyingCaseView reopen(String caseId, String accessToken) {
    return view(caseAccess.requireAuthorized(
        persistence.findById(caseId),
        accessToken,
        StoredEphemeralCase::accessTokenHash,
        StoredEphemeralCase::accessExpiresAt));
  }

  public String buyerState(String postalCode) { return location.buyerState(postalCode); }

  @org.springframework.transaction.annotation.Transactional
  public BuyingCaseView changeLanguage(String caseId, String accessToken, String language) {
    persistence.lock(caseId);
    reopen(caseId, accessToken);
    persistence.changeLanguage(caseId, language);
    return reopen(caseId, accessToken);
  }

  public String marketPostalCode(String caseId, String accessToken) {
    var buyingCase = reopen(caseId, accessToken);
    String dealer = buyingCase.dealerLocation();
    return dealer != null && dealer.matches("[0-9]{5}") ? dealer : persistence.buyerPostalCode(caseId);
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
        CONSENT_ACTOR,
        CONSENT_SOURCE,
        CONSENT_SCHEMA_VERSION,
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
        channels.referralDisclosure(stored.id()).orElse(null),
        stored.accessExpiresAt(),
        stored.createdAt(),
        stored.updatedAt(),
        stored.language());
  }

  private StoredEphemeralCase requireAuthorized(
      StoredEphemeralCase stored, String accessToken) {
    return caseAccess.requireAuthorized(
        java.util.Optional.of(stored),
        accessToken,
        StoredEphemeralCase::accessTokenHash,
        StoredEphemeralCase::accessExpiresAt);
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
      /** Configured disclosure when the case started from a referral partnership; otherwise null. */
      String referralDisclosure,
      Instant accessExpiresAt,
      Instant createdAt,
      Instant updatedAt,
      String language) {}

  public record BuyerLimitsView(Long maxSellingPriceMinor, Long maxOutTheDoorPriceMinor) {}
}
