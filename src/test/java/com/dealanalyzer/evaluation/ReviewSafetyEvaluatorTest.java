package com.dealanalyzer.evaluation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ReviewSafetyEvaluatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void releaseCorpusHasNoUnsafePositiveReview() throws Exception {
    JsonNode corpus = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/review/review-safety-corpus.json"));

    new ReviewSafetyEvaluator().assertNoUnsafeMatches(corpus.get("fixtures"));
  }

  @Test
  void unsafePositiveReviewFailsTheSafetyGate() throws Exception {
    JsonNode corpus = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/review/review-safety-corpus.json"));
    ArrayNode unsafeCorpus = ((ArrayNode) corpus.get("fixtures")).deepCopy();
    ObjectNode unreadableAmount = (ObjectNode) unsafeCorpus.get(1);
    unreadableAmount.put("actualStatus", "MATCHES_APPROVED_BASELINE");

    assertThatThrownBy(() -> new ReviewSafetyEvaluator().assertNoUnsafeMatches(unsafeCorpus))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unreadable material amount");
  }

  @Test
  void everyUnsafeConditionRejectsAPositiveReview() throws Exception {
    JsonNode corpus = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/review/review-safety-corpus.json"));

    for (JsonNode fixture : corpus.get("fixtures")) {
      if (fixture.path("actualStatus").asText().equals("MATCHES_APPROVED_BASELINE")) {
        continue;
      }
      ObjectNode unsafePositive = ((ObjectNode) fixture).deepCopy();
      unsafePositive.put("actualStatus", "MATCHES_APPROVED_BASELINE");
      ArrayNode singleFixtureCorpus = JSON.createArrayNode().add(unsafePositive);

      assertThatThrownBy(
          () -> new ReviewSafetyEvaluator().assertNoUnsafeMatches(singleFixtureCorpus))
          .as(fixture.path("name").asText())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(fixture.path("name").asText());
    }
  }
}
