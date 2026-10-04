package com.dealanalyzer.purchase;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ContributionThresholdTest {
  private final ObjectMapper json = new ObjectMapper();
  @Test void missingFeesUnknownCostsAndSmallSamplesCanNeverMeetMarginThreshold() throws Exception {
    var threshold = json.readTree("{\"metric\":\"MEDIAN_CONTRIBUTION_USD\",\"bound\":\"AT_LEAST\",\"value\":1,\"minimumDenominator\":2}");
    for (String status : new String[] {"UNAVAILABLE", "NOT_MEASURABLE", "MEASURED"}) {
      var snapshot = json.readTree("{\"historicalDeletionCoverage\":{\"status\":\"COMPLETE\"},\"contributionMargin\":{\"status\":\"" + status + "\",\"paidCaseCount\":1,\"count\":1,\"medianUsd\":10}}");
      assertThat(json.valueToTree(ContributionDecisionService.evaluate(threshold, snapshot)).path("status").asText()).isEqualTo("NOT_MEASURABLE");
      ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot.path("contributionMargin")).put("paidCaseCount", 2);
      assertThat(json.valueToTree(ContributionDecisionService.evaluate(threshold, snapshot)).path("status").asText()).isEqualTo(status.equals("MEASURED") ? "MET" : "NOT_MEASURABLE");
    }
  }
}
