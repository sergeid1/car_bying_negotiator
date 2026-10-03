package com.dealanalyzer.extraction;

import com.dealanalyzer.extraction.DealTermsExtractionProvider.Candidate;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.LineItem;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.ProviderExtraction;
import com.dealanalyzer.finance.FinancingCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies deterministic validation after an untrusted provider returns constrained data. */
public final class DealTermsValidator {
  static final Set<String> FIELDS = Set.of(
      "DOCUMENT_TYPE", "DOCUMENT_NUMBER",
      "VEHICLE_VIN", "VEHICLE_YEAR", "VEHICLE_MAKE", "VEHICLE_MODEL", "VEHICLE_TRIM",
      "BUYER_NAME", "DEALER_NAME", "OFFER_DATE", "EXPIRATION_DATE",
      "SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL", "SUBTOTAL", "TAX_TOTAL",
      "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL",
      "TRADE_ALLOWANCE", "TRADE_PAYOFF", "CASH_DOWN", "OUT_THE_DOOR_TOTAL",
      "AMOUNT_FINANCED", "APR", "TERM_MONTHS", "MONTHLY_PAYMENT",
      "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS", "CONDITIONAL_LANGUAGE");
  private static final Set<String> MONEY_FIELDS = Set.of(
      "SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL", "SUBTOTAL", "TAX_TOTAL",
      "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL",
      "TRADE_ALLOWANCE", "TRADE_PAYOFF", "CASH_DOWN", "OUT_THE_DOOR_TOTAL",
      "AMOUNT_FINANCED", "MONTHLY_PAYMENT", "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS");
  private static final Set<String> DATE_FIELDS = Set.of("OFFER_DATE", "EXPIRATION_DATE");
  private static final Set<String> MATERIAL_FIELDS = Set.of(
      "VEHICLE_VIN", "SELLING_PRICE", "DISCOUNT_TOTAL", "REBATE_TOTAL", "SUBTOTAL",
      "TAX_TOTAL", "DEALER_CHARGES_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "ADD_ON_TOTAL",
      "TRADE_ALLOWANCE", "TRADE_PAYOFF", "CASH_DOWN", "OUT_THE_DOOR_TOTAL",
      "AMOUNT_FINANCED", "APR", "TERM_MONTHS", "MONTHLY_PAYMENT",
      "FINANCE_CHARGE", "TOTAL_OF_PAYMENTS", "CONDITIONAL_LANGUAGE");
  static final Set<String> LINE_CATEGORIES = Set.of(
      "SELLING_PRICE", "DISCOUNT", "REBATE", "DEALER_FEE", "ADD_ON", "TAX",
      "GOVERNMENT_CHARGE", "TRADE", "CREDIT", "UNKNOWN");
  private final double materialConfidenceThreshold;

  public DealTermsValidator(double materialConfidenceThreshold) {
    if (!Double.isFinite(materialConfidenceThreshold)
        || materialConfidenceThreshold < 0 || materialConfidenceThreshold > 1) {
      throw new IllegalArgumentException("Material confidence threshold must be between 0 and 1");
    }
    this.materialConfidenceThreshold = materialConfidenceThreshold;
  }

