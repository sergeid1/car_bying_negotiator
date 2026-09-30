package com.dealanalyzer.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The recorded evidence a model-backed path needs before it may run: validated vendor retention and
 * training terms, and shadow comparisons produced by the Python evaluation harness (ticket 28)
 * against the frozen corpus. Both are code-reviewed classpath records, not runtime settings.
 */
public record ModelObligations(VendorTerms vendorTerms, List<ShadowComparison> comparisons) {
  static final String VENDOR_TERMS = "/ai/model-vendor-terms.json";
  static final String SHADOW_COMPARISONS = "/ai/shadow-comparisons.json";
  static final String TERMS_SCHEMA = "model-vendor-terms.v1";
  static final String COMPARISONS_SCHEMA = "shadow-comparisons.v1";
  /** The only producer whose comparisons count; a hand-written record is not evidence. */
  public static final String EVALUATION_HARNESS = "python-evaluation-harness";

  public ModelObligations {
    comparisons = List.copyOf(comparisons);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record VendorTerms(String schemaVersion, String vendor, String status, String agreementReference,
      Term retention, Term trainingExclusion, String validatedBy, Instant validatedAt, String blocker) {
    /** Executed, and both the retention and no-training terms were checked against the contract. */
    public boolean validated() {
      return "EXECUTED".equals(status) && present(agreementReference) && agreementReference.length() <= 100
          && present(validatedBy)
          && validatedAt != null && retention != null && retention.validated()
          && trainingExclusion != null && trainingExclusion.validated();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Term(boolean validated, String clause) {
    public Term {
      if (validated && !present(clause)) throw new IllegalArgumentException("A validated term names its clause");
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ComparisonRecord(String schemaVersion, List<ShadowComparison> comparisons) {}

  /**
   * One shadow comparison of a candidate against the current baseline on the frozen held-out corpus.
   * The candidate tuple is what the gate matches against the configured model and each task.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShadowComparison(String id, String operation, Candidate candidate, String baseline,
      String evaluationSetVersion, String thresholdSetVersion, String corpusSplit, String executedBy,
      String harnessVersion, Instant executedAt, String outcome, String report) {
    static final Set<String> OUTCOMES = Set.of("PASS", "FAIL");

    public ShadowComparison {
      if (!present(id) || !present(operation) || candidate == null || !present(baseline)
          || !present(evaluationSetVersion) || !present(thresholdSetVersion)
          || !"HELD_OUT".equals(corpusSplit) || !EVALUATION_HARNESS.equals(executedBy)
          || !present(harnessVersion) || executedAt == null || !OUTCOMES.contains(outcome) || !present(report)) {
        throw new IllegalArgumentException("Shadow comparison " + id + " is incomplete or not from the evaluation harness");
      }
    }

    boolean covers(String operation, ModelProvenance provenance) {
      return "PASS".equals(outcome) && this.operation.equals(operation)
          && candidate.modelId().equals(provenance.modelId())
          && candidate.promptVersion().equals(provenance.promptVersion())
          && candidate.schemaVersion().equals(provenance.schemaVersion())
          && evaluationSetVersion.equals(provenance.evaluationSetVersion())
          && thresholdSetVersion.equals(provenance.thresholdSetVersion());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Candidate(String modelId, String promptVersion, String schemaVersion) {
    public Candidate {
      if (!present(modelId) || !present(promptVersion) || !present(schemaVersion)) {
        throw new IllegalArgumentException("A comparison candidate names its model, prompt and schema versions");
      }
    }
  }

  /** Reads and validates the records shipped on the classpath. A malformed record fails closed. */
  public static ModelObligations shipped(ObjectMapper json) {
    VendorTerms terms = read(json, VENDOR_TERMS, VendorTerms.class);
    if (!TERMS_SCHEMA.equals(terms.schemaVersion())) throw new IllegalStateException("Unsupported " + VENDOR_TERMS);
    ComparisonRecord record = read(json, SHADOW_COMPARISONS, ComparisonRecord.class);
    if (!COMPARISONS_SCHEMA.equals(record.schemaVersion()) || record.comparisons() == null) {
      throw new IllegalStateException("Unsupported " + SHADOW_COMPARISONS);
    }
    return new ModelObligations(terms, record.comparisons());
  }

  private static <T> T read(ObjectMapper json, String resource, Class<T> type) {
    try (InputStream source = ModelObligations.class.getResourceAsStream(resource)) {
      if (source == null) throw new IllegalStateException("Missing " + resource);
      return json.readValue(source, type);
    } catch (IOException | IllegalArgumentException cause) {
      throw new IllegalStateException("Unreadable " + resource, cause);
    }
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
