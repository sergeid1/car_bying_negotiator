package com.dealanalyzer.extraction;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelProvider;
import com.dealanalyzer.ai.ModelRolloutGate;
import com.dealanalyzer.ai.ModelTask;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Selects the one deal-terms extraction provider with {@code extraction.provider}: {@code deterministic}
 * (the default) or {@code claude}. There is no fallback between them. Selecting {@code claude} registers
 * its model task, so the rollout gate requires that task's shadow comparison, and the application
 * refuses to start until every obligation of the Claude path holds, even with model paths disabled.
 */
@Configuration(proxyBeanMethods = false)
public class ExtractionProviderConfiguration {
  public static final String PROPERTY = "extraction.provider";

  public enum Choice { DETERMINISTIC, CLAUDE }

  public static Choice choice(Environment environment) {
    String value = environment.getProperty(PROPERTY, "deterministic");
    try {
      return Choice.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalStateException(PROPERTY + " must be one of deterministic, claude");
    }
  }

  @Bean
  @ConditionalOnProperty(name = PROPERTY, havingValue = "claude")
  public ModelTask<ClaudeDealTermsExtractionProvider.Output> dealTermsExtractionModelTask() {
    return ClaudeDealTermsExtractionProvider.task();
  }

  @Bean
  public DealTermsExtractionProvider dealTermsExtractionProvider(Environment environment,
      ObjectProvider<ModelRolloutGate> gate, ObjectProvider<ModelHarness> harness) {
    return switch (choice(environment)) {
      case DETERMINISTIC -> new DeterministicDealerDocumentProvider();
      case CLAUDE -> {
        ModelRolloutGate rollout = gate.getObject();
        rollout.requireModelPath(ModelProvider.ANTHROPIC, ClaudeDealTermsExtractionProvider.task());
        yield new ClaudeDealTermsExtractionProvider(harness.getObject(), rollout.settings().modelId(),
            environment.getProperty("extraction.material-confidence-threshold", Double.class, 0.85));
      }
    };
  }
}
