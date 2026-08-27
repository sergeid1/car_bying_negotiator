package com.dealanalyzer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ExtractionEvaluatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void scoresPresenceSeparatelyFromNumericExactMatch() throws Exception {
    JsonNode fixture = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/extraction/scoring-corpus.json"));

    ExtractionEvaluator.Score score = new ExtractionEvaluator()
        .score(fixture.get("expected"), fixture.get("observed"));
    JsonNode expected = fixture.get("expectedScore");

    assertThat(score.expectedFieldCount()).isEqualTo(expected.get("expectedFieldCount").asInt());
    assertThat(score.presentFieldCount()).isEqualTo(expected.get("presentFieldCount").asInt());
    assertThat(score.expectedNumericFieldCount())
        .isEqualTo(expected.get("expectedNumericFieldCount").asInt());
    assertThat(score.exactNumericFieldCount())
        .isEqualTo(expected.get("exactNumericFieldCount").asInt());
    assertThat(score.presenceRecall()).isEqualTo(expected.get("presenceRecall").asDouble());
    assertThat(score.numericExactMatchRate())
        .isCloseTo(expected.get("numericExactMatchRate").asDouble(),
            org.assertj.core.data.Offset.offset(0.0000000001));
  }
}
