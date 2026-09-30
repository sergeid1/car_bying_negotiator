package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dealanalyzer.ai.ModelObligations.Candidate;
import com.dealanalyzer.ai.ModelObligations.ShadowComparison;
import com.dealanalyzer.ai.ModelObligations.Term;
import com.dealanalyzer.ai.ModelObligations.VendorTerms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ModelRolloutGateTest {
  private static final ModelSettings ENABLED = new ModelSettings(true, "claude-opus-5", "pilot-parameters.v1", "corpus.v1");
  private static final UnaryOperator<String> CREDENTIALS = Map.of("ANTHROPIC_API_KEY", "set")::get;
  private static final VendorTerms EXECUTED = new VendorTerms("model-vendor-terms.v1", "Anthropic", "EXECUTED",
      "agreement-2026-01", new Term(true, "Section 4.2 retention"), new Term(true, "Section 5.1 no training"),
      "counsel", Instant.parse("2026-09-01T00:00:00Z"), null);

  private static ShadowComparison comparison(String promptVersion, String outcome) {
    return new ShadowComparison("cmp-1", "EXTRACTION", new Candidate("claude-opus-5", promptVersion, "total-schema.v1"),
        "deterministic-dealer-layout.v1", "corpus.v1", "pilot-parameters.v1", "HELD_OUT",
        ModelObligations.EVALUATION_HARNESS, "0.1.0", Instant.parse("2026-09-02T00:00:00Z"), outcome, "reports/cmp-1.json");
  }

  private static List<String> unmet(ModelSettings settings, ModelObligations obligations, UnaryOperator<String> env) {
    return ModelRolloutGate.unmet(settings, obligations, List.of(TestTasks.total()), env);
  }

  @Test void everyObligationMetAllowsEnablement() {
    var obligations = new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v1", "PASS")));
    assertThat(unmet(ENABLED, obligations, CREDENTIALS)).isEmpty();
    new ModelRolloutGate(ENABLED, obligations, List.of(TestTasks.total()), CREDENTIALS);
  }

  @Test void theShippedRecordsRefuseEnablementForUnexecutedTermsAndMissingComparisons() {
    var shipped = ModelObligations.shipped(new ObjectMapper().registerModule(new JavaTimeModule()));
    var defaults = new ModelSettings(true, "claude-opus-5", "pilot-parameters.v1", ModelSettings.NO_EVALUATION_SET);
    assertThat(unmet(defaults, shipped, name -> null)).containsExactly(
        "VENDOR_TERMS_NOT_VALIDATED", "NO_FROZEN_EVALUATION_SET", "CREDENTIALS_MISSING",
        "NO_SHADOW_COMPARISON:EXTRACTION:total-prompt.v1:total-schema.v1");
    assertThatThrownBy(() -> new ModelRolloutGate(defaults, shipped, List.of(), name -> null))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("VENDOR_TERMS_NOT_VALIDATED");
  }

  @Test void aRolloutWithoutAMatchingPassingComparisonIsRefused() {
    String missing = "NO_SHADOW_COMPARISON:EXTRACTION:total-prompt.v1:total-schema.v1";
    assertThat(unmet(ENABLED, new ModelObligations(EXECUTED, List.of()), CREDENTIALS)).containsExactly(missing);
    assertThat(unmet(ENABLED, new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v0", "PASS"))), CREDENTIALS))
        .as("a comparison of an earlier prompt does not cover a new one").containsExactly(missing);
    assertThat(unmet(ENABLED, new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v1", "FAIL"))), CREDENTIALS))
        .as("a failed comparison is not a release").containsExactly(missing);
    var otherModel = new ModelSettings(true, "claude-sonnet-5", "pilot-parameters.v1", "corpus.v1");
    assertThat(unmet(otherModel, new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .as("changing the model needs its own comparison and price").contains(missing, "MODEL_NOT_PRICED");
    var otherCorpus = new ModelSettings(true, "claude-opus-5", "pilot-parameters.v1", "corpus.v2");
    assertThat(unmet(otherCorpus, new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .containsExactly(missing);
  }

  @Test void comparisonsNotProducedByTheEvaluationHarnessAreNotEvidence() {
    assertThatThrownBy(() -> new ShadowComparison("cmp", "EXTRACTION", new Candidate("claude-opus-5", "p", "s"),
        "baseline", "corpus.v1", "pilot-parameters.v1", "HELD_OUT", "hand-written", "0", Instant.now(), "PASS", "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShadowComparison("cmp", "EXTRACTION", new Candidate("claude-opus-5", "p", "s"),
        "baseline", "corpus.v1", "pilot-parameters.v1", "DEVELOPMENT", ModelObligations.EVALUATION_HARNESS, "0",
        Instant.now(), "PASS", "r")).as("only the frozen held-out split counts").isInstanceOf(IllegalArgumentException.class);
  }

  @Test void credentialsAndEndpointComeFromTheEnvironmentOnly() {
    var obligations = new ModelObligations(EXECUTED, List.of(comparison("total-prompt.v1", "PASS")));
    assertThat(unmet(ENABLED, obligations, name -> null)).containsExactly("CREDENTIALS_MISSING");
    assertThat(unmet(ENABLED, obligations, Map.of("ANTHROPIC_API_KEY", "set", "ANTHROPIC_BASE_URL", "http://elsewhere")::get))
        .containsExactly("ENDPOINT_OVERRIDDEN");
  }

  @Test void unvalidatedTermsBlockEvenWithEverythingElseInPlace() {
    var unsigned = new VendorTerms("model-vendor-terms.v1", "Anthropic", "EXECUTED", "agreement-2026-01",
        new Term(true, "Section 4.2"), new Term(false, null), "counsel", Instant.now(), null);
    assertThat(unmet(ENABLED, new ModelObligations(unsigned, List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .containsExactly("VENDOR_TERMS_NOT_VALIDATED");
  }

  @Test void applicationStartupFailsWhenEnabledAgainstTheShippedRecords() {
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> new ObjectMapper().registerModule(new JavaTimeModule()))
        .withUserConfiguration(ModelRolloutGate.class)
        .withPropertyValues("ai.model.enabled=true")
        .run(context -> assertThat(context).hasFailed()
            .getFailure().rootCause().hasMessageContaining("VENDOR_TERMS_NOT_VALIDATED"));
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> new ObjectMapper().registerModule(new JavaTimeModule()))
        .withUserConfiguration(ModelRolloutGate.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
