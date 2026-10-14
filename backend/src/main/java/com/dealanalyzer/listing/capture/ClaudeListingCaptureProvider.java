package com.dealanalyzer.listing.capture;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelResult;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.ai.UntrustedInput;
import com.dealanalyzer.extraction.ClaudeDealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider.EvidenceRegion;
import com.dealanalyzer.extraction.NormalizedDocumentPage;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Model-backed reading of a listing capture through the code-owned {@code listing-capture-prompt.v1} instructions
 * and {@code listing-capture.v1} schema, a prompt of its own beside the deal-terms prompt. The model sees only
 * the normalized page images, as untrusted content; {@link ListingCaptureValidator} decides what is kept, and
 * the Buyer confirms every field before it is used. It stays behind the model rollout gate: selecting it
 * refuses startup until its own shadow comparison is recorded.
 */
public final class ClaudeListingCaptureProvider implements ListingCaptureProvider {
  public static final String PROMPT_VERSION = "listing-capture-prompt.v1";
  public static final String EXTRACTOR_VERSION = "claude:" + PROMPT_VERSION;
  static final String PROMPT_RESOURCE = "/ai/listing/" + PROMPT_VERSION + ".md";
  static final String SCHEMA_RESOURCE = "/ai/listing/" + ListingCaptureValidator.SCHEMA_VERSION + ".schema.json";
  private static final int MAX_OUTPUT_TOKENS = 4_000;
  private static final ModelTask<Output> TASK = load();

  private final ModelHarness harness;

  public ClaudeListingCaptureProvider(ModelHarness harness) {
    this.harness = Objects.requireNonNull(harness);
  }

  /** The model's proposal, before versions are attached; not yet trusted for anything. */
  public record Output(List<Field> fields, List<String> documentWarnings) {
    public Output {
      fields = List.copyOf(fields);
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
  public Reading read(Capture capture) {
    int pageCount = capture.pages().size();
    List<UntrustedInput> inputs = new ArrayList<>();
    for (NormalizedDocumentPage page : capture.pages())
      inputs.add(new UntrustedInput.PngImage(ClaudeDealTermsExtractionProvider.pageLabel(page.pageNumber()), page.image()));
    // Shape checks run inside the metered attempt, so rejected output is recorded as a contract failure.
    ModelResult<Output> result = harness.run(capture.caseId(), TASK, inputs, pageCount,
        output -> ListingCaptureValidator.validate(reading(output, "pending"), pageCount));
    return reading(result.value(), result.provenance().modelId());
  }

  static Reading reading(Output output, String modelVersion) {
    return new Reading(EXTRACTOR_VERSION, modelVersion, ListingCaptureValidator.SCHEMA_VERSION, output.fields(),
        output.documentWarnings());
  }

  private static ModelTask<Output> load() {
    try {
      String instructions = new String(resource(PROMPT_RESOURCE), StandardCharsets.UTF_8).strip();
      Map<String, Object> schema = new ObjectMapper().readValue(resource(SCHEMA_RESOURCE), new TypeReference<>() {});
      return new ModelTask<>(ProviderMeter.Operation.EXTRACTION, PROMPT_VERSION, ListingCaptureValidator.SCHEMA_VERSION,
          instructions, schema, MAX_OUTPUT_TOKENS, ClaudeListingCaptureProvider::parse);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream source = ClaudeListingCaptureProvider.class.getResourceAsStream(name)) {
      if (source == null) throw new IllegalStateException("Missing " + name);
      return source.readAllBytes();
    }
  }

  /** Reads schema-valid output; throws on anything it cannot represent and never repairs a value. */
  static Output parse(JsonNode node) {
    List<Field> fields = new ArrayList<>();
    for (JsonNode field : node.get("fields")) {
      JsonNode value = field.get("normalizedValue");
      JsonNode region = field.get("evidence");
      if (!region.get("pageNumber").canConvertToInt()) throw new IllegalArgumentException("Evidence page out of range");
      fields.add(new Field(field.get("field").asText(), field.get("originalWording").asText(),
          value.isNull() ? null : value.asText(), field.get("normalizedUnit").asText(),
          new EvidenceRegion(region.get("pageNumber").intValue(), number(region.get("x")), number(region.get("y")),
              number(region.get("width")), number(region.get("height"))),
          number(field.get("confidence"))));
    }
    List<String> warnings = new ArrayList<>();
    node.get("documentWarnings").forEach(warning -> warnings.add(warning.asText()));
    return new Output(fields, warnings);
  }

  private static double number(JsonNode value) {
    double number = value.doubleValue();
    if (!Double.isFinite(number)) throw new IllegalArgumentException("Number out of range");
    return number;
  }
}
