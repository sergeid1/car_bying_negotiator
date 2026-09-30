package com.dealanalyzer.ai;

import java.util.List;
import java.util.Locale;

/**
 * The model vendors the harness can call. Exactly one is configured per deployment, and there is no
 * fallback between them: a provider without its own terms and shadow comparison never answers.
 *
 * @param credentials environment variables or system properties the vendor SDK reads a credential from
 * @param endpointOverrides settings that would redirect the SDK away from the vendor's own endpoint
 */
public enum ModelProvider {
  ANTHROPIC("anthropic-messages-api", "claude-opus-5",
      List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "anthropic.apiKey", "anthropic.authToken"),
      List.of("ANTHROPIC_BASE_URL", "anthropic.baseUrl")),
  OPENAI("openai-responses-api", "gpt-6-astra",
      List.of("OPENAI_API_KEY", "openai.apiKey"),
      List.of("OPENAI_BASE_URL", "openai.baseUrl"));

  private final String api;
  private final String defaultModel;
  private final List<String> credentials;
  private final List<String> endpointOverrides;

  ModelProvider(String api, String defaultModel, List<String> credentials, List<String> endpointOverrides) {
    this.api = api;
    this.defaultModel = defaultModel;
    this.credentials = credentials;
    this.endpointOverrides = endpointOverrides;
  }

  /** The API recorded as the provider version on usage rows. */
  public String api() { return api; }

  public String defaultModel() { return defaultModel; }

  public List<String> credentials() { return credentials; }

  public List<String> endpointOverrides() { return endpointOverrides; }

  public static ModelProvider fromSetting(String value) {
    try {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException unknown) {
      throw new IllegalStateException("ai.model.provider must be one of anthropic, openai");
    }
  }
}
