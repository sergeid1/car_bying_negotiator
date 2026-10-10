package com.dealanalyzer.extraction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic extractor for text-bearing U.S. dealer quotes, buyers' orders and four-square worksheets. The
 * default provider; {@link ExtractionProviderConfiguration} selects it.
 */
class DeterministicDealerDocumentProvider implements DealTermsExtractionProvider {
  private static final Pattern LABELED = Pattern.compile("^\\s*([^:]{2,80})\\s*[:#]\\s*(.*?)\\s*$");
  private static final Pattern MONEY = Pattern.compile("^\\$?([0-9]{1,3}(?:,[0-9]{3})*|[0-9]+)(?:\\.([0-9]{2}))?$");
  private static final Pattern PERCENT = Pattern.compile("^([0-9]{1,3}(?:\\.[0-9]+)?)\\s*%?$");
  private static final Pattern MONTHS = Pattern.compile("^([0-9]{1,3})(?:\\s*months?)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern VIN = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
  private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/uuuu");
  private static final Map<String, FieldDefinition> LABELS = labels();

  @Override
  public ProviderExtraction extract(ExtractionDocument document) {
    Map<String, Candidate> candidates = new LinkedHashMap<>();
    List<LineItem> lineItems = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    boolean supported = document.pages().stream().map(NormalizedDocumentPage::recognizedText)
        .map(text -> text.toUpperCase(Locale.ROOT))
        .anyMatch(text -> text.contains("BUYER'S ORDER") || text.contains("BUYERS ORDER")
            || text.contains("DEALER QUOTE") || text.contains("PURCHASE ORDER")
            || fourSquare(text));
    if (!supported) {
      warnings.add("The normalized pages do not match a supported text-bearing dealer layout.");
    }
    for (NormalizedDocumentPage page : document.pages()) {
      // Text normalization retains quadrant boundaries as pipes, tabs, or wide spacing.
      for (String rawLine : page.recognizedText().split("\\R|\\s*\\|\\s*|\\t+| {3,}")) {
        String line = rawLine.strip();
        if (line.isEmpty()) {
          continue;
        }
        EvidenceRegion evidence = new EvidenceRegion(page.pageNumber(), 0, 0, 1, 1);
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.equals("BUYER'S ORDER") || upper.equals("BUYERS ORDER")
            || upper.equals("DEALER QUOTE") || upper.equals("PURCHASE ORDER")
            || fourSquare(upper)) {
          candidates.putIfAbsent("DOCUMENT_TYPE", new Candidate(
              "DOCUMENT_TYPE", line, fourSquare(upper) ? "FOUR_SQUARE_WORKSHEET" : upper.replace("'", ""),
              "TEXT", evidence, 0.99, false));
          continue;
        }
        Matcher labeled = LABELED.matcher(line);
        if (labeled.matches()) {
          String normalizedLabel = normalizeLabel(labeled.group(1));
          FieldDefinition definition = LABELS.get(normalizedLabel);
          if (definition != null) {
            String rawValue = labeled.group(2);
            boolean uncertain = uncertainFigure(line);
            // A crossed-out amount is not a current offer, even when its digits are legible.
            String value = crossedOut(line) ? null : normalize(definition,
                rawValue.replaceAll("(?i)\\[(?:handwritten|handwriting)\\]", "").strip());
            double confidence = uncertain || value == null ? 0.35 : 0.99;
            candidates.putIfAbsent(definition.field(), new Candidate(
                definition.field(), line, value, definition.unit(), evidence,
                confidence, definition.material()));
            if (definition.lineCategory() != null && value != null) {
              long amount = Long.parseLong(value);
              if (definition.credit()) {
                amount = -Math.abs(amount);
              }
              lineItems.add(new LineItem(
                  labeled.group(1).strip(), definition.lineCategory(), amount, "USD",
                  "UNKNOWN", definition.optionality(), evidence, confidence));
            }
            continue;
          }
        }
        if (conditional(upper)) {
          candidates.putIfAbsent("CONDITIONAL_LANGUAGE", new Candidate(
              "CONDITIONAL_LANGUAGE", line, line, "TEXT", evidence, 0.90, true));
        }
      }
    }
    return new ProviderExtraction(
        "deterministic-dealer-layout.v1", "none", "deal-terms.v1",
        document.pages().size(), List.copyOf(candidates.values()), lineItems, warnings);
  }

