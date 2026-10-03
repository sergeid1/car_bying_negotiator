package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.ai.ModelObligations.Candidate;
import com.dealanalyzer.ai.ModelObligations.ShadowComparison;
import com.dealanalyzer.ai.ModelObligations.Term;
import com.dealanalyzer.ai.ModelObligations.VendorTerms;
import com.dealanalyzer.extraction.ClaudeDealTermsExtractionProvider;
import com.dealanalyzer.extraction.DealTermsExtractionProvider;
import com.dealanalyzer.extraction.ExtractionProviderConfiguration;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.RecordingUsageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code extraction.provider} selects one extraction provider. The Claude provider is refused at
 * startup until the rollout gate's obligations hold for its task, and no test here sends a request.
 */
class ExtractionProviderSelectionTest {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private static final String MISSING_COMPARISON = "NO_SHADOW_COMPARISON:ANTHROPIC:EXTRACTION:deal-terms-prompt.v2:deal-terms.v1";

  private static ApplicationContextRunner shipped() {
    return new ApplicationContextRunner()
        .withBean(ObjectMapper.class, () -> JSON)
        .withUserConfiguration(ModelRolloutGate.class, ExtractionProviderConfiguration.class);
  }

  @Test void theDeterministicProviderIsTheDefaultAndRegistersNoModelTask() {
    for (String[] properties : List.of(new String[0], new String[] {"extraction.provider=deterministic"})) {
      shipped().withPropertyValues(properties).run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context.getBean(DealTermsExtractionProvider.class).getClass().getSimpleName())
            .isEqualTo("DeterministicDealerDocumentProvider");
        assertThat(context.getBean(DealTermsExtractionProvider.class).selfMetered()).isFalse();
        assertThat(context.getBeansOfType(ModelTask.class)).isEmpty();
      });
    }
  }

  @Test void selectingClaudeRefusesStartupAndListsItsMissingShadowComparison() {
    shipped().withPropertyValues("extraction.provider=claude").run(context ->
        assertThat(context).hasFailed().getFailure().rootCause()
            .hasMessageContaining("MODEL_PATHS_DISABLED")
            .hasMessageContaining("VENDOR_TERMS_NOT_VALIDATED:ANTHROPIC")
            .hasMessageContaining(MISSING_COMPARISON));
    // With model paths on, the gate itself refuses, because selecting Claude registered the task.
    shipped().withPropertyValues("extraction.provider=claude", "ai.model.enabled=true").run(context ->
        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(MISSING_COMPARISON));
    shipped().withPropertyValues("extraction.provider=claude", "ai.model.enabled=true", "ai.model.provider=openai")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test void anUnknownProviderRefusesStartup() {
    shipped().withPropertyValues("extraction.provider=deterministic,claude").run(context ->
        assertThat(context).hasFailed().getFailure().rootCause()
            .hasMessageContaining("extraction.provider must be one of deterministic, claude"));
  }

  @Test void withEveryObligationMetClaudeIsSelectedAndNothingFallsBack() {
    var settings = new ModelSettings(true, ModelProvider.ANTHROPIC, "claude-opus-5", "pilot-parameters.v1", "corpus.v1");
    var comparison = new ShadowComparison("cmp-40", "EXTRACTION",
        new Candidate(ModelProvider.ANTHROPIC, "claude-opus-5", "deal-terms-prompt.v2", "deal-terms.v1"),
        "deterministic-dealer-layout.v1", "corpus.v1", "pilot-parameters.v1", "HELD_OUT",
        ModelObligations.EVALUATION_HARNESS, "0.1.0", Instant.parse("2026-09-02T00:00:00Z"), "PASS", "reports/cmp-40.json");
    var terms = new VendorTerms(ModelProvider.ANTHROPIC, "Anthropic", "EXECUTED", "agreement-1",
        new Term(true, "Section 4.2"), new Term(true, "Section 5.1"), "counsel", Instant.parse("2026-09-01T00:00:00Z"), null);
    var obligations = new ModelObligations(List.of(terms), List.of(comparison));
    var oldComparison = new ShadowComparison("cmp-old", "EXTRACTION",
        new Candidate(ModelProvider.ANTHROPIC, "claude-opus-5", "deal-terms-prompt.v1", "deal-terms.v1"),
        "deterministic-dealer-layout.v1", "corpus.v1", "pilot-parameters.v1", "HELD_OUT",
        ModelObligations.EVALUATION_HARNESS, "0.1.0", Instant.parse("2026-09-02T00:00:00Z"), "PASS", "reports/cmp-old.json");
    assertThat(ModelRolloutGate.unmet(settings, new ModelObligations(List.of(terms), List.of(oldComparison)),
        List.of(ClaudeDealTermsExtractionProvider.task()), Map.of("ANTHROPIC_API_KEY", "set")::get))
        .contains(MISSING_COMPARISON);
    var gate = new ModelRolloutGate(settings, obligations, List.of(ClaudeDealTermsExtractionProvider.task()),
        Map.of("ANTHROPIC_API_KEY", "set")::get);
    var meter = new ProviderMeter(new RecordingUsageStore(), Duration.ofSeconds(1), 1);
    ModelGateway refusingToConnect = request -> { throw new AssertionError("no request is sent"); };
    try {
      new ApplicationContextRunner()
          .withBean(ModelRolloutGate.class, () -> gate)
          .withBean(ModelHarness.class, () -> new ModelHarness(meter, settings, refusingToConnect, "agreement-1", JSON))
          .withUserConfiguration(ExtractionProviderConfiguration.class)
          .withPropertyValues("extraction.provider=claude")
          .run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DealTermsExtractionProvider.class))
                .isInstanceOf(ClaudeDealTermsExtractionProvider.class)
                .satisfies(provider -> assertThat(provider.selfMetered()).isTrue());
            assertThat(context.getBeansOfType(DealTermsExtractionProvider.class)).as("one provider, no fallback").hasSize(1);
            assertThat(context.getBeansOfType(ModelTask.class)).hasSize(1);
          });
    } finally {
      meter.close();
    }
  }
}
