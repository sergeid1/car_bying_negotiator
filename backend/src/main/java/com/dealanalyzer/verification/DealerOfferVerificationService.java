package com.dealanalyzer.verification;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.CandidateView;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.LineItemView;
import com.dealanalyzer.vehicle.persistence.VehicleEvidencePersistence;
import com.dealanalyzer.vehicle.persistence.VehicleIdentityConflictPersistence;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.CorrectionView;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.VerifiedTarget;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DealerOfferVerificationService {
  private static final Set<String> FINANCIAL_FIELDS = Set.of(
      "SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL", "SUBTOTAL", "TAX_TOTAL",
      "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL",
      "TRADE_ALLOWANCE", "TRADE_PAYOFF", "CASH_DOWN", "OUT_THE_DOOR_TOTAL",
      "AMOUNT_FINANCED", "APR", "TERM_MONTHS", "MONTHLY_PAYMENT",
      "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS");

  private final EphemeralBuyingCaseService cases;
  private final DocumentExtractionPersistence extractions;
  private final DealerOfferPersistence persistence;
  private final VehicleEvidencePersistence vehicles;
  private final VehicleIdentityConflictPersistence conflicts;
  private final double confidenceThreshold;
  private final ListingEvidencePersistence listings;

  DealerOfferVerificationService(
      EphemeralBuyingCaseService cases,
      DocumentExtractionPersistence extractions,
      DealerOfferPersistence persistence,
      VehicleEvidencePersistence vehicles,
      VehicleIdentityConflictPersistence conflicts,
      ListingEvidencePersistence listings,
      @Value("${extraction.material-confidence-threshold:0.85}") double confidenceThreshold) {
    this.cases = cases;
    this.extractions = extractions;
    this.persistence = persistence;
    this.vehicles = vehicles;
    this.conflicts = conflicts;
    this.confidenceThreshold = confidenceThreshold;
    this.listings = listings;
  }

  public CorrectionView correct(
      String caseId, String documentId, String extractionId, String accessToken,
      String idempotencyKey, CorrectDealTermRequest request) {
    cases.reopen(caseId, accessToken);
    String fingerprint = persistence.fingerprint(request);
    CorrectionView existing = persistence.findCorrection(caseId, idempotencyKey).orElse(null);
    if (existing != null) {
      persistence.requireMatchingFingerprint(existing.requestFingerprint(), fingerprint);
      return existing;
    }
    ExtractionView extraction = extractions.require(caseId, documentId, extractionId);
    validateCorrection(extraction, request);
    try {
      return persistence.appendCorrection(
          caseId, extractionId, request, idempotencyKey, fingerprint, Instant.now());
    } catch (DataIntegrityViolationException duplicate) {
      CorrectionView raced = persistence.findCorrection(caseId, idempotencyKey)
          .orElseThrow(() -> duplicate);
      persistence.requireMatchingFingerprint(raced.requestFingerprint(), fingerprint);
      return raced;
    }
  }

  public DealerOfferView verify(
      String caseId, String accessToken, String idempotencyKey,
      VerifyDealerOfferRequest request) {
    cases.reopen(caseId, accessToken);
    String fingerprint = persistence.fingerprint(request);
    DealerOfferView existing = persistence.findOffer(caseId, idempotencyKey).orElse(null);
    if (existing != null) {
      persistence.requireMatchingFingerprint(existing.requestFingerprint(), fingerprint);
      return existing;
    }
    ExtractionView extraction = extractions.require(caseId, request.extractionId());
    requireResolvedVehicle(caseId, extraction);
    List<VerifiedTarget> verified = new ArrayList<>();
    List<Map<String, Object>> candidateTerms = new ArrayList<>();
    for (CandidateView candidate : extraction.candidates()) {
      CorrectionView correction = candidate.currentCorrection();
      String value = correction == null ? candidate.normalizedValue() : correction.normalizedValue();
      String unit = correction == null ? candidate.normalizedUnit() : correction.normalizedUnit();
      boolean required = FINANCIAL_FIELDS.contains(candidate.field())
          || candidate.material() && candidate.confidence() < confidenceThreshold;
      if (required && correction == null
          && !request.confirmedCandidateIds().contains(candidate.id())) {
        throw invalid("Confirm every financially material and low-confidence value.");
      }
      if (required && (value == null || value.isBlank())) {
        throw invalid("Correct missing material values before verification.");
      }
      if (value != null
          && (request.confirmedCandidateIds().contains(candidate.id()) || correction != null)) {
        verified.add(new VerifiedTarget("CANDIDATE", candidate.id(), value));
      }
      Map<String, Object> term = new java.util.LinkedHashMap<>();
      term.put("id", candidate.id());
      term.put("field", candidate.field());
      term.put("value", value);
      term.put("unit", unit);
      term.put("sourceCandidateId", candidate.id());
      term.put("originalWording", candidate.originalWording());
      term.put("confidence", candidate.confidence());
      term.put("evidence", candidate.evidence());
      term.put("buyerVerified", correction != null || request.confirmedCandidateIds().contains(candidate.id()));
      candidateTerms.add(term);
    }
    List<Map<String, Object>> lineTerms = new ArrayList<>();
    for (LineItemView line : extraction.lineItems()) {
      CorrectionView correction = line.currentCorrection();
      if (correction == null && !request.confirmedLineItemIds().contains(line.id())) {
        throw invalid("Confirm every financially material line item.");
      }
      String category = correction == null ? line.category() : correction.category();
      long amount = correction == null ? line.amountMinor() : correction.amountMinor();
      verified.add(new VerifiedTarget("LINE_ITEM", line.id(), Long.toString(amount)));
      Map<String, Object> lineTerm = new java.util.LinkedHashMap<>(Map.of(
          "id", line.id(), "originalLabel", line.originalLabel(), "category", category,
          "amountMinor", amount, "currency", line.currency(), "sourceLineItemId", line.id(),
          "taxClaim", line.taxClaim(), "optionalityClaim", line.optionalityClaim(),
          "clarificationAction", category.equals("UNKNOWN") ? "ASK_DEALER_TO_CLARIFY" : "NONE"));
      lineTerm.put("confidence", line.confidence());
      lineTerm.put("evidence", line.evidence());
      lineTerm.put("buyerVerified", true);
      lineTerms.add(lineTerm);
    }
    Map<String, Object> terms = Map.of(
        "candidates", candidateTerms, "lineItems", lineTerms,
        "sourceExtractionId", extraction.id(), "sourceExtractionVersion", extraction.version());
    DealerOfferView offer;
    try {
      offer = persistence.appendOffer(
          caseId, extraction.id(), idempotencyKey, fingerprint, terms, verified, Instant.now());
    } catch (DataIntegrityViolationException duplicate) {
      DealerOfferView raced = persistence.findOffer(caseId, idempotencyKey)
          .orElseThrow(() -> duplicate);
      persistence.requireMatchingFingerprint(raced.requestFingerprint(), fingerprint);
      return raced;
    }
    return offer;
  }

  public DealerOfferView latest(String caseId, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.latestOffer(caseId)
        .orElseThrow(() -> new com.dealanalyzer.buyingcase.ResourceNotFoundException(
            "Dealer Offer not found"));
  }

  public DealerOfferView version(String caseId, int version, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.requireOffer(caseId, version);
  }

  private void requireResolvedVehicle(String caseId, ExtractionView extraction) {
    if (conflicts.findByCaseId(caseId).stream().anyMatch(value -> value.status().equals("UNRESOLVED"))) {
      throw invalid("Resolve the Vehicle identity conflict before verifying the Dealer Offer.");
    }
    var vehicle = vehicles.findByCaseId(caseId)
        .orElseThrow(() -> invalid("Confirm the Vehicle before verifying the Dealer Offer."));
    CandidateView documentVin = extraction.candidates().stream()
        .filter(value -> value.field().equals("VEHICLE_VIN")).findFirst().orElse(null);
    if (vehicle.vin() != null && documentVin != null) {
      String value = documentVin.currentCorrection() == null
          ? documentVin.normalizedValue() : documentVin.currentCorrection().normalizedValue();
      if (value != null && !vehicle.vin().equals(value)) {
        throw invalid("Resolve the document and Buyer-confirmed Vehicle VIN conflict before verification.");
      }
    }
    var listing = listings.findLatest(caseId).orElse(null);
    if (listing != null) {
      requireSame("VIN", vehicle.vin(), listing.vin());
      requireSame("year", vehicle.year(), listing.year());
      requireSame("make", vehicle.make(), listing.make());
      requireSame("model", vehicle.model(), listing.model());
      requireSame("trim", vehicle.trim(), listing.trim());
    }
    requireDocumentFact(extraction, "VEHICLE_YEAR", vehicle.year());
    requireDocumentFact(extraction, "VEHICLE_MAKE", vehicle.make());
    requireDocumentFact(extraction, "VEHICLE_MODEL", vehicle.model());
    requireDocumentFact(extraction, "VEHICLE_TRIM", vehicle.trim());
  }

  private void requireDocumentFact(ExtractionView extraction, String field, Object confirmed) {
    if (confirmed == null) {
      return;
    }
    extraction.candidates().stream().filter(value -> value.field().equals(field)).findFirst()
        .ifPresent(candidate -> {
          String document = candidate.currentCorrection() == null
              ? candidate.normalizedValue() : candidate.currentCorrection().normalizedValue();
          requireSame(field, confirmed, document);
        });
  }

  private void requireSame(String field, Object confirmed, Object other) {
    if (confirmed != null && other != null
        && !confirmed.toString().equalsIgnoreCase(other.toString())) {
      throw invalid("Resolve the " + field + " Vehicle identity conflict before verification.");
    }
  }

  private void validateCorrection(ExtractionView extraction, CorrectDealTermRequest request) {
    if (request == null || request.targetType() == null || request.targetId() == null) {
      throw invalid("A correction target is required.");
    }
    if (request.targetType() == CorrectDealTermRequest.TargetType.CANDIDATE) {
      CandidateView target = extraction.candidates().stream()
          .filter(value -> value.id().equals(request.targetId())).findFirst()
          .orElseThrow(() -> invalid("Candidate Deal Term was not found."));
      if (request.normalizedValue() == null || request.normalizedValue().isBlank()
          || request.normalizedUnit() == null || request.normalizedUnit().isBlank()
          || request.category() != null || request.amountMinor() != null) {
        throw invalid("Candidate corrections require a normalized value and unit only.");
      }
      if (!target.normalizedUnit().equals(request.normalizedUnit())) {
        throw invalid("A correction cannot change the candidate value unit.");
      }
      validateCandidateValue(request.normalizedValue(), request.normalizedUnit());
    } else {
      extraction.lineItems().stream().filter(value -> value.id().equals(request.targetId()))
          .findFirst().orElseThrow(() -> invalid("Quote Line Item was not found."));
      if (request.category() == null || !Set.of(
              "SELLING_PRICE", "DISCOUNT", "REBATE", "DEALER_FEE", "ADD_ON", "TAX",
              "GOVERNMENT_CHARGE", "TRADE", "CREDIT", "UNKNOWN").contains(request.category())
          || request.amountMinor() == null || request.normalizedValue() != null
          || request.normalizedUnit() != null) {
        throw invalid("Line-item corrections require a category and amount only.");
      }
      if (request.amountMinor() < -1_000_000_000L || request.amountMinor() > 1_000_000_000L) {
        throw invalid("The corrected line-item amount is outside supported bounds.");
      }
    }
  }

  private void validateCandidateValue(String value, String unit) {
    try {
      if (unit.equals("USD_MINOR")) {
        long amount = Long.parseLong(value);
        if (amount < -1_000_000_000L || amount > 1_000_000_000L) {
          throw new NumberFormatException();
        }
      } else if (unit.equals("PERCENT")) {
        java.math.BigDecimal amount = new java.math.BigDecimal(value);
        if (amount.signum() < 0 || amount.compareTo(new java.math.BigDecimal("100")) > 0) {
          throw new NumberFormatException();
        }
      } else if (unit.equals("MONTH")) {
        int months = Integer.parseInt(value);
        if (months < 1 || months > 120) throw new NumberFormatException();
      } else if (unit.equals("DATE")) {
        java.time.LocalDate.parse(value);
      } else if (unit.equals("VIN")) {
        com.dealanalyzer.vehicle.Vin.validated(value);
      } else if (value.length() > 500) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException invalid) {
      throw invalid("The corrected candidate value is invalid for " + unit + ".");
    }
  }

  private InvalidDealTermsVerificationException invalid(String message) {
    return new InvalidDealTermsVerificationException(message);
  }
}