  private static String normalize(FieldDefinition definition, String raw) {
    String value = raw.strip();
    if (definition.unit().equals("USD_MINOR")) {
      Matcher money = MONEY.matcher(value);
      if (!money.matches()) {
        return null;
      }
      String decimal = money.group(1).replace(",", "") + "."
          + (money.group(2) == null ? "00" : money.group(2));
      return new BigDecimal(decimal).movePointRight(2)
          .setScale(0, RoundingMode.UNNECESSARY).toPlainString();
    }
    if (definition.unit().equals("PERCENT")) {
      Matcher percent = PERCENT.matcher(value);
      return percent.matches() ? new BigDecimal(percent.group(1)).stripTrailingZeros().toPlainString() : null;
    }
    if (definition.unit().equals("MONTH")) {
      Matcher months = MONTHS.matcher(value);
      return months.matches() ? months.group(1) : null;
    }
    if (definition.unit().equals("VIN")) {
      String vin = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
      return VIN.matcher(vin).matches() ? vin : null;
    }
    if (definition.unit().equals("DATE")) {
      try {
        return LocalDate.parse(value, US_DATE).toString();
      } catch (DateTimeParseException ignored) {
        try {
          return LocalDate.parse(value).toString();
        } catch (DateTimeParseException invalid) {
          return null;
        }
      }
    }
    return value.isBlank() ? null : value;
  }

  private static boolean conditional(String line) {
    return line.contains("REQUIRES ") || line.contains("CONDITIONAL")
        || line.contains("SUBJECT TO") || line.contains("WITH DEALER FINANCING")
        || line.startsWith("IF ");
  }

  static boolean fourSquare(String text) {
    return text.matches("(?s).*\\b(?:FOUR[ -]?SQUARE|4[ -]?SQUARE)\\b.*");
  }

  static boolean crossedOut(String wording) {
    String lower = wording.toLowerCase(Locale.ROOT);
    return lower.contains("crossed out") || lower.contains("crossed-out")
        || lower.contains("struck out") || lower.contains("strikethrough") || lower.contains("~~");
  }

  static boolean uncertainFigure(String wording) {
    String lower = wording.toLowerCase(Locale.ROOT);
    return crossedOut(wording) || lower.contains("handwritten") || lower.contains("handwriting");
  }

  private static String normalizeLabel(String label) {
    return label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
  }

