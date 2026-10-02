package com.dealanalyzer.explanation;

import com.dealanalyzer.ai.ModelProvenance;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The explanation of one finalized Deal Analysis Report. It is returned, never stored, and carries
 * nothing that can change an amount, rating, threshold, boundary, approval or state.
 *
 * @param path which producer made the text; {@code TEMPLATE} whenever the model path is off,
 *     unavailable, or its output failed the claim check
 * @param fallbackReason why the template was used, or null for model text
 * @param provenance the model versions behind model text, or null for template text
 */
public record ReportExplanation(
    String policyVersion,
    Path path,
    String fallbackReason,
    int reportVersion,
    List<Statement> statements,
    ModelProvenance provenance,
    String scope) {
  public static final String VERSION = "report-explanation.v1";

  public ReportExplanation { statements = List.copyOf(statements); }

  public enum Path { MODEL, TEMPLATE }

  /** One checked sentence: its parts in order and the recorded findings and amounts it relies on. */
  public record Statement(List<Segment> segments, List<Source> sources) {
    public Statement {
      segments = List.copyOf(segments);
      sources = List.copyOf(sources);
    }

    /** The sentence as shown, with every amount rendered from the report. */
    @JsonProperty("text")
    public String text() {
      return segments.stream().map(Segment::text).collect(Collectors.joining());
    }
  }

  /**
   * Plain text, or an amount rendered from the report. {@code amountIndex} is the amount's position in
   * {@code /analysis/materialAmounts}, so the client can link it to the figure and its evidence.
   */
  public record Segment(String text, String amountId, Integer amountIndex, String precision) {
    static Segment text(String text) { return new Segment(text, null, null, null); }

    static Segment amount(ExplanationFacts.Amount amount) {
      return new Segment(amount.rendered(), amount.id(), amount.index(), amount.precision());
    }
  }

  /** A finding or amount the sentence relies on, with its pointer and value in the report. */
  public record Source(String id, String sourcePointer, String sourceValue) {}
}
