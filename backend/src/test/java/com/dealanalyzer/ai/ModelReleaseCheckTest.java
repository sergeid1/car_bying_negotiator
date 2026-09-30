package com.dealanalyzer.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The release check over what ships. Every context start also runs {@link ModelRolloutGate}, so a
 * build that enables a model path without its obligations cannot start; this test states the same
 * properties against the shipped configuration and records directly.
 */
class ModelReleaseCheckTest {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private static StandardEnvironment shippedEnvironment() throws Exception {
    var properties = new Properties();
    try (InputStream source = ModelReleaseCheckTest.class.getResourceAsStream("/application.properties")) {
      properties.load(source);
    }
    var environment = new StandardEnvironment();
    environment.getPropertySources().addLast(new PropertiesPropertySource("shipped", properties));
    return environment;
  }

  @Test void theShippedRecordsAreWellFormed() {
    var shipped = ModelObligations.shipped(JSON);
    assertThat(shipped.vendorTerms().vendor()).isEqualTo("Anthropic");
    shipped.comparisons().forEach(comparison ->
        assertThat(comparison.executedBy()).isEqualTo(ModelObligations.EVALUATION_HARNESS));
  }

  @Test void theShippedConfigurationDoesNotEnableAModelPathWithUnmetObligations() throws Exception {
    var settings = ModelSettings.from(shippedEnvironment());
    assertThat(settings.modelId()).isEqualTo("claude-opus-5");
    var shipped = ModelObligations.shipped(JSON);
    if (settings.enabled()) {
      assertThat(ModelRolloutGate.unmet(settings, shipped, List.of(), System::getenv)).isEmpty();
    }
    if (!shipped.vendorTerms().validated()) {
      assertThat(settings.enabled()).as("vendor terms are unexecuted, so model paths stay off").isFalse();
    }
  }

  @Test void noCredentialCanBeConfiguredThroughApplicationProperties() throws Exception {
    String text;
    try (InputStream source = ModelReleaseCheckTest.class.getResourceAsStream("/application.properties")) {
      text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(text.lines().filter(line -> !line.startsWith("#")))
        .noneMatch(line -> line.toLowerCase().matches(".*(anthropic|ai\\.model).*(key|token|secret).*"))
        .noneMatch(line -> line.contains("sk-ant-"));
  }
}
