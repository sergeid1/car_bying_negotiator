package com.dealanalyzer.purchase;

import com.dealanalyzer.operations.OperatorAccounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1")
@Validated
public class PurchaseController {
  private final OfferCatalog offers;
  private final PurchaseService purchases;
  public PurchaseController(OfferCatalog offers, PurchaseService purchases) { this.offers = offers; this.purchases = purchases; }
  @GetMapping("/offer")
  public com.fasterxml.jackson.databind.JsonNode offer() { return offers.current(); }
  @GetMapping("/cases/{caseId}/entitlement")
  public PurchaseService.Entitlement entitlement(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return purchases.entitlement(caseId, token);
  }
  public record Request(@NotBlank @Size(max = 200) String offerId) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void unexpected(String field, Object value) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
  }
  @PostMapping("/cases/{caseId}/purchases")
  public ResponseEntity<PurchaseService.Purchase> purchase(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @Valid @RequestBody Request request) {
    var result = purchases.create(caseId, token, key, request.offerId());
    return ResponseEntity.status(result.replay() ? 200 : 201).body(result.purchase());
  }
  @PostMapping("/operator/purchases/{purchaseId}/refunds")
  public Map<String, String> refund(@PathVariable String purchaseId,
      @RequestAttribute("operator") OperatorAccounts.Principal operator) {
    if (operator.role() != OperatorAccounts.Role.SUPPORT) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return purchases.refund(purchaseId, operator.id());
  }
}
