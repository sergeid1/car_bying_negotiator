package com.dealanalyzer.extraction;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelResult;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.ai.UntrustedInput;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model-backed deal-terms extraction through the code-owned {@code deal-terms-prompt.v1} instructions
 * and {@code deal-terms.v1} schema. The model sees only the normalized page images, as untrusted
 * content, and proposes candidates; {@link DealTermsValidator} decides what is accepted, and nothing
 * the model returns can set a verified term, an approval, a limit or a state.
 *
 * <p>Output that fails the schema, or whose evidence cites a page or region the document does not
 * have, fails the call closed as a contract failure. A value whose digits do not appear in the wording
 * the model quoted for it is kept only at zero confidence, so it always needs review.
 */
public final class ClaudeDealTermsExtractionProvider implements DealTermsExtractionProvider {
  public static final String PROMPT_VERSION = "deal-terms-prompt.v1";
  public static final String SCHEMA_VERSION = "deal-terms.v1";
  /** Recorded as the extraction's extractor version, so each extraction names its provider and prompt. */
  public static final String EXTRACTOR_VERSION = "claude:" + PROMPT_VERSION;
  static final String PROMPT_RESOURCE = "/ai/extraction/" + PROMPT_VERSION + ".md";
  static final String SCHEMA_RESOURCE = "/ai/extraction/" + SCHEMA_VERSION + ".schema.json";
  static final double UNSUPPORTED_VALUE_CONFIDENCE = 0.0;
  private static final int MAX_OUTPUT_TOKENS = 16_000;
  private static final ModelTask<Output> TASK = load();

  private final ModelHarness harness;
  private final String modelId;
  private final DealTermsValidator validator;

  public ClaudeDealTermsExtractionProvider(ModelHarness harness, String modelId, double materialConfidenceThreshold) {
    this.harness = Objects.requireNonNull(harness);
    this.modelId = Objects.requireNonNull(modelId);
    this.validator = new DealTermsValidator(materialConfidenceThreshold);
  }

  /** The model's proposal, before versions are attached; not yet trusted for anything. */
  public record Output(Integer expectedPageCount, List<Candidate> candidates, List<LineItem> lineItems,
      List<String> documentWarnings) {
    public Output {
      candidates = List.copyOf(candidates);
      lineItems = List.copyOf(lineItems);
      documentWarnings = List.copyOf(documentWarnings);
    }
  }

  public static ModelTask<Output> task() {
    return TASK;
  }

  @Override
  public boolean selfMetered() {
    return true;
  }

  @Override
  public ProviderExtraction extract(ExtractionDocument document) {
    int pageCount = document.pages().size();
    List<UntrustedInput> inputs = new ArrayList<>();
    for (NormalizedDocumentPage page : document.pages()) {
      inputs.add(new UntrustedInput.PngImage("page " + page.pageNumber() + " image", page.image()));
    }
    // Shape checks run inside the metered attempt, so rejected output is recorded as a contract
    // failure rather than a success. The service validates again; the validator stays the authority.
    ModelResult<Output> result = harness.run(document.caseId(), TASK, inputs, pageCount,
        output -> validator.validate(extraction(output, modelId), pageCount, false));
    return extraction(result.value(), result.provenance().modelId());
  }

  static ProviderExtraction extraction(Output output, String modelVersion) {
    return new ProviderExtraction(EXTRACTOR_VERSION, modelVersion, SCHEMA_VERSION,
        output.expectedPageCount(), output.candidates(), output.lineItems(), output.documentWarnings());
  }

  private static ModelTask<Output> load() {
    try {
      String instructions = new String(resource(PROMPT_RESOURCE), StandardCharsets.UTF_8).strip();
      Map<String, Object> schema = new ObjectMapper().readValue(resource(SCHEMA_RESOURCE), new TypeReference<>() {});
      return new ModelTask<>(ProviderMeter.Operation.EXTRACTION, PROMPT_VERSION, SCHEMA_VERSION,
          instructions, schema, MAX_OUTPUT_TOKENS, ClaudeDealTermsExtractionProvider::parse);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream source = ClaudeDealTermsExtractionProvider.class.getResourceAsStream(name)) {
      if (source == null) throw new IllegalStateException("Missing " + name);
      return source.readAllBytes();
    }
  }

