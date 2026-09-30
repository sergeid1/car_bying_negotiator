package com.dealanalyzer.ai;

import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The code-owned model task behind a reply suggestion for one Dealer answer. The model only chooses:
 * a short opening and closing, which recorded facts to cite (by identifier; the product renders their
 * wording), follow-up questions, and optionally a counter selling price. Everything it returns is
 * checked again in code before a Buyer sees it, so this task's instructions are guidance, not the
 * safety boundary. Registered as a bean so the rollout gate requires its own shadow comparison.
 */
@Configuration(proxyBeanMethods = false)
public class ReplyDraftTask {
  public static final String PROMPT_VERSION = "reply-draft-prompt.v1";
  public static final String SCHEMA_VERSION = "reply-draft-schema.v1";
  public static final int MAX_TEXT = 600;
  public static final int MAX_FACTS = 8;
  public static final int MAX_QUESTIONS = 5;

  static final String INSTRUCTIONS = """
      You help a car Buyer draft a reply to a Dealer. The Buyer will read, edit and send the reply \
      themselves. You receive two data items: the Dealer's latest response, and a JSON list of recorded \
      facts, each with an id and a statement, plus optional counter figures in minor units.

      Return only the required JSON object:
      - opening: one short courteous sentence acknowledging the response. No figures, no claims.
      - fact_ids: ids of recorded facts that are relevant to the reply, in the order to state them. Use \
      only ids from the recorded facts. Do not restate facts in your own words anywhere.
      - questions: up to five short questions to the Dealer, each ending with a question mark. No \
      figures and no assertions of fact inside a question.
      - proposed_selling_price_minor: a counter selling price in minor units taken from the recorded \
      counter figures, or null when there is none or a counter is not appropriate.
      - closing: one short courteous sentence, or an empty string.

      Never write digits or spelled-out amounts. Never mention competing offers, other dealers, \
      legal matters, eligibility, credit, commitments to buy, guarantees, vehicle condition, history \
      or title, and never attribute statements to the Dealer.""";

  /** What the model proposes; not yet trusted for anything. */
  public record Output(String opening, List<String> factIds, List<String> questions,
      Long proposedSellingPriceMinor, String closing) {
    public Output {
      factIds = List.copyOf(factIds);
      questions = List.copyOf(questions);
    }
  }

  @Bean
  public ModelTask<Output> replyDraftModelTask() {
    return task();
  }

  public static ModelTask<Output> task() {
    Map<String, Object> text = Map.of("type", "string");
    Map<String, Object> schema = Map.of(
        "type", "object",
        "additionalProperties", false,
        "required", List.of("opening", "fact_ids", "questions", "proposed_selling_price_minor", "closing"),
        "properties", Map.of(
            "opening", text,
            "fact_ids", Map.of("type", "array", "items", text),
            "questions", Map.of("type", "array", "items", text),
            "proposed_selling_price_minor", Map.of("type", List.of("integer", "null")),
            "closing", text));
    return new ModelTask<>(ProviderMeter.Operation.REPLY_DRAFT, PROMPT_VERSION, SCHEMA_VERSION,
        INSTRUCTIONS, schema, 2048, ReplyDraftTask::parse);
  }

  private static Output parse(JsonNode node) {
    List<String> facts = strings(node.get("fact_ids"), MAX_FACTS);
    List<String> questions = strings(node.get("questions"), MAX_QUESTIONS);
    JsonNode proposal = node.get("proposed_selling_price_minor");
    if (!proposal.isNull() && !proposal.canConvertToLong()) throw new IllegalArgumentException("Proposal out of range");
    return new Output(bounded(node.get("opening").asText()), facts, questions,
        proposal.isNull() ? null : proposal.longValue(), bounded(node.get("closing").asText()));
  }

  private static List<String> strings(JsonNode array, int max) {
    if (array.size() > max) throw new IllegalArgumentException("Too many entries");
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(bounded(value.asText())));
    return values;
  }

  private static String bounded(String value) {
    if (value.length() > MAX_TEXT) throw new IllegalArgumentException("Text exceeds its bound");
    return value;
  }
}
