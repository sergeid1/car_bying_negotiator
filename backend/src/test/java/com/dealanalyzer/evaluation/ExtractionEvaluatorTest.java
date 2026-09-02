package com.dealanalyzer.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.DeterministicDealerDocumentTestSupport;
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
    assertThat(score.observedFieldCount()).isEqualTo(expected.get("observedFieldCount").asInt());
    assertThat(score.presentFieldCount()).isEqualTo(expected.get("presentFieldCount").asInt());
    assertThat(score.unexpectedFieldCount())
        .isEqualTo(expected.get("unexpectedFieldCount").asInt());
    assertThat(score.expectedNumericFieldCount())
        .isEqualTo(expected.get("expectedNumericFieldCount").asInt());
    assertThat(score.exactNumericFieldCount())
        .isEqualTo(expected.get("exactNumericFieldCount").asInt());
    assertThat(score.presenceRecall()).isEqualTo(expected.get("presenceRecall").asDouble());
    assertThat(score.presencePrecision()).isEqualTo(expected.get("presencePrecision").asDouble());
    assertThat(score.numericExactMatchRate())
        .isCloseTo(expected.get("numericExactMatchRate").asDouble(),
            org.assertj.core.data.Offset.offset(0.0000000001));
  }

  @Test
  void scoresEachFieldWithConfiguredWeightsAndAppliesTheMaterialPresenceGate() throws Exception {
    JsonNode fixture = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/extraction/scoring-corpus.json"));

    ExtractionEvaluator.Score score = new ExtractionEvaluator().score(
        fixture.get("expected"),
        fixture.get("observed"),
        fixture.get("fieldPolicies"),
        fixture.get("minimumMaterialPresenceRecall").asDouble());
    JsonNode expected = fixture.get("expectedScore");

    assertThat(score.fields().get("vin"))
        .isEqualTo(new ExtractionEvaluator.FieldScore(true, true, false, 3.0, true));
    assertThat(score.fields().get("outTheDoorTotalMinor"))
        .isEqualTo(new ExtractionEvaluator.FieldScore(true, false, false, 3.0, true));
    assertThat(score.fields().get("dealerFeeMinor"))
        .isEqualTo(new ExtractionEvaluator.FieldScore(false, false, false, 1.0, true));
    assertThat(score.unexpectedFields()).containsExactly("inventedProtectionPlanMinor");
    assertThat(score.weightedPresenceRecall())
        .isEqualTo(expected.get("weightedPresenceRecall").asDouble());
    assertThat(score.weightedExactMatchRate())
        .isEqualTo(expected.get("weightedExactMatchRate").asDouble());
    assertThat(score.weightedNumericExactMatchRate())
        .isCloseTo(expected.get("weightedNumericExactMatchRate").asDouble(),
            org.assertj.core.data.Offset.offset(0.0000000001));
    assertThat(score.materialPresenceRecall())
        .isEqualTo(expected.get("materialPresenceRecall").asDouble());
    assertThat(score.passesMaterialPresenceGate())
        .isEqualTo(expected.get("passesMaterialPresenceGate").asBoolean());
  }

  @Test
  void reportsMaterialPresenceRecallAndExactValueAccuracyForSupportedLayouts() throws Exception {
    JsonNode corpus = JSON.readTree(getClass().getResourceAsStream(
        "/fixtures/extraction/supported-layout-corpus.json"));

    for (JsonNode layout : corpus.get("layouts")) {
      DealTermsExtractionProvider.ProviderExtraction extraction =
          DeterministicDealerDocumentTestSupport.extract(layout.get("pages"));
      ObjectNode observed = JSON.createObjectNode();
      extraction.candidates().forEach(candidate -> {
        if (candidate.normalizedValue() == null) {
          return;
        }
        if (candidate.normalizedUnit().equals("USD_MINOR")
            || candidate.normalizedUnit().equals("MONTH")
            || candidate.normalizedUnit().equals("YEAR")) {
          observed.put(candidate.field(), Long.parseLong(candidate.normalizedValue()));
        } else if (candidate.normalizedUnit().equals("PERCENT")) {
          observed.put(candidate.field(), new java.math.BigDecimal(candidate.normalizedValue()));
        } else {
          observed.put(candidate.field(), candidate.normalizedValue());
        }
      });
      ExtractionEvaluator.Score score = new ExtractionEvaluator().score(
          layout.get("expected"), observed, corpus.get("fieldPolicies"),
          corpus.get("minimumMaterialPresenceRecall").asDouble());

      assertThat(score.materialPresenceRecall())
          .as(layout.get("name").asText() + " material presence recall")
          .isEqualTo(1.0);
      assertThat(score.weightedExactMatchRate())
          .as(layout.get("name").asText() + " exact-value accuracy")
          .isEqualTo(1.0);
      assertThat(score.passesMaterialPresenceGate()).isTrue();
    }
  }
}