  /** Reads schema-valid output; throws on anything it cannot represent and never repairs a value. */
  static Output parse(JsonNode node) {
    JsonNode pages = node.get("expectedPageCount");
    if (!pages.isNull() && !pages.canConvertToInt()) throw new IllegalArgumentException("Page count out of range");
    List<Candidate> candidates = new ArrayList<>();
    for (JsonNode candidate : node.get("candidates")) {
      String field = candidate.get("field").asText();
      String wording = candidate.get("originalWording").asText();
      JsonNode rawValue = candidate.get("normalizedValue");
      String value = rawValue.isNull() ? null : rawValue.asText();
      String unit = candidate.get("normalizedUnit").asText();
      double confidence = number(candidate.get("confidence"));
      boolean material = candidate.get("material").asBoolean();
      if (value != null && !supportedByWording(unit, value, wording)) {
        confidence = Math.min(confidence, UNSUPPORTED_VALUE_CONFIDENCE);
        material = true;
      }
      candidates.add(new Candidate(field, wording, value, unit, evidence(candidate.get("evidence")),
          confidence, material));
    }
    List<LineItem> lineItems = new ArrayList<>();
    for (JsonNode line : node.get("lineItems")) {
      JsonNode amount = line.get("amountMinor");
      if (!amount.canConvertToLong()) throw new IllegalArgumentException("Amount out of range");
      lineItems.add(new LineItem(line.get("originalLabel").asText(), line.get("category").asText(),
          amount.longValue(), line.get("currency").asText(), line.get("taxClaim").asText(),
          line.get("optionalityClaim").asText(), evidence(line.get("evidence")), number(line.get("confidence"))));
    }
    List<String> warnings = new ArrayList<>();
    node.get("documentWarnings").forEach(warning -> warnings.add(warning.asText()));
    return new Output(pages.isNull() ? null : pages.intValue(), candidates, lineItems, warnings);
  }

  private static EvidenceRegion evidence(JsonNode region) {
    JsonNode page = region.get("pageNumber");
    if (!page.canConvertToInt()) throw new IllegalArgumentException("Evidence page out of range");
    return new EvidenceRegion(page.intValue(), number(region.get("x")), number(region.get("y")),
        number(region.get("width")), number(region.get("height")));
  }

  private static double number(JsonNode value) {
    double number = value.doubleValue();
    if (!Double.isFinite(number)) throw new IllegalArgumentException("Number out of range");
    return number;
  }

  private static final Pattern DIGIT_GROUP = Pattern.compile("[0-9]+");

  /**
   * Whether the normalized value can be read from the wording the model quoted for it. A model that
   * fills in an illegible digit tends to report a value the quoted text does not contain; such a value
   * is kept for review rather than trusted. Text values are not checked.
   */
  static boolean supportedByWording(String unit, String value, String wording) {
    String digits = wording.replaceAll("[^0-9]", "");
    return switch (unit) {
      case "USD_MINOR", "PERCENT" -> {
        String significant = value.replaceAll("[^0-9]", "").replaceFirst("^0+", "").replaceFirst("0+$", "");
        // A value the validator cannot read as a number is left to the validator to reject.
        yield !value.matches("-?[0-9]+(\\.[0-9]+)?") || digits.contains(significant);
      }
      case "MONTH", "YEAR" -> !value.matches("[0-9]+") || digits.contains(value);
      case "VIN" -> wording.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "").contains(value);
      case "DATE" -> dateSupported(value, wording);
      default -> true;
    };
  }

  private static boolean dateSupported(String value, String wording) {
    LocalDate date;
    try {
      date = LocalDate.parse(value);
    } catch (java.time.format.DateTimeParseException invalid) {
      return true;
    }
    List<Integer> groups = new ArrayList<>();
    Matcher matcher = DIGIT_GROUP.matcher(wording);
    while (matcher.find()) {
      if (matcher.group().length() <= 4) groups.add(Integer.parseInt(matcher.group()));
    }
    String upper = wording.toUpperCase(Locale.ROOT);
    boolean month = groups.contains(date.getMonthValue())
        || upper.contains(date.getMonth().getDisplayName(TextStyle.SHORT, Locale.US).toUpperCase(Locale.ROOT));
    boolean year = groups.contains(date.getYear()) || groups.contains(date.getYear() % 100);
    return month && groups.contains(date.getDayOfMonth()) && year;
  }
}
