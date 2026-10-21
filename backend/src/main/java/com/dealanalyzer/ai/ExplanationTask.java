package com.dealanalyzer.ai;

import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The code-owned model task behind the explanation of one finalized Deal Analysis Report. The model
 * receives only the report's finalized findings and the identifiers of its material amounts, never a
 * figure, document text or an extraction candidate. It writes short sentences that each cite one finding
 * by id and refer to amounts only by placeholder; the product renders every amount from the report.
 * Every sentence is checked again in code before a Buyer sees it, so these instructions are guidance,
 * not the safety boundary. Registered as a bean so the rollout gate requires its own shadow comparison.
 */
@Configuration(proxyBeanMethods = false)
public class ExplanationTask {
  public static final String PROMPT_VERSION = "report-explanation-prompt.v1";
  public static final String SCHEMA_VERSION = "report-explanation-schema.v1";
  public static final int MAX_SENTENCES = 6;
  public static final int MAX_TEXT = 400;

  static final String INSTRUCTIONS = """
      You explain a finalized car Deal Analysis Report to the Buyer in plain language. You receive one \
      data item: a JSON object with the report's findings (each with an id, a subject and its recorded \
      state) and its material amounts (each with an id and a precision, but no figure).

      Return only the required JSON object with up to six sentences. Each sentence has:
      - text: one plain sentence describing only what the cited findings record. To mention an amount, \
      write its placeholder exactly, for example {amount-0}; the product inserts the recorded figure.
      - finding_id: the id of the single finding the sentence describes. Use only ids from the data. Describe each finding in its own sentence, and mention only amounts whose subjects match it.

      Never write digits or spelled-out amounts. Describe a finding whose state is unavailable, missing \
      or insufficient as unknown or unavailable. Never call an estimated amount exact. Do not mention \
      legal, tax, mechanical, title or vehicle-history matters, whether it is safe to sign, savings, \
      guarantees, or advice, and do not describe yourself.""";

  /** What the model proposes; not yet trusted for anything. */
  public record Output(List<Sentence> sentences) {
    public Output { sentences = List.copyOf(sentences); }
  }

  /** One sentence and the single finding it describes; the schema allows no second id. */
  public record Sentence(String text, String findingId) {}

  @Bean
  public ModelTask<Output> explanationModelTask() {
    return task();
  }

  @Bean
  public ModelTask<Output> spanishExplanationModelTask() { return spanishTask(); }

  public static ModelTask<Output> spanishTask() {
    var english = task();
    return new ModelTask<>(english.operation(), "report-explanation-es-prompt.v1", english.schemaVersion(),
        english.instructions() + "\nWrite every sentence in neutral US Latin American Spanish, addressing the Buyer as usted.",
        english.outputSchema(), english.maxOutputTokens(), english.parse());
  }

  public static ModelTask<Output> task() {
    Map<String, Object> text = Map.of("type", "string");
    Map<String, Object> sentence = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("text", "finding_id"),
        "properties", Map.of("text", text, "finding_id", text));
    Map<String, Object> schema = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("sentences"),
        "properties", Map.of("sentences", Map.of("type", "array", "items", sentence)));
    return new ModelTask<>(ProviderMeter.Operation.EXPLANATION, PROMPT_VERSION, SCHEMA_VERSION,
        INSTRUCTIONS, schema, 2048, ExplanationTask::parse);
  }

  private static Output parse(JsonNode node) {
    JsonNode sentences = node.get("sentences");
    if (sentences.isEmpty() || sentences.size() > MAX_SENTENCES) throw new IllegalArgumentException("Sentence count out of range");
    List<Sentence> parsed = new ArrayList<>();
    for (JsonNode sentence : sentences) {
      parsed.add(new Sentence(bounded(sentence.get("text").asText(), MAX_TEXT),
          bounded(sentence.get("finding_id").asText(), 100)));
    }
    return new Output(parsed);
  }

  private static String bounded(String value, int max) {
    if (value.length() > max) throw new IllegalArgumentException("Text exceeds its bound");
    return value;
  }
}
