package com.dealanalyzer.listing.capture;

import com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import java.math.BigDecimal;
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
 * Reads a dealer's listing from the text of a capture: pasted text or a saved PDF of the page. A screenshot has
 * no text to read, so it comes back unreadable and the Buyer types what the ad shows. The default provider;
 * {@link ListingCaptureProviderConfiguration} selects it.
 */
class DeterministicListingCaptureProvider implements ListingCaptureProvider {
  static final String VERSION = "deterministic-listing-text.v1";
  private static final Pattern LABELED = Pattern.compile("^\\s*([A-Za-z][A-Za-z .#/-]{0,40}?)\\s*:\\s*(.+?)\\s*$");
  private static final Pattern MONEY = Pattern.compile("\\$\\s?([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.([0-9]{2}))?");
  private static final Pattern NUMBER = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)");
  private static final Pattern MILES = Pattern.compile("(?i)\\b([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,7})\\s*(?:mi|miles)\\b");
  private static final Pattern VIN = Pattern.compile("\\b([A-HJ-NPR-Z0-9]{17})\\b");
  private static final Pattern TITLE = Pattern.compile("^((?:19|20)[0-9]{2})\\s+([A-Za-z][A-Za-z-]+)\\s+([A-Za-z0-9][A-Za-z0-9-]*)(?:\\s+(.{1,40}))?$");
  /** A browser's print header, as "Save as PDF" writes it: "10/14/26, 3:12 PM". */
  private static final Pattern PRINTED = Pattern.compile("^([0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4}),?\\s+[0-9]{1,2}:[0-9]{2}");
  private static final Pattern PRICE_ONLY = Pattern.compile("^\\$\\s?[0-9][0-9,]*(?:\\.[0-9]{2})?\\s*\\*?$");
  private static final List<String> CONDITION_WORDS = List.of("requires", "require ", "required", "financing", "finance",
      "rebate", "incentive", "plus tax", "plus ", "excludes", "excluding", "does not include", "not included",
      "approved credit", "w.a.c", "conditional", "dealer fee", "doc fee", "documentary", "subject to", "includes",
      "including", "qualify", "qualified", "trade-in", "trade in", "must ", "military", "college", "loyalty",
      "conquest", "down payment", "price shown", "price does not", "in stock", "while supplies");
  private static final Map<String, String> LABELS = labels();
  // RelayTextPages draws pasted text at 27 px per line from a 65 px baseline on a 1275 x 1650 page.
  private static final double PAGE_WIDTH = 1275, PAGE_HEIGHT = 1650, LINE = 27, FIRST_TOP = 45, LEFT = 40, CHAR = 12.1;

  @Override
  public Reading read(Capture capture) {
    Map<String, Field> fields = new LinkedHashMap<>();
    List<Field> conditions = new ArrayList<>();
    List<Field> fallback = new ArrayList<>();
    for (NormalizedDocumentPage page : capture.pages()) {
      String[] lines = page.recognizedText().split(capture.plainText() ? "\n" : "\\R", -1);
      for (int index = 0; index < lines.length; index++) {
        String line = lines[index].strip();
        if (line.isEmpty()) continue;
        EvidenceRegion region = region(capture.plainText(), page.pageNumber(), index, lines[index]);
        Matcher printed = PRINTED.matcher(line);
        if (printed.find()) {
          String date = date(printed.group(1));
          if (date != null) fallback.add(new Field("CAPTURE_DATE", line, date, "DATE", region, 0.9));
        }
        Matcher labeled = LABELED.matcher(line);
        String field = labeled.matches() ? LABELS.get(label(labeled.group(1))) : null;
        if (field != null) {
          fields.putIfAbsent(field, field(field, line, labeled.group(2), region, 0.95));
        } else {
          unlabeled(line, region, fallback);
        }
        if (conditional(line) && conditions.size() < ListingCaptureValidator.MAX_CONDITIONS
            && conditions.stream().noneMatch(existing -> existing.originalWording().equals(line)))
          conditions.add(new Field("LISTING_PRICE_CONDITIONS", line, trimmed(line.replaceFirst("^[*†‡]+\\s*", "")),
              "TEXT", region, 0.9));
      }
    }
    for (Field field : fallback) fields.putIfAbsent(field.field(), field);
    List<Field> all = new ArrayList<>(fields.values());
    all.addAll(conditions);
    List<String> warnings = all.isEmpty()
        ? List.of("No listing details could be read from this capture.") : List.of();
    return new Reading(VERSION, "none", ListingCaptureValidator.SCHEMA_VERSION, all, warnings);
  }

  private static void unlabeled(String line, EvidenceRegion region, List<Field> fallback) {
    Matcher vin = VIN.matcher(line.toUpperCase(Locale.ROOT));
    while (vin.find()) {
      String value = ListingCaptureValidator.normalized("VIN", vin.group(1));
      if (value != null) { fallback.add(new Field("VEHICLE_VIN", line, value, "VIN", region, 0.8)); break; }
    }
    Matcher title = TITLE.matcher(line);
    if (title.matches() && !line.contains("$")) {
      fallback.add(new Field("VEHICLE_YEAR", line, title.group(1), "YEAR", region, 0.7));
      fallback.add(new Field("VEHICLE_MAKE", line, title.group(2), "TEXT", region, 0.7));
      fallback.add(new Field("VEHICLE_MODEL", line, title.group(3), "TEXT", region, 0.7));
      if (title.group(4) != null) fallback.add(new Field("VEHICLE_TRIM", line, title.group(4).strip(), "TEXT", region, 0.6));
    }
    Matcher miles = MILES.matcher(line);
    if (miles.find()) fallback.add(new Field("ODOMETER", line, miles.group(1).replace(",", ""), "MILES", region, 0.7));
    if (PRICE_ONLY.matcher(line).matches()) {
      String price = money(line);
      if (price != null) fallback.add(new Field("LISTING_PRICE", line, price, "USD_MINOR", region, 0.6));
    }
  }

  private static Field field(String field, String line, String raw, EvidenceRegion region, double confidence) {
    String value = switch (field) {
      case "LISTING_PRICE" -> money(raw);
      case "VEHICLE_VIN" -> ListingCaptureValidator.normalized("VIN", raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""));
      case "VEHICLE_YEAR" -> number(raw);
      case "ODOMETER" -> number(raw);
      case "CAPTURE_DATE" -> date(raw);
      default -> trimmed(raw);
    };
    String unit = switch (field) {
      case "LISTING_PRICE" -> "USD_MINOR";
      case "VEHICLE_VIN" -> "VIN";
      case "VEHICLE_YEAR" -> "YEAR";
      case "ODOMETER" -> "MILES";
      case "CAPTURE_DATE" -> "DATE";
      default -> "TEXT";
    };
    return new Field(field, line, value, unit, region, value == null ? 0.3 : confidence);
  }

  /** The rendered line for pasted text; the whole page for a PDF, whose text carries no positions. */
  private static EvidenceRegion region(boolean plainText, int page, int index, String line) {
    if (!plainText) return new EvidenceRegion(page, 0, 0, 1, 1);
    double top = Math.min(FIRST_TOP + LINE * index, PAGE_HEIGHT - LINE);
    double width = Math.min(PAGE_WIDTH - LEFT, CHAR * Math.max(1, line.length()) + 20);
    return new EvidenceRegion(page, LEFT / PAGE_WIDTH, top / PAGE_HEIGHT, width / PAGE_WIDTH, LINE / PAGE_HEIGHT);
  }

  private static boolean conditional(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return line.startsWith("*") || CONDITION_WORDS.stream().anyMatch(lower::contains);
  }

  private static String money(String raw) {
    Matcher money = MONEY.matcher(raw);
    if (!money.find()) {
      Matcher plain = Pattern.compile("^([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{4,})(?:\\.([0-9]{2}))?\\b").matcher(raw.strip());
      if (!plain.find()) return null;
      money = plain;
    }
    String decimal = money.group(1).replace(",", "") + "." + (money.group(2) == null ? "00" : money.group(2));
    return ListingCaptureValidator.normalized("USD_MINOR", new BigDecimal(decimal).movePointRight(2).toBigInteger().toString());
  }

  private static String number(String raw) {
    Matcher number = NUMBER.matcher(raw);
    return number.find() ? number.group(1).replace(",", "") : null;
  }

  private static String date(String raw) {
    String text = raw.strip();
    for (String pattern : List.of("M/d/uuuu", "M/d/uu", "uuuu-MM-dd", "MMMM d, uuuu", "MMM d, uuuu")) {
      try {
        return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.US)).toString();
      } catch (DateTimeParseException ignored) {
        // Try the next written form.
      }
    }
    return null;
  }

  private static String trimmed(String raw) {
    String text = raw.strip();
    return text.isEmpty() ? null : text.substring(0, Math.min(text.length(), ListingCaptureValidator.MAX_TEXT));
  }

  private static String label(String label) {
    return label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").strip();
  }

  private static Map<String, String> labels() {
    Map<String, String> labels = new LinkedHashMap<>();
    for (String name : List.of("PRICE", "INTERNET PRICE", "SALE PRICE", "OUR PRICE", "ASKING PRICE", "LIST PRICE",
        "LISTED PRICE", "ADVERTISED PRICE", "DEALER PRICE", "E PRICE", "EPRICE", "ONLINE PRICE", "SPECIAL PRICE",
        "YOUR PRICE", "BUY NOW PRICE", "SELLING PRICE", "VEHICLE PRICE", "CASH PRICE"))
      labels.put(name, "LISTING_PRICE");
    for (String name : List.of("VIN", "VIN NUMBER", "VIN #", "VEHICLE VIN")) labels.put(label(name), "VEHICLE_VIN");
    for (String name : List.of("YEAR", "MODEL YEAR")) labels.put(name, "VEHICLE_YEAR");
    labels.put("MAKE", "VEHICLE_MAKE");
    labels.put("MODEL", "VEHICLE_MODEL");
    labels.put("TRIM", "VEHICLE_TRIM");
    for (String name : List.of("MILEAGE", "MILES", "ODOMETER")) labels.put(name, "ODOMETER");
    for (String name : List.of("DEALER", "DEALERSHIP", "SOLD BY", "SELLER", "OFFERED BY")) labels.put(name, "DEALER_NAME");
    for (String name : List.of("CAPTURED", "CAPTURED ON", "DATE CAPTURED", "SAVED", "SAVED ON", "PRINTED", "PRINTED ON",
        "SCREENSHOT TAKEN")) labels.put(name, "CAPTURE_DATE");
    return Map.copyOf(labels);
  }
}
