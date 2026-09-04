package com.dealanalyzer.economics;

import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest.PurchaseMethod;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.economics.CashDealEconomics.Eligibility;
import com.dealanalyzer.economics.CashDealEconomics.LineItem;
import com.dealanalyzer.economics.CashDealEconomics.LineType;
import com.dealanalyzer.economics.CashDealEconomics.Money;
import com.dealanalyzer.economics.CashDealEconomics.Offer;
import com.dealanalyzer.economics.CashDealEconomics.Policy;
import com.dealanalyzer.economics.CashDealEconomics.TaxTreatment;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence;
import com.dealanalyzer.economics.persistence.CashEconomicsPersistence.CashEconomicsView;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class CashEconomicsService {
  private final EphemeralBuyingCaseService cases;
  private final DealerOfferPersistence offers;
  private final CashEconomicsPersistence persistence;
  private final CashDealEconomicsCalculator calculator = new CashDealEconomicsCalculator();

  CashEconomicsService(
      EphemeralBuyingCaseService cases,
      DealerOfferPersistence offers,
      CashEconomicsPersistence persistence) {
    this.cases = cases;
    this.offers = offers;
    this.persistence = persistence;
  }

  public CashEconomicsView calculate(
      String caseId, String accessToken, CalculateCashEconomicsRequest request) {
    var buyingCase = cases.reopen(caseId, accessToken);
    if (buyingCase.purchaseMethod() != PurchaseMethod.CASH) {
      throw new InvalidCashEconomicsException("Cash economics require a CASH Buying Case");
    }
    if (!"READY_TO_ANALYZE".equals(buyingCase.status())) {
      throw new InvalidCashEconomicsException(
          "Cash economics require a current verified Dealer Offer");
    }
    DealerOfferView dealerOffer = offers.latestOffer(caseId)
        .orElseThrow(() -> new ResourceNotFoundException("Dealer Offer not found"));
    Policy policy = persistence.requirePolicy(buyingCase.buyerJurisdiction());
    CalculateCashEconomicsRequest effectiveRequest = request == null
        ? CalculateCashEconomicsRequest.empty() : request;
    String eligibilityFingerprint = fingerprint(effectiveRequest.verifiedRebateEvidenceIds());
    CashEconomicsView existing = persistence.find(
        dealerOffer.id(), policy.version(), eligibilityFingerprint).orElse(null);
    if (existing != null) return existing;
    var result = calculator.calculate(toDomain(
        dealerOffer, policy, effectiveRequest.verifiedRebateEvidenceIds()), policy);
    try {
      return persistence.save(caseId, dealerOffer.id(), policy.version(), eligibilityFingerprint,
          result, Instant.now());
    } catch (DataIntegrityViolationException duplicate) {
      return persistence.find(dealerOffer.id(), policy.version(), eligibilityFingerprint)
          .orElseThrow(() -> duplicate);
    }
  }

  public CashEconomicsView latest(String caseId, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.latest(caseId)
        .orElseThrow(() -> new ResourceNotFoundException("Cash economics result not found"));
  }

  private Offer toDomain(
      DealerOfferView offer, Policy policy, Set<String> verifiedRebateEvidenceIds) {
    Currency currency = policy.currency();
    List<LineItem> lines = new ArrayList<>();
    EnumSet<LineType> represented = EnumSet.noneOf(LineType.class);
    for (JsonNode line : offer.terms().path("lineItems")) {
      LineType type = lineType(line.path("category").asText());
      represented.add(type);
      String lineCurrency = line.path("currency").asText(currency.getCurrencyCode());
      BigDecimal amount = BigDecimal.valueOf(
          line.path("amountMinor").asLong(), Currency.getInstance(lineCurrency).getDefaultFractionDigits());
      if (isReductionOrBuyerValue(type)) amount = amount.abs();
      lines.add(new LineItem(
          line.path("sourceLineItemId").asText(), line.path("originalLabel").asText(),
          new Money(amount, Currency.getInstance(lineCurrency)), type,
          taxTreatment(line.path("taxClaim").asText("UNKNOWN")),
          eligibility(type, line.path("sourceLineItemId").asText(),
              line.path("optionalityClaim").asText("UNKNOWN"), verifiedRebateEvidenceIds)));
    }
    addCandidateFallbacks(offer.terms().path("candidates"), lines, represented, currency);
    Set<String> rebateEvidence = lines.stream().filter(line -> line.type() == LineType.REBATE)
        .map(LineItem::evidence).collect(java.util.stream.Collectors.toSet());
    if (!rebateEvidence.containsAll(verifiedRebateEvidenceIds)) {
      throw new InvalidCashEconomicsException(
          "Verified rebate evidence must identify a rebate in the Dealer Offer");
    }
    lines.replaceAll(line -> line.type() == LineType.REBATE
            && verifiedRebateEvidenceIds.contains(line.evidence())
        ? new LineItem(line.evidence(), line.label(), line.amount(), line.type(),
            line.taxTreatment(), Eligibility.VERIFIED)
        : line);
    JsonNode quoted = candidate(offer.terms().path("candidates"), "OUT_THE_DOOR_TOTAL");
    Money quotedTotal = quoted == null || quoted.path("value").isNull()
        ? null : fromMinor(Long.parseLong(quoted.path("value").asText()), currency);
    return new Offer(offer.id(), offer.version(), currency, lines, quotedTotal,
        quoted == null ? null : quoted.path("sourceCandidateId").asText());
  }

  private void addCandidateFallbacks(
      JsonNode candidates, List<LineItem> lines, EnumSet<LineType> represented, Currency currency) {
    addFallback(candidates, lines, represented, currency, "SELLING_PRICE", LineType.SELLING_PRICE,
        TaxTreatment.TAXABLE);
    addFallback(candidates, lines, represented, currency, "DISCOUNT_TOTAL",
        LineType.DEALER_DISCOUNT, TaxTreatment.TAXABLE);
    addFallback(candidates, lines, represented, currency, "REBATE_TOTAL",
        LineType.REBATE, TaxTreatment.TAXABLE);
    addFallback(candidates, lines, represented, currency, "DEALER_CHARGES_TOTAL", LineType.DEALER_FEE,
        TaxTreatment.TAXABLE);
    addFallback(candidates, lines, represented, currency, "ADD_ON_TOTAL", LineType.ADD_ON,
        TaxTreatment.UNKNOWN);
    addFallback(candidates, lines, represented, currency, "GOVERNMENT_CHARGES_TOTAL",
        LineType.GOVERNMENT_CHARGE, TaxTreatment.NOT_TAXABLE);
    addFallback(candidates, lines, represented, currency, "TRADE_ALLOWANCE", LineType.TRADE_ALLOWANCE,
        TaxTreatment.TAX_CREDIT);
    addFallback(candidates, lines, represented, currency, "TRADE_PAYOFF", LineType.TRADE_PAYOFF,
        TaxTreatment.NOT_TAXABLE);
    addFallback(candidates, lines, represented, currency, "CASH_DOWN", LineType.DOWN_PAYMENT,
        TaxTreatment.NOT_TAXABLE);
  }

  private void addFallback(
      JsonNode candidates, List<LineItem> lines, EnumSet<LineType> represented, Currency currency,
      String field, LineType type, TaxTreatment taxTreatment) {
    if (represented.contains(type)) return;
    JsonNode value = candidate(candidates, field);
    if (value == null || value.path("value").isNull() || value.path("value").asText().isBlank()) return;
    lines.add(new LineItem(value.path("sourceCandidateId").asText(), field,
        fromMinor(Long.parseLong(value.path("value").asText()), currency), type, taxTreatment,
        type == LineType.REBATE ? Eligibility.UNVERIFIED : Eligibility.NOT_APPLICABLE));
    represented.add(type);
  }

  private static JsonNode candidate(JsonNode candidates, String field) {
    for (JsonNode candidate : candidates) {
      if (field.equals(candidate.path("field").asText())) return candidate;
    }
    return null;
  }

  private static Money fromMinor(long minor, Currency currency) {
    return new Money(BigDecimal.valueOf(minor, currency.getDefaultFractionDigits()), currency);
  }

  private static LineType lineType(String category) {
    return switch (category.toUpperCase(Locale.ROOT)) {
      case "SELLING_PRICE" -> LineType.SELLING_PRICE;
      case "DISCOUNT" -> LineType.DEALER_DISCOUNT;
      case "REBATE" -> LineType.REBATE;
      case "DEALER_FEE" -> LineType.DEALER_FEE;
      case "ADD_ON" -> LineType.ADD_ON;
      case "GOVERNMENT_CHARGE" -> LineType.GOVERNMENT_CHARGE;
      case "TRADE" -> LineType.TRADE_ALLOWANCE;
      case "CREDIT" -> LineType.CREDIT;
      case "TAX" -> LineType.QUOTED_TAX;
      default -> LineType.UNKNOWN;
    };
  }

  private static boolean isReductionOrBuyerValue(LineType type) {
    return type == LineType.DEALER_DISCOUNT || type == LineType.REBATE
        || type == LineType.TRADE_ALLOWANCE || type == LineType.DOWN_PAYMENT
        || type == LineType.OUTSIDE_FINANCING || type == LineType.OTHER_BUYER_FUNDS
        || type == LineType.CREDIT;
  }

  private static TaxTreatment taxTreatment(String claim) {
    return switch (claim.toUpperCase(Locale.ROOT)) {
      case "TAXABLE" -> TaxTreatment.TAXABLE;
      case "NONTAXABLE", "NON_TAXABLE", "NOT_TAXABLE" -> TaxTreatment.NOT_TAXABLE;
      case "TAX_CREDIT" -> TaxTreatment.TAX_CREDIT;
      default -> TaxTreatment.UNKNOWN;
    };
  }

  private static Eligibility eligibility(
      LineType type, String evidence, String optionality, Set<String> verifiedRebateEvidenceIds) {
    if (type != LineType.REBATE) return Eligibility.NOT_APPLICABLE;
    return verifiedRebateEvidenceIds.contains(evidence)
        || "ELIGIBILITY_VERIFIED".equalsIgnoreCase(optionality)
        ? Eligibility.VERIFIED : Eligibility.UNVERIFIED;
  }

  private static String fingerprint(Set<String> evidenceIds) {
    try {
      String normalized = evidenceIds.stream().sorted()
          .collect(java.util.stream.Collectors.joining("\n"));
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