  public ValidatedExtraction validate(
      ProviderExtraction provider, int actualPageCount, boolean pageWarning) {
    requireVersion(provider.extractorVersion(), "extractor");
    requireVersion(provider.modelVersion(), "model");
    if (!"deal-terms.v1".equals(provider.schemaVersion())) {
      throw new IllegalArgumentException("Unsupported Deal Terms schema version");
    }
    if (provider.expectedPageCount() != null
        && (provider.expectedPageCount() < 1 || provider.expectedPageCount() > 25)) {
      throw new IllegalArgumentException("Provider expected page count is outside schema bounds");
    }
    if (provider.candidates().size() > 200 || provider.lineItems().size() > 500
        || provider.documentWarnings().size() > 100
        || provider.documentWarnings().stream().anyMatch(warning ->
            warning == null || warning.isBlank() || warning.length() > 500)) {
      throw new IllegalArgumentException("Provider extraction exceeds schema bounds");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    boolean suspectedMissing = pageWarning
        || provider.expectedPageCount() != null && provider.expectedPageCount() > actualPageCount;
    if (suspectedMissing) {
      issues.add(issue("SUSPECTED_MISSING_PAGES", null,
          "The document may be missing one or more pages."));
    }
    Set<String> seenFields = new HashSet<>();
    List<ValidatedCandidate> candidates = new ArrayList<>();
    for (Candidate candidate : provider.candidates()) {
      validateCandidateShape(candidate, actualPageCount);
      if (!seenFields.add(candidate.field())) {
        issues.add(issue("DUPLICATE_FIELD", candidate.field(),
            "The provider returned the same field more than once."));
      }
      validateValue(candidate, issues);
      boolean material = MATERIAL_FIELDS.contains(candidate.field()) || candidate.material();
      double confidence = DeterministicDealerDocumentProvider.uncertainFigure(candidate.originalWording())
          ? Math.min(candidate.confidence(), 0.5) : candidate.confidence();
      String verificationState = candidate.normalizedValue() == null
          ? "MISSING"
          : material && (confidence < materialConfidenceThreshold
              || DeterministicDealerDocumentProvider.uncertainFigure(candidate.originalWording()))
              ? "REQUIRES_REVIEW" : "UNVERIFIED";
      candidates.add(new ValidatedCandidate(
          candidate.field(), candidate.originalWording(), candidate.normalizedValue(),
          candidate.normalizedUnit(), candidate.evidence(), confidence,
          material, verificationState));
    }
    Set<String> seenLines = new HashSet<>();
    List<ValidatedLineItem> validatedLines = new ArrayList<>();
    for (LineItem line : provider.lineItems()) {
      validateLineShape(line, actualPageCount);
      String signature = line.originalLabel().strip().toLowerCase() + "\u0000"
          + line.category() + "\u0000" + line.amountMinor() + "\u0000" + line.currency();
      if (!seenLines.add(signature)) {
        issues.add(issue("DUPLICATE_LINE_ITEM", null,
            "An identical line item appears more than once."));
      }
      String category = LINE_CATEGORIES.contains(line.category()) ? line.category() : "UNKNOWN";
      double confidence = DeterministicDealerDocumentProvider.uncertainFigure(line.originalLabel())
          ? Math.min(line.confidence(), 0.5) : line.confidence();
      String verificationState = confidence < materialConfidenceThreshold
          || DeterministicDealerDocumentProvider.uncertainFigure(line.originalLabel())
          ? "REQUIRES_REVIEW" : "UNVERIFIED";
      validatedLines.add(new ValidatedLineItem(
          line.originalLabel(), category, line.amountMinor(), line.currency(), line.taxClaim(),
          line.optionalityClaim(), line.evidence(), confidence, verificationState));
    }
    crossFoot(provider, issues);
    financeCheck(provider, issues);
    paymentLedCheck(provider, issues);
    double confidence = candidates.stream().mapToDouble(ValidatedCandidate::confidence)
        .average().orElse(0.0);
    boolean review = suspectedMissing
        || provider.candidates().isEmpty()
        || !provider.documentWarnings().isEmpty()
        || candidates.stream().anyMatch(candidate ->
            candidate.material() && !candidate.verificationState().equals("UNVERIFIED"))
        || validatedLines.stream().anyMatch(line ->
            line.verificationState().equals("REQUIRES_REVIEW"))
        || !issues.isEmpty();
    return new ValidatedExtraction(
        review ? "NEEDS_REVIEW" : "CANDIDATE",
        confidence,
        suspectedMissing,
        List.copyOf(candidates),
        List.copyOf(validatedLines),
        List.copyOf(issues),
        provider.documentWarnings());
  }

  private static void validateCandidateShape(Candidate candidate, int pageCount) {
    if (candidate == null || !FIELDS.contains(candidate.field())) {
      throw new IllegalArgumentException("Provider returned an unsupported candidate field");
    }
    if (candidate.originalWording() == null || candidate.originalWording().isBlank()
        || candidate.originalWording().length() > 2_000) {
      throw new IllegalArgumentException("Candidate original wording is required and bounded");
    }
    if (candidate.normalizedValue() != null && candidate.normalizedValue().length() > 500) {
      throw new IllegalArgumentException("Candidate normalized value is too long");
    }
    if (candidate.normalizedUnit() == null || candidate.normalizedUnit().isBlank()
        || candidate.normalizedUnit().length() > 32) {
      throw new IllegalArgumentException("Candidate normalized unit is required and bounded");
    }
    if (!Double.isFinite(candidate.confidence())
        || candidate.confidence() < 0 || candidate.confidence() > 1) {
      throw new IllegalArgumentException("Candidate confidence must be between 0 and 1");
    }
    validateEvidence(candidate.evidence(), pageCount);
  }

  private static void validateLineShape(LineItem line, int pageCount) {
    if (line == null || line.originalLabel() == null || line.originalLabel().isBlank()
        || line.originalLabel().length() > 500
        || line.category() == null || !line.category().matches("[A-Z][A-Z0-9_]{1,63}")
        || line.amountMinor() < -1_000_000_000L || line.amountMinor() > 1_000_000_000L
        || !"USD".equals(line.currency())
        || !Set.of("TAXABLE", "NONTAXABLE", "UNKNOWN").contains(line.taxClaim())
        || !Set.of("REQUIRED", "OPTIONAL", "CONDITIONAL", "UNKNOWN")
            .contains(line.optionalityClaim())
        || !Double.isFinite(line.confidence()) || line.confidence() < 0 || line.confidence() > 1) {
      throw new IllegalArgumentException("Provider returned an invalid line item");
    }
    validateEvidence(line.evidence(), pageCount);
  }

  private static void validateEvidence(
      DealTermsExtractionProvider.EvidenceRegion evidence, int pageCount) {
    if (evidence == null || evidence.pageNumber() < 1 || evidence.pageNumber() > pageCount
        || !coordinate(evidence.x()) || !coordinate(evidence.y())
        || !positiveCoordinate(evidence.width()) || !positiveCoordinate(evidence.height())
        || evidence.x() + evidence.width() > 1.000001
        || evidence.y() + evidence.height() > 1.000001) {
      throw new IllegalArgumentException("Evidence region must identify a normalized document page");
    }
  }

  private static boolean coordinate(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }

  private static boolean positiveCoordinate(double value) {
    return coordinate(value) && value > 0;
  }

  private static void validateValue(Candidate candidate, List<ValidationIssue> issues) {
    if (candidate.normalizedValue() == null) {
      return;
    }
    if (MONEY_FIELDS.contains(candidate.field())) {
      if (!"USD_MINOR".equals(candidate.normalizedUnit())) {
        issues.add(issue("INVALID_UNIT", candidate.field(), "Money must use USD minor units."));
      }
      Long value = longValue(candidate, issues);
      if (value != null && (value < -1_000_000_000L || value > 1_000_000_000L
          || value < 0 && !Set.of("DISCOUNT_TOTAL", "REBATE_TOTAL", "TRADE_ALLOWANCE")
              .contains(candidate.field()))) {
        issues.add(issue("VALUE_OUT_OF_BOUNDS", candidate.field(),
            "The monetary value is outside supported bounds."));
      }
    } else if (DATE_FIELDS.contains(candidate.field())) {
      requireUnit(candidate, "DATE", issues);
      try {
        LocalDate.parse(candidate.normalizedValue());
      } catch (DateTimeParseException exception) {
        issues.add(issue("INVALID_DATE", candidate.field(), "The normalized date is invalid."));
      }
    } else if (candidate.field().equals("VEHICLE_VIN")) {
      requireUnit(candidate, "VIN", issues);
      if (!validVin(candidate.normalizedValue())) {
        issues.add(issue("INVALID_VIN", candidate.field(), "The normalized VIN is invalid."));
      }
    } else if (candidate.field().equals("APR")) {
      requireUnit(candidate, "PERCENT", issues);
      decimal(candidate, issues).filter(value -> value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0)
          .ifPresent(value -> issues.add(issue("VALUE_OUT_OF_BOUNDS", candidate.field(),
              "APR is outside supported bounds.")));
    } else if (candidate.field().equals("TERM_MONTHS")) {
      requireUnit(candidate, "MONTH", issues);
      Long value = longValue(candidate, issues);
      if (value != null && (value < 1 || value > 120)) {
        issues.add(issue("VALUE_OUT_OF_BOUNDS", candidate.field(),
            "Loan term is outside supported bounds."));
      }
    }
  }

  private static void requireUnit(
      Candidate candidate, String expected, List<ValidationIssue> issues) {
    if (!expected.equals(candidate.normalizedUnit())) {
      issues.add(issue("INVALID_UNIT", candidate.field(),
          "The normalized unit must be " + expected + "."));
    }
  }

  private static void crossFoot(ProviderExtraction provider, List<ValidationIssue> issues) {
    Map<String, Long> values = moneyValues(provider.candidates());
    if (values.containsKey("SUBTOTAL") && !provider.lineItems().isEmpty()) {
      long sum = provider.lineItems().stream().mapToLong(LineItem::amountMinor).sum();
      if (sum != values.get("SUBTOTAL")) {
        issues.add(issue("SUBTOTAL_MISMATCH", "SUBTOTAL",
            "The stated subtotal does not equal extracted line items."));
      }
    }
    if (values.containsKey("OUT_THE_DOOR_TOTAL") && values.containsKey("SUBTOTAL")) {
      long calculated = values.get("SUBTOTAL")
          + values.getOrDefault("TAX_TOTAL", 0L)
          + values.getOrDefault("GOVERNMENT_CHARGES_TOTAL", 0L);
      if (calculated != values.get("OUT_THE_DOOR_TOTAL")) {
        issues.add(issue("OUT_THE_DOOR_TOTAL_MISMATCH", "OUT_THE_DOOR_TOTAL",
            "The stated out-the-door total does not cross-foot."));
      }
    }
  }

  private static void financeCheck(ProviderExtraction provider, List<ValidationIssue> issues) {
    Map<String, Candidate> candidates = new HashMap<>();
    provider.candidates().forEach(candidate -> candidates.putIfAbsent(candidate.field(), candidate));
    Long principal = candidateLong(candidates.get("AMOUNT_FINANCED"));
    Long payment = candidateLong(candidates.get("MONTHLY_PAYMENT"));
    Long months = candidateLong(candidates.get("TERM_MONTHS"));
    BigDecimal apr = candidateDecimal(candidates.get("APR"));
    if (principal == null || payment == null || months == null || apr == null
        || principal < 0 || principal > 1_000_000_000L
        || payment < 0 || payment > 1_000_000_000L
        || months < 1 || months > 120 || apr.signum() < 0
        || apr.compareTo(new BigDecimal("100")) > 0) {
      return;
    }
    var reconciliation = new FinancingCalculator().reconcilePayment(principal, apr, months.intValue(), payment);
    if (Boolean.FALSE.equals(reconciliation.withinTolerance())) {
      issues.add(issue("FINANCING_INCONSISTENT", "MONTHLY_PAYMENT",
          "Payment differs by more than $1 from the standard monthly financing calculation using amount financed, APR, and term; ask the dealer to reconcile timing, fees and loan terms."));
    }
  }

  private static void paymentLedCheck(ProviderExtraction provider, List<ValidationIssue> issues) {
    Map<String, Candidate> fields = new HashMap<>();
    provider.candidates().forEach(candidate -> fields.putIfAbsent(candidate.field(), candidate));
    Candidate type = fields.get("DOCUMENT_TYPE");
    if (type == null || !"FOUR_SQUARE_WORKSHEET".equals(type.normalizedValue())
        || !fields.containsKey("MONTHLY_PAYMENT")) return;
    if (candidateLong(fields.get("OUT_THE_DOOR_TOTAL")) == null)
      issues.add(issue("PAYMENT_LED_MISSING_TOTAL", "OUT_THE_DOOR_TOTAL",
          "The four-square worksheet shows a monthly payment but the out-the-door total is missing. Payment-led figures hide the total; ask for an itemized out-the-door total. Do not infer it from payment."));
    if (candidateLong(fields.get("TERM_MONTHS")) == null)
      issues.add(issue("PAYMENT_LED_MISSING_TERM", "TERM_MONTHS",
          "The four-square worksheet loan term is missing. Ask for the term in months; do not infer it from payment."));
  }

  private static Map<String, Long> moneyValues(List<Candidate> candidates) {
    Map<String, Long> values = new HashMap<>();
    for (Candidate candidate : candidates) {
      Long value = candidateLong(candidate);
      if (MONEY_FIELDS.contains(candidate.field()) && value != null) {
        values.putIfAbsent(candidate.field(), value);
      }
    }
    return values;
  }

  private static Long longValue(Candidate candidate, List<ValidationIssue> issues) {
    try {
      return Long.valueOf(candidate.normalizedValue());
    } catch (NumberFormatException exception) {
      issues.add(issue("INVALID_NUMBER", candidate.field(), "The normalized number is invalid."));
      return null;
    }
  }

  private static java.util.Optional<BigDecimal> decimal(
      Candidate candidate, List<ValidationIssue> issues) {
    try {
      return java.util.Optional.of(new BigDecimal(candidate.normalizedValue()));
    } catch (NumberFormatException exception) {
      issues.add(issue("INVALID_NUMBER", candidate.field(), "The normalized number is invalid."));
      return java.util.Optional.empty();
    }
  }

  private static Long candidateLong(Candidate candidate) {
    try {
      return candidate == null || candidate.normalizedValue() == null
          ? null : Long.valueOf(candidate.normalizedValue());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static BigDecimal candidateDecimal(Candidate candidate) {
    try {
      return candidate == null || candidate.normalizedValue() == null
          ? null : new BigDecimal(candidate.normalizedValue());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static boolean validVin(String vin) {
    if (vin == null || !vin.matches("[A-HJ-NPR-Z0-9]{17}")) {
      return false;
    }
    int[] weights = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
    int sum = 0;
    String letters = "ABCDEFGHJKLMNPRSTUVWXYZ";
    int[] values = {1,2,3,4,5,6,7,8,1,2,3,4,5,7,9,2,3,4,5,6,7,8,9};
    for (int index = 0; index < vin.length(); index++) {
      char value = vin.charAt(index);
      int numeric = Character.isDigit(value) ? value - '0' : values[letters.indexOf(value)];
      sum += numeric * weights[index];
    }
    int remainder = sum % 11;
    return vin.charAt(8) == (remainder == 10 ? 'X' : Character.forDigit(remainder, 10));
  }

  private static void requireVersion(String value, String label) {
    if (value == null || value.isBlank() || value.length() > 100) {
      throw new IllegalArgumentException("Provider " + label + " version is required and bounded");
    }
  }

  private static ValidationIssue issue(String code, String field, String message) {
    return new ValidationIssue("REVIEW", code, field, message);
  }

  public record ValidatedCandidate(
      String field, String originalWording, String normalizedValue, String normalizedUnit,
      DealTermsExtractionProvider.EvidenceRegion evidence, double confidence,
      boolean material, String verificationState) {}

  public record ValidatedLineItem(
      String originalLabel, String category, long amountMinor, String currency,
      String taxClaim, String optionalityClaim,
      DealTermsExtractionProvider.EvidenceRegion evidence, double confidence,
      String verificationState) {}

  public record ValidationIssue(String severity, String code, String field, String message) {}

  public record ValidatedExtraction(
      String status,
      double overallConfidence,
      boolean suspectedMissingPages,
      List<ValidatedCandidate> candidates,
      List<ValidatedLineItem> lineItems,
      List<ValidationIssue> issues,
      List<String> documentWarnings) {
    public Set<String> issueCodes() {
      return issues.stream().map(ValidationIssue::code)
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
  }
}
