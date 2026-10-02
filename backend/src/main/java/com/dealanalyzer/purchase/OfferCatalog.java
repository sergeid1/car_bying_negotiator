package com.dealanalyzer.purchase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** A missing approval disables commercial behavior; malformed approved configuration fails closed. */
@Component
public final class OfferCatalog {
  private final ObjectNode available;
  public OfferCatalog(ObjectMapper json, CheckoutProvider checkout,
      @Value("${purchase.offer-config:classpath:purchase/offer.v1.json}") Resource resource) throws java.io.IOException {
    JsonNode config;
    try (var input = resource.getInputStream()) { config = json.readTree(input); }
    available = validate(json, config, checkout.name());
  }
  static ObjectNode validate(ObjectMapper json, JsonNode config, String checkout) {
    if (!"offer-config.v1".equals(config.path("schemaVersion").asText()))
      throw new IllegalArgumentException("Unsupported offer configuration version");
    JsonNode offer = config.path("offer");
    if (!"ONE_TIME_PER_CASE".equals(offer.path("kind").asText()))
      throw new IllegalArgumentException("Only ONE_TIME_PER_CASE offers are supported");
    JsonNode approval = config.path("approval");
    if (approval.isMissingNode() || approval.isNull()) return null;
    for (String field : List.of("experimentKey", "currency", "approver", "approvedAt", "rationale"))
      if (!approval.path(field).isTextual() || approval.path(field).asText().isBlank()) return null;
    if (!approval.path("amountMinor").isIntegralNumber() || !approval.path("amountMinor").canConvertToLong()
        || approval.path("amountMinor").asLong() < 0
        || !approval.path("currency").asText().matches("[A-Z]{3}")
        || !approval.path("notCopiedFromCompetitorAdvertisedPrice").asBoolean(false)) return null;
    String rationale = approval.path("rationale").asText().toLowerCase(java.util.Locale.ROOT);
    if (!rationale.contains("not copied") || !rationale.contains("competitor") || !rationale.contains("advertised price")) return null;
    try { if (Instant.parse(approval.path("approvedAt").asText()).isAfter(Instant.now())) return null; }
    catch (java.time.format.DateTimeParseException invalid) { return null; }
    for (String field : List.of("offerId", "paymentPoint"))
      if (!offer.path(field).isTextual() || offer.path(field).asText().isBlank())
        throw new IllegalArgumentException("Missing offer " + field);
    if (!"BEFORE_REPORT".equals(offer.path("paymentPoint").asText()))
      throw new IllegalArgumentException("Unsupported payment point");
    for (String field : List.of("free", "paid", "excludedPurchaseTypes", "scopeLimits"))
      if (!offer.path(field).isArray() || offer.path(field).isEmpty())
        throw new IllegalArgumentException("Missing offer terms: " + field);
    if (!offer.path("refund").path("windowDays").isIntegralNumber()
        || offer.path("refund").path("windowDays").asInt(-1) < 0
        || offer.path("refund").path("terms").asText().isBlank()
        || !offer.has("jurisdiction")) throw new IllegalArgumentException("Missing offer scope/refund terms");
    ObjectNode result = ((ObjectNode) offer).deepCopy();
    result.put("status", "AVAILABLE"); result.put("checkout", checkout);
    result.putObject("price").set("amountMinor", approval.get("amountMinor"));
    ((ObjectNode) result.get("price")).set("currency", approval.get("currency"));
    return result;
  }
  public ObjectNode current() {
    return available == null ? new ObjectMapper().createObjectNode().put("status", "UNAVAILABLE") : available.deepCopy();
  }
  public String activeId() { return available == null ? null : available.path("offerId").asText(); }
}
