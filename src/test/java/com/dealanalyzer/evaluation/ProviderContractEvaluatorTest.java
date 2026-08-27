package com.dealanalyzer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderContractEvaluatorTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void recordedContractsCoverSuccessAndFailureBehaviorWithoutLiveCalls() throws Exception {
    JsonNode corpus = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/providers/market-provider-contract.json"));
    ProviderContractEvaluator evaluator = new ProviderContractEvaluator(
        Instant.parse(corpus.get("frozenNow").asText()),
        Duration.ofDays(corpus.get("maxAgeDays").asLong()));

    for (JsonNode fixture : corpus.get("scenarios")) {
      ProviderContractEvaluator.Result result = evaluator.evaluate(fixture);
      assertThat(result.status().name())
          .as(fixture.get("name").asText())
          .isEqualTo(fixture.get("expectedStatus").asText());
      assertThat(result.uniqueObservationCount())
          .as(fixture.get("name").asText())
          .isEqualTo(fixture.get("expectedUniqueObservations").asInt());
    }
  }
}
