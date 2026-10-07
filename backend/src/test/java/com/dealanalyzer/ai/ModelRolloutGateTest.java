package com.dealanalyzer.ai;

import static com.dealanalyzer.ai.ModelProvider.ANTHROPIC;
import static com.dealanalyzer.ai.ModelProvider.OPENAI;
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
  private static final ModelSettings ENABLED = new ModelSettings(true, ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "corpus.v1");
  private static final ModelSettings OPENAI_ENABLED = new ModelSettings(true, OPENAI, "gpt-6-astra", "pilot-parameters.v1", "corpus.v1");
  private static final UnaryOperator<String> CREDENTIALS = Map.of("ANTHROPIC_API_KEY", "set")::get;
  private static final UnaryOperator<String> OPENAI_CREDENTIALS = Map.of("OPENAI_API_KEY", "set")::get;
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private static VendorTerms executed(ModelProvider provider) {
    return new VendorTerms(provider, provider.name(), "EXECUTED", "agreement-" + provider,
        new Term(true, "Section 4.2 retention"), new Term(true, "Section 5.1 no training"),
        "counsel", Instant.parse("2026-09-01T00:00:00Z"), null);
  }

  private static ShadowComparison comparison(ModelProvider provider, String model, String promptVersion, String outcome) {
    return new ShadowComparison("cmp-1", "EXTRACTION", new Candidate(provider, model, promptVersion, "total-schema.v1"),
        "deterministic-dealer-layout.v1", "corpus.v1", "pilot-parameters.v1", "HELD_OUT",
        ModelObligations.EVALUATION_HARNESS, "0.1.0", Instant.parse("2026-09-02T00:00:00Z"), outcome, "reports/cmp-1.json");
  }

  private static ShadowComparison comparison(String promptVersion, String outcome) {
    return comparison(ANTHROPIC, "claude-opus-5", promptVersion, outcome);
  }

  private static ModelObligations anthropicMet(List<ShadowComparison> comparisons) {
    return new ModelObligations(List.of(executed(ANTHROPIC)), comparisons);
  }

  private static List<String> unmet(ModelSettings settings, ModelObligations obligations, UnaryOperator<String> env) {
    return ModelRolloutGate.unmet(settings, obligations, List.of(TestTasks.total()), env);
  }

  @Test void everyObligationMetAllowsEnablement() {
    var obligations = anthropicMet(List.of(comparison("total-prompt.v1", "PASS")));
    assertThat(unmet(ENABLED, obligations, CREDENTIALS)).isEmpty();
    new ModelRolloutGate(ENABLED, obligations, List.of(TestTasks.total()), CREDENTIALS);

    var openAi = new ModelObligations(List.of(executed(OPENAI)),
        List.of(comparison(OPENAI, "gpt-6-astra", "total-prompt.v1", "PASS")));
    assertThat(unmet(OPENAI_ENABLED, openAi, OPENAI_CREDENTIALS)).isEmpty();
  }

  @Test void theShippedRecordsRefuseEnablementOfEitherProvider() {
    var shipped = ModelObligations.shipped(JSON);
    for (ModelProvider provider : ModelProvider.values()) {
      var defaults = new ModelSettings(true, provider, provider.defaultModel(), "pilot-parameters.v1",
          ModelSettings.NO_EVALUATION_SET);
      assertThat(unmet(defaults, shipped, name -> null)).containsExactly(
          "VENDOR_TERMS_NOT_VALIDATED:" + provider, "NO_FROZEN_EVALUATION_SET", "CREDENTIALS_MISSING:" + provider,
          "NO_SHADOW_COMPARISON:" + provider + ":EXTRACTION:total-prompt.v1:total-schema.v1");
      assertThatThrownBy(() -> new ModelRolloutGate(defaults, shipped, List.of(), name -> null))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("VENDOR_TERMS_NOT_VALIDATED:" + provider);
    }
    assertThat(shipped.termsFor(OPENAI).vendor()).isEqualTo("OpenAI");
    assertThat(shipped.termsFor(OPENAI).status()).isEqualTo("UNEXECUTED");
  }

  @Test void anotherProvidersObligationsNeverCount() {
    var anthropicOnly = anthropicMet(List.of(comparison("total-prompt.v1", "PASS")));
    assertThat(unmet(OPENAI_ENABLED, anthropicOnly, Map.of("OPENAI_API_KEY", "set", "ANTHROPIC_API_KEY", "set")::get))
        .as("Anthropic terms and comparisons do not let OpenAI answer").containsExactly(
            "VENDOR_TERMS_NOT_VALIDATED:OPENAI",
            "NO_SHADOW_COMPARISON:OPENAI:EXTRACTION:total-prompt.v1:total-schema.v1");
    var openAiTermsAnthropicComparison = new ModelObligations(List.of(executed(OPENAI)),
        List.of(comparison(ANTHROPIC, "gpt-6-astra", "total-prompt.v1", "PASS")));
    assertThat(unmet(OPENAI_ENABLED, openAiTermsAnthropicComparison, OPENAI_CREDENTIALS))
        .as("a comparison is bound to its provider").containsExactly(
            "NO_SHADOW_COMPARISON:OPENAI:EXTRACTION:total-prompt.v1:total-schema.v1");
    assertThat(unmet(OPENAI_ENABLED, new ModelObligations(List.of(executed(OPENAI)),
        List.of(comparison(OPENAI, "gpt-6-astra", "total-prompt.v1", "PASS"))), CREDENTIALS))
        .as("an Anthropic key is not an OpenAI credential").containsExactly("CREDENTIALS_MISSING:OPENAI");
  }

  @Test void aModelIsPricedOnlyUnderItsOwnProvider() {
    var claudeOnOpenAi = new ModelSettings(true, OPENAI, "claude-opus-5", "pilot-parameters.v1", "corpus.v1");
    assertThat(unmet(claudeOnOpenAi, new ModelObligations(List.of(executed(OPENAI)),
        List.of(comparison(OPENAI, "claude-opus-5", "total-prompt.v1", "PASS"))), OPENAI_CREDENTIALS))
        .containsExactly("MODEL_NOT_PRICED:OPENAI");
  }

  @Test void aRolloutWithoutAMatchingPassingComparisonIsRefused() {
    String missing = "NO_SHADOW_COMPARISON:ANTHROPIC:EXTRACTION:total-prompt.v1:total-schema.v1";
    assertThat(unmet(ENABLED, anthropicMet(List.of()), CREDENTIALS)).containsExactly(missing);
    assertThat(unmet(ENABLED, anthropicMet(List.of(comparison("total-prompt.v0", "PASS"))), CREDENTIALS))
        .as("a comparison of an earlier prompt does not cover a new one").containsExactly(missing);
    assertThat(unmet(ENABLED, anthropicMet(List.of(comparison("total-prompt.v1", "FAIL"))), CREDENTIALS))
        .as("a failed comparison is not a release").containsExactly(missing);
    var otherModel = new ModelSettings(true, ANTHROPIC, "claude-sonnet-5", "pilot-parameters.v1", "corpus.v1");
    assertThat(unmet(otherModel, anthropicMet(List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .as("changing the model needs its own comparison and price").contains(missing, "MODEL_NOT_PRICED:ANTHROPIC");
    var otherCorpus = new ModelSettings(true, ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "corpus.v2");
    assertThat(unmet(otherCorpus, anthropicMet(List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .containsExactly(missing);
  }

  @Test void anOpus5ComparisonDoesNotAdmitOpus55() {
    var defaults = new ModelSettings(true, ANTHROPIC, ANTHROPIC.defaultModel(), "pilot-parameters.v1", "corpus.v1");
    var oldComparison = anthropicMet(List.of(comparison(ANTHROPIC, "claude-opus-5", "total-prompt.v1", "PASS")));
    String missing = "NO_SHADOW_COMPARISON:ANTHROPIC:EXTRACTION:total-prompt.v1:total-schema.v1";
    assertThat(unmet(ENABLED, oldComparison, CREDENTIALS)).isEmpty();
    assertThat(unmet(defaults, oldComparison, CREDENTIALS)).containsExactly(missing);
    assertThatThrownBy(() -> new ModelRolloutGate(defaults, oldComparison, List.of(TestTasks.total()), CREDENTIALS))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining(missing);
    var ownComparison = anthropicMet(List.of(comparison(ANTHROPIC, "claude-opus-5-5", "total-prompt.v1", "PASS")));
    assertThat(unmet(defaults, ownComparison, CREDENTIALS)).isEmpty();
  }

  @Test void comparisonsNotProducedByTheEvaluationHarnessAreNotEvidence() {
    assertThatThrownBy(() -> new ShadowComparison("cmp", "EXTRACTION", new Candidate(ANTHROPIC, "claude-opus-5", "p", "s"),
        "baseline", "corpus.v1", "pilot-parameters.v1", "HELD_OUT", "hand-written", "0", Instant.now(), "PASS", "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ShadowComparison("cmp", "EXTRACTION", new Candidate(ANTHROPIC, "claude-opus-5", "p", "s"),
        "baseline", "corpus.v1", "pilot-parameters.v1", "DEVELOPMENT", ModelObligations.EVALUATION_HARNESS, "0",
        Instant.now(), "PASS", "r")).as("only the frozen held-out split counts").isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Candidate(null, "claude-opus-5", "p", "s"))
        .as("a comparison names its provider").isInstanceOf(IllegalArgumentException.class);
  }

  @Test void credentialsAndEndpointComeFromTheEnvironmentOnly() {
    var obligations = anthropicMet(List.of(comparison("total-prompt.v1", "PASS")));
    assertThat(unmet(ENABLED, obligations, name -> null)).containsExactly("CREDENTIALS_MISSING:ANTHROPIC");
    assertThat(unmet(ENABLED, obligations, Map.of("ANTHROPIC_API_KEY", "set", "ANTHROPIC_BASE_URL", "http://elsewhere")::get))
        .containsExactly("ENDPOINT_OVERRIDDEN:ANTHROPIC");
    var openAi = new ModelObligations(List.of(executed(OPENAI)),
        List.of(comparison(OPENAI, "gpt-6-astra", "total-prompt.v1", "PASS")));
    assertThat(unmet(OPENAI_ENABLED, openAi, Map.of("OPENAI_API_KEY", "set", "OPENAI_BASE_URL", "http://elsewhere")::get))
        .containsExactly("ENDPOINT_OVERRIDDEN:OPENAI");
    assertThat(unmet(OPENAI_ENABLED, openAi, Map.of("openai.apiKey", "set", "openai.baseUrl", "http://elsewhere")::get))
        .as("the SDK also reads system properties").containsExactly("ENDPOINT_OVERRIDDEN:OPENAI");
  }

  @Test void unvalidatedTermsBlockEvenWithEverythingElseInPlace() {
    var unsigned = new VendorTerms(ANTHROPIC, "Anthropic", "EXECUTED", "agreement-2026-01",
        new Term(true, "Section 4.2"), new Term(false, null), "counsel", Instant.now(), null);
    assertThat(unmet(ENABLED, new ModelObligations(List.of(unsigned), List.of(comparison("total-prompt.v1", "PASS"))), CREDENTIALS))
        .containsExactly("VENDOR_TERMS_NOT_VALIDATED:ANTHROPIC");
    assertThatThrownBy(() -> new ModelObligations(List.of(executed(ANTHROPIC), executed(ANTHROPIC)), List.of()))
        .as("one terms record per provider").isInstanceOf(IllegalArgumentException.class);
  }

  @Test void applicationStartupFailsWhenEitherProviderIsEnabledAgainstTheShippedRecords() {
    for (String provider : List.of("anthropic", "openai")) {
      new ApplicationContextRunner()
          .withBean(ObjectMapper.class, () -> JSON)
          .withUserConfiguration(ModelRolloutGate.class)
          .withPropertyValues("ai.model.enabled=true", "ai.model.provider=" + provider)
          .run(context -> assertThat(context).hasFailed()
              .getFailure().rootCause().hasMessageContaining("VENDOR_TERMS_NOT_VALIDATED:" + provider.toUpperCase()));
    }
    new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> JSON)
        .withUserConfiguration(ModelRolloutGate.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
