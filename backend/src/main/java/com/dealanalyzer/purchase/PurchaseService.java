package com.dealanalyzer.purchase;

import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.BuyingCaseStore;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PurchaseService {
  private final EntityManager manager;
  private final BuyingCaseStore cases;
  private final CaseAccessService access;
  private final OfferCatalog offers;
  private final CheckoutProvider checkout;
  public PurchaseService(EntityManager manager, BuyingCaseStore cases, CaseAccessService access,
      OfferCatalog offers, CheckoutProvider checkout) {
    this.manager = manager; this.cases = cases; this.access = access; this.offers = offers; this.checkout = checkout;
  }
  private void authorize(String caseId, String token) {
    access.requireAuthorized(cases.findCase(caseId), token,
        BuyingCaseStore.StoredCase::accessTokenHash, BuyingCaseStore.StoredCase::accessExpiresAt);
  }
  public record Entitlement(String reportAccess, String offerId) {}
  public record Purchase(String purchaseId, String status, String checkoutUrl, String checkout) {}
  public record Created(Purchase purchase, boolean replay) {}
  @Transactional(readOnly = true)
  public Entitlement entitlement(String caseId, String token) {
    authorize(caseId, token);
    String offer = offers.activeId();
    boolean purchased = ((Number) manager.createNativeQuery("select count(*) from case_purchases where case_id = :id and status = 'PAID'")
        .setParameter("id", caseId).getSingleResult()).longValue() > 0;
    return new Entitlement(purchased ? "PURCHASED" : offer == null ? "FREE_NO_OFFER" : "PURCHASE_REQUIRED", offer);
  }
  public void requireReport(String caseId, String token) {
    var entitlement = entitlement(caseId, token);
    if (entitlement.reportAccess().equals("PURCHASE_REQUIRED"))
      throw new PurchaseFailure(402, "PURCHASE_REQUIRED", entitlement.offerId());
  }
  @Transactional
  public Created create(String caseId, String token, String key, String offerId) {
    authorize(caseId, token);
    // Serialize purchases and deletion on the owning case, including idempotent retries.
    manager.createNativeQuery("select id from analysis_cases where id = :id for update")
        .setParameter("id", caseId).getSingleResult();
    var previous = rows(caseId);
    var replay = manager.createNativeQuery("select offer_id from case_purchase_requests where case_id = :id and request_key = :key")
        .setParameter("id", caseId).setParameter("key", key).getResultList();
    if (!replay.isEmpty()) {
      if (!replay.getFirst().equals(offerId)) throw new PurchaseFailure(409, "OFFER_CHANGED", null);
      return new Created(view(previous.getFirst()), true);
    }
    String active = offers.activeId();
    if (active == null) throw new PurchaseFailure(409, "NO_ACTIVE_OFFER", null);
    if (!active.equals(offerId)) throw new PurchaseFailure(409, "OFFER_CHANGED", null);
    if (!previous.isEmpty()) {
      var purchase = view(previous.getFirst());
      recordRequest(caseId, key, purchase.purchaseId(), offerId);
      return new Created(purchase, true);
    }
    var price = offers.current().path("price");
    String id = UUID.randomUUID().toString();
    var result = checkout.checkout(id, active, price.path("amountMinor").asLong(), price.path("currency").asText());
    manager.createNativeQuery("""
        insert into case_purchases (id, case_id, request_key, offer_id, amount_minor, currency, status, checkout, checkout_url, created_at)
        values (:id, :caseId, :key, :offer, :amount, :currency, :status, :checkout, :url, :now)
        """).setParameter("id", id).setParameter("caseId", caseId).setParameter("key", key)
        .setParameter("offer", active).setParameter("amount", price.path("amountMinor").asLong())
        .setParameter("currency", price.path("currency").asText()).setParameter("status", result.status())
        .setParameter("checkout", checkout.name()).setParameter("url", result.checkoutUrl())
        .setParameter("now", Instant.now()).executeUpdate();
    recordRequest(caseId, key, id, offerId);
    return new Created(new Purchase(id, result.status(), result.checkoutUrl(), checkout.name()), false);
  }
  private void recordRequest(String caseId, String key, String purchaseId, String offerId) {
    manager.createNativeQuery("insert into case_purchase_requests (case_id, request_key, purchase_id, offer_id) values (:caseId, :key, :purchase, :offer)")
        .setParameter("caseId", caseId).setParameter("key", key).setParameter("purchase", purchaseId)
        .setParameter("offer", offerId).executeUpdate();
  }
  @SuppressWarnings("unchecked")
  private List<Object[]> rows(String caseId) {
    return manager.createNativeQuery("select id, status, checkout_url, checkout from case_purchases where case_id = :id")
        .setParameter("id", caseId).getResultList();
  }
  private Purchase view(Object[] row) { return new Purchase((String) row[0], (String) row[1], (String) row[2], (String) row[3]); }
  @Transactional
  public java.util.Map<String, String> refund(String purchaseId, String operator) {
    int changed = manager.createNativeQuery("update case_purchases set refunded_at = coalesce(refunded_at, :now), refunded_by = coalesce(refunded_by, :operator) where id = :id and status = 'PAID'")
        .setParameter("now", Instant.now()).setParameter("operator", operator).setParameter("id", purchaseId).executeUpdate();
    if (changed == 0) throw new ResourceNotFoundException("Purchase not found");
    return java.util.Map.of("purchaseId", purchaseId, "status", "REFUNDED");
  }
  public static final class PurchaseFailure extends RuntimeException {
    final int status; final String code; final String offerId;
    PurchaseFailure(int status, String code, String offerId) { this.status = status; this.code = code; this.offerId = offerId; }
  }
}