  private static Map<String, FieldDefinition> labels() {
    Map<String, FieldDefinition> labels = new LinkedHashMap<>();
    add(labels, "DOCUMENT_NUMBER", "TEXT", false, null, false, "UNKNOWN",
        "ORDER NUMBER", "QUOTE NUMBER", "DOCUMENT NUMBER");
    add(labels, "VEHICLE_VIN", "VIN", true, null, false, "UNKNOWN", "VIN", "VEHICLE VIN");
    add(labels, "TRADE_VIN", "VIN", true, null, false, "UNKNOWN", "TRADE VIN", "TRADE IN VIN");
    add(labels, "VEHICLE_YEAR", "YEAR", false, null, false, "UNKNOWN", "YEAR", "MODEL YEAR");
    add(labels, "VEHICLE_MAKE", "TEXT", false, null, false, "UNKNOWN", "MAKE");
    add(labels, "VEHICLE_MODEL", "TEXT", false, null, false, "UNKNOWN", "MODEL");
    add(labels, "VEHICLE_TRIM", "TEXT", false, null, false, "UNKNOWN", "TRIM");
    add(labels, "BUYER_NAME", "TEXT", false, null, false, "UNKNOWN", "BUYER", "CUSTOMER");
    add(labels, "DEALER_NAME", "TEXT", false, null, false, "UNKNOWN", "DEALER", "DEALERSHIP");
    add(labels, "OFFER_DATE", "DATE", false, null, false, "UNKNOWN", "DATE", "OFFER DATE");
    add(labels, "EXPIRATION_DATE", "DATE", false, null, false, "UNKNOWN", "EXPIRATION DATE", "EXPIRES");
    add(labels, "SELLING_PRICE", "USD_MINOR", true, "SELLING_PRICE", false, "REQUIRED",
        "SELLING PRICE", "CASH PRICE", "VEHICLE PRICE", "PRICE");
    add(labels, "DISCOUNT_TOTAL", "USD_MINOR", true, "DISCOUNT", true, "CONDITIONAL", "DISCOUNT");
    add(labels, "REBATE_TOTAL", "USD_MINOR", true, "REBATE", true, "CONDITIONAL", "REBATE");
    add(labels, "DEALER_CHARGES_TOTAL", "USD_MINOR", true, "DEALER_FEE", false, "REQUIRED",
        "DEALER FEE", "DOC FEE", "DOCUMENTARY FEE");
    add(labels, "ADD_ON_TOTAL", "USD_MINOR", true, "ADD_ON", false, "UNKNOWN", "ADD ON", "ADD ONS");
    add(labels, "SUBTOTAL", "USD_MINOR", true, null, false, "UNKNOWN", "SUBTOTAL");
    add(labels, "TAX_TOTAL", "USD_MINOR", true, null, false, "UNKNOWN", "TAX", "SALES TAX");
    add(labels, "GOVERNMENT_CHARGES_TOTAL", "USD_MINOR", true, null, false, "UNKNOWN",
        "GOVERNMENT FEES", "DMV FEES", "REGISTRATION FEES");
    add(labels, "TRADE_ALLOWANCE", "USD_MINOR", true, null, false, "UNKNOWN", "TRADE ALLOWANCE", "TRADE", "TRADE IN");
    add(labels, "TRADE_PAYOFF", "USD_MINOR", true, null, false, "UNKNOWN", "TRADE PAYOFF");
    add(labels, "CASH_DOWN", "USD_MINOR", true, null, false, "UNKNOWN", "CASH DOWN", "DOWN PAYMENT");
    add(labels, "OUT_THE_DOOR_TOTAL", "USD_MINOR", true, null, false, "UNKNOWN",
        "OUT THE DOOR TOTAL", "OTD TOTAL", "BALANCE DUE");
    add(labels, "AMOUNT_FINANCED", "USD_MINOR", true, null, false, "UNKNOWN", "AMOUNT FINANCED");
    add(labels, "APR", "PERCENT", true, null, false, "UNKNOWN", "APR", "ANNUAL PERCENTAGE RATE");
    add(labels, "TERM_MONTHS", "MONTH", true, null, false, "UNKNOWN", "TERM", "TERM MONTHS");
    add(labels, "MONTHLY_PAYMENT", "USD_MINOR", true, null, false, "UNKNOWN", "MONTHLY PAYMENT", "PAYMENT");
    add(labels, "FINANCE_CHARGE", "USD_MINOR", true, null, false, "UNKNOWN", "FINANCE CHARGE");
    add(labels, "TOTAL_OF_PAYMENTS", "USD_MINOR", true, null, false, "UNKNOWN", "TOTAL OF PAYMENTS");
    return Map.copyOf(labels);
  }

  private static void add(
      Map<String, FieldDefinition> labels, String field, String unit, boolean material,
      String lineCategory, boolean credit, String optionality, String... names) {
    FieldDefinition definition =
        new FieldDefinition(field, unit, material, lineCategory, credit, optionality);
    for (String name : names) {
      labels.put(name, definition);
    }
  }

  private record FieldDefinition(
      String field, String unit, boolean material,
      String lineCategory, boolean credit, String optionality) {}
}
