package com.dealanalyzer.listing.capture;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelProvider;
import com.dealanalyzer.ai.ModelRolloutGate;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.extraction.ExtractionProviderConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The listing capture reader follows {@code extraction.provider}: the deterministic text reader by default, or
 * the Claude reader with its own prompt version. Selecting Claude registers the listing task, so the rollout
 * gate requires that task's own shadow comparison too; there is no fallback between the two.
 */
@Configuration(proxyBeanMethods = false)
public class ListingCaptureProviderConfiguration {
  @Bean
  @ConditionalOnProperty(name = ExtractionProviderConfiguration.PROPERTY, havingValue = "claude")
  public ModelTask<ClaudeListingCaptureProvider.Output> listingCaptureModelTask() {
    return ClaudeListingCaptureProvider.task();
  }

  @Bean
  public ListingCaptureProvider listingCaptureProvider(Environment environment,
      ObjectProvider<ModelRolloutGate> gate, ObjectProvider<ModelHarness> harness) {
    return switch (ExtractionProviderConfiguration.choice(environment)) {
      case DETERMINISTIC -> new DeterministicListingCaptureProvider();
      case CLAUDE -> {
        gate.getObject().requireModelPath(ModelProvider.ANTHROPIC, ClaudeListingCaptureProvider.task());
        yield new ClaudeListingCaptureProvider(harness.getObject());
      }
    };
  }
}
