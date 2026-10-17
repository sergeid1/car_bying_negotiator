package com.dealanalyzer.economics.persistence;

import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.verification.persistence.DealerOfferEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.InvalidCashEconomicsException;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CashEconomicsPersistence {
  private final CashEconomicsResultRepository results;
  private final JurisdictionPolicyRepository policies;
  private final EntityManager entityManager;
  private final ObjectMapper json;

  CashEconomicsPersistence(
      CashEconomicsResultRepository results, JurisdictionPolicyRepository policies,
      EntityManager entityManager, ObjectMapper json) {
    this.results = results;
    this.policies = policies;
    this.entityManager = entityManager;
    this.json = json;
  }

  /** Whether any effective policy pack exists for a jurisdiction, which is what makes it usable. */
  @Transactional(readOnly = true)
  public boolean hasPolicy(String jurisdiction) {
    return policies.findTopByJurisdictionAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
        jurisdiction, Instant.now()).isPresent();
  }

  /** Every jurisdiction with a policy pack, so intake can say which are supported. */
  @Transactional(readOnly = true)
  public java.util.List<String> policyJurisdictions() {
    return policies.findAll().stream()
        .filter(policy -> !policy.effectiveAt.isAfter(Instant.now()))
        .map(policy -> policy.jurisdiction)
        .distinct()
        .sorted()
        .toList();
  }

  public Policy requirePolicy(String jurisdiction) {
    JurisdictionPolicyEntity value = policies.findTopByJurisdictionAndEffectiveAtLessThanEqualOrderByEffectiveAtDesc(
        jurisdiction, Instant.now())
        .orElseThrow(() -> new InvalidCashEconomicsException(
            "No cash economics policy is configured for " + jurisdiction));
    return new Policy(value.version, value.jurisdiction, Currency.getInstance(value.currency),
        value.taxRate, Arrays.stream(value.taxableLineTypes.split(","))
            .map(LineType::valueOf).collect(Collectors.toUnmodifiableSet()),
        value.tradeTaxCreditEnabled, value.currencyScale, RoundingMode.valueOf(value.roundingMode),
        value.reconciliationTolerance, components(value.policyJson), rules(value.policyJson));
  }

  /** Optional pack fields from ticket 137; a malformed value fails loading rather than being ignored. */
  private com.dealanalyzer.economics.CashDealEconomics.TaxRules rules(String policyJson) {
    try {
      JsonNode node = json.readTree(policyJson);
      JsonNode charges = node.path("nonTaxableCharges");
      var terms = new java.util.EnumMap<LineType, java.util.List<String>>(LineType.class);
      if (!charges.isMissingNode()) {
        if (!charges.isObject()) throw new IllegalStateException("nonTaxableCharges must be an object");
        for (var entry : charges.properties()) {
          if (!entry.getValue().isArray()) throw new IllegalStateException("nonTaxableCharges values must be arrays");
          var list = new java.util.ArrayList<String>();
          for (JsonNode term : entry.getValue()) {
            if (!term.isTextual()) throw new IllegalStateException("nonTaxableCharges terms must be text");
            list.add(term.asText());
          }
          terms.put(LineType.valueOf(entry.getKey()), list);
        }
      }
      JsonNode titling = node.path("paidAtTitling");
      if (!titling.isMissingNode() && !titling.isBoolean()) throw new IllegalStateException("paidAtTitling must be a boolean");
      String taxName = text(node, "taxName"), note = text(node, "buyerNote");
      if (titling.asBoolean(false) && (taxName == null || note == null))
        throw new IllegalStateException("A tax paid at titling needs a taxName and a buyerNote");
      return new com.dealanalyzer.economics.CashDealEconomics.TaxRules(taxName, terms, titling.asBoolean(false), note);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new IllegalStateException("Unable to read jurisdiction tax rules", exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isTextual() || value.asText().isBlank()) throw new IllegalStateException(field + " must be text");
    return value.asText();
  }

  private java.util.List<com.dealanalyzer.economics.CashDealEconomics.TaxComponent> components(String policyJson) {
    try {
      var node = json.readTree(policyJson).path("taxComponents");
      if (node.isMissingNode()) return java.util.List.of();
      if (!node.isArray() || node.isEmpty()) throw new IllegalStateException("Tax components must be a nonempty array");
      return json.readerForListOf(com.dealanalyzer.economics.CashDealEconomics.TaxComponent.class).readValue(node);
    } catch (java.io.IOException exception) {
      throw new IllegalStateException("Unable to read jurisdiction tax components", exception);
    }
  }

  @Transactional(readOnly = true)
  public Optional<CashEconomicsView> find(
      String dealerOfferId, String policyVersion, String eligibilityFingerprint) {
    return results.findByDealerOfferIdAndPolicyVersionAndEligibilityFingerprint(
        dealerOfferId, policyVersion, eligibilityFingerprint).map(this::view);
  }

  @Transactional(readOnly = true)
  public Optional<CashEconomicsView> latest(String caseId) {
    return results.findTopByCaseIdOrderByCreatedAtDescIdDesc(caseId).map(this::view);
  }

  @Transactional(readOnly = true)
  public Optional<CashEconomicsView> forOffer(String offerId) {
    return results.findTopByDealerOfferIdOrderByCreatedAtDescIdDesc(offerId).map(this::view);
  }

  @Transactional
  public CashEconomicsView save(
      String caseId, String dealerOfferId, String policyVersion, String eligibilityFingerprint,
      Result result, Instant now) {
    DealerOfferEntity offer = entityManager.getReference(DealerOfferEntity.class, dealerOfferId);
    JurisdictionPolicyEntity policy = entityManager.getReference(
        JurisdictionPolicyEntity.class, policyVersion);
    return view(results.saveAndFlush(new CashEconomicsResultEntity(
        UUID.randomUUID().toString(), caseId, offer, policy, eligibilityFingerprint,
        write(result), now)));
  }

  private CashEconomicsView view(CashEconomicsResultEntity value) {
    try {
      return new CashEconomicsView(value.id, value.caseId, value.schemaVersion,
          value.dealerOffer.id(), value.policy.version(), json.readTree(value.resultJson), value.createdAt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read cash economics result", exception);
    }
  }

  private String write(Result result) {
    try {
      return json.writeValueAsString(result);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to persist cash economics result", exception);
    }
  }

  public record CashEconomicsView(
      String id, String caseId, String schemaVersion, String dealerOfferId,
      String policyVersion, JsonNode result, Instant createdAt) {}
}
