package com.dealanalyzer.buyingcase.persistence;
import com.dealanalyzer.buyingcase.*;
import com.dealanalyzer.recovery.RecoveryEmail;
import com.dealanalyzer.recovery.RequestEmailLimits;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class CaseRecoveryService {
  private final AnalysisCaseRepository cases;
  private final RecoveryContactRepository contacts;
  private final DeliveryRequestRepository deliveries;
  private final RecoveryTokenRepository tokens;
  private final CaseAccessService access;
  private final RecoveryEmail email;
  private final RequestEmailLimits emailLimits;
  private final String origin;
  private final Duration lifetime;
  public CaseRecoveryService(AnalysisCaseRepository cases, RecoveryContactRepository contacts,
      DeliveryRequestRepository deliveries, RecoveryTokenRepository tokens, CaseAccessService access,
      RecoveryEmail email, RequestEmailLimits emailLimits, @Value("${case.recovery.public-url:http://localhost:5173/}") String origin,
      @Value("${case.recovery.link-lifetime:PT15M}") Duration lifetime) {
    this.cases=cases; this.contacts=contacts; this.deliveries=deliveries; this.tokens=tokens;
    this.access=access; this.email=email; this.origin=origin; this.lifetime=lifetime;
    this.emailLimits=emailLimits;
  }
  @Transactional
  public void enroll(String id, String credential, String recipient, String key) {
    var buyingCase = access.requireAuthorized(cases.findLockedById(id), credential,
        c -> c.accessTokenHash, c -> c.accessExpiresAt);
    var contact = contacts.findById(id).orElseGet(RecoveryContactEntity::new);
    contact.caseId=id; contact.email=recipient; contact.expiresAt=buyingCase.accessExpiresAt;
    contacts.save(contact);
    deliver(buyingCase, recipient, key);
  }
  @Transactional
  public void request(String id, String recipient, String key) {
    var buyingCase = cases.findLockedById(id).orElse(null);
    var contact = contacts.findById(id).orElse(null);
    if (buyingCase == null || contact == null || !contact.email.equals(recipient)
        || !contact.expiresAt.isAfter(Instant.now()) || !buyingCase.accessExpiresAt.isAfter(Instant.now())) return;
    deliver(buyingCase, recipient, key);
  }
  private void deliver(AnalysisCaseEntity buyingCase, String recipient, String key) {
    var previous = deliveries.findByCaseIdAndRequestKey(buyingCase.id, key).orElse(null);
    if (previous != null) {
      if (!previous.email.equals(recipient)) throw new IdempotencyConflictException();
      return;
    }
    if (!emailLimits.acquireDelivery()) return;
    var credential = access.issue(Instant.now());
    var delivery = new DeliveryRequestEntity(); delivery.id=UUID.randomUUID().toString();
    delivery.caseId=buyingCase.id; delivery.requestKey=key; delivery.email=recipient;
    delivery.expiresAt=Instant.now().plus(lifetime);
    if (delivery.expiresAt.isAfter(buyingCase.accessExpiresAt)) delivery.expiresAt=buyingCase.accessExpiresAt;
    deliveries.saveAndFlush(delivery);
    var token = new RecoveryTokenEntity(); token.tokenHash=access.protectRecoveryToken(credential.token());
    token.caseId=buyingCase.id; token.deliveryId=delivery.id; token.expiresAt=delivery.expiresAt;
    tokens.saveAndFlush(token);
    if ("es".equals(buyingCase.language))
      email.send(recipient, origin + "#recover=" + credential.token(), RecoveryEmail.Purpose.CASE_RECOVERY, "es");
    else email.send(recipient, origin + "#recover=" + credential.token());
  }
  @Transactional
  public Recovered verify(String supplied) {
    // Always lock the case before the token to share the same ordering as issuance.
    String hash=access.protectRecoveryToken(supplied);
    var candidate=tokens.findProjectedByTokenHash(hash).orElseThrow(this::missing);
    var buyingCase=cases.findLockedById(candidate.getCaseId()).orElseThrow(this::missing);
    var token=tokens.findLockedByTokenHash(hash).orElseThrow(this::missing);
    var contact=contacts.findById(buyingCase.id).orElseThrow(this::missing);
    var delivery=deliveries.findById(token.deliveryId).orElseThrow(this::missing);
    if (token.consumedAt != null || !token.expiresAt.isAfter(Instant.now())
        || !buyingCase.accessExpiresAt.isAfter(Instant.now()) || !contact.email.equals(delivery.email)) throw missing();
    token.consumedAt=Instant.now();
    var credential=access.issue(Instant.now());
    buyingCase.accessTokenHash=credential.protectedHash(); // Recovery never extends case retention.
    return new Recovered(buyingCase.id, credential.token(), buyingCase.accessExpiresAt);
  }
  private ResourceNotFoundException missing() { return new ResourceNotFoundException("Recovery link unavailable"); }
  public record Recovered(String caseId, String accessToken, Instant expiresAt) {}
}
