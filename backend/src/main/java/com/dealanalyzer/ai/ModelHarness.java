package com.dealanalyzer.ai;

import com.dealanalyzer.ai.ModelGateway.ModelExchange;
import com.dealanalyzer.ai.ModelGateway.ModelRequest;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ModelUsageScope;
import com.dealanalyzer.operations.ProviderMeter.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The single path for model calls in the request path. It builds the request only from the
 * code-owned task and configuration, routes the call through {@link ProviderMeter}, validates the
 * output against the task schema and parser, and either returns a complete value with its
 * provenance or throws {@link ProviderMeter.Failure}. It never persists, repairs or logs anything;
 * a caller that receives a failure has nothing to store.
 *
 * <p>Outcomes map onto the existing status taxonomy: rate limiting, timeouts and vendor outages are
 * {@code PROVIDER_UNAVAILABLE}; refusals and rejected requests are {@code PROVIDER_FAILURE};
 * truncated, malformed, off-schema, unparseable output, or output from a model other than the one
 * configured, is {@code CONTRACT_FAILURE}. With model paths disabled every call is
 * {@code PROVIDER_UNAVAILABLE} without contacting anyone.
 */
@Service
public class ModelHarness {
  private final ProviderMeter meter;
  private final ModelSettings settings;
  private final ModelGateway gateway;
  private final String license;
  private final ObjectMapper json;

  @Autowired
  ModelHarness(ProviderMeter meter, ModelRolloutGate gate, ObjectMapper json) {
    // The gate has already refused startup if enabled with an unmet obligation.
    this(meter, gate.settings(),
        gate.settings().enabled() ? gateway(gate.settings().provider(), meter.timeout()) : null,
        gate.license(), json);
  }

  /** One gateway for the configured provider; there is deliberately no second one to fall back to. */
  static ModelGateway gateway(ModelProvider provider, java.time.Duration timeout) {
    return switch (provider) {
      case ANTHROPIC -> AnthropicModelGateway.fromEnvironment(timeout);
      case OPENAI -> OpenAiModelGateway.fromEnvironment(timeout);
    };
  }

  /** @param gateway null when model paths are disabled */
  public ModelHarness(ProviderMeter meter, ModelSettings settings, ModelGateway gateway, String license,
      ObjectMapper json) {
    this.meter = meter;
    this.settings = settings;
    this.gateway = settings.enabled() ? Objects.requireNonNull(gateway) : null;
    this.license = license;
    this.json = json;
  }

  public boolean available() {
    return gateway != null;
  }

  public <T> ModelResult<T> run(String caseId, ModelTask<T> task, List<UntrustedInput> inputs, long units) {
    return run(caseId, task, inputs, units, value -> {});
  }

  /**
   * @param accept a check of the parsed value against this request, such as evidence that must cite
   *     a page the document has. Throwing rejects the output as a {@code CONTRACT_FAILURE}, metered as
   *     one, exactly like output the schema or parser rejects; it never repairs the value.
   */
  public <T> ModelResult<T> run(String caseId, ModelTask<T> task, List<UntrustedInput> inputs, long units,
      Consumer<? super T> accept) {
    return runScoped(ModelUsageScope.forCase(caseId), task, inputs, units, accept);
  }

  public <T> ModelResult<T> runScoped(ModelUsageScope scope, ModelTask<T> task, List<UntrustedInput> inputs, long units) {
    return runScoped(scope, task, inputs, units, value -> {});
  }

  public <T> ModelResult<T> runScoped(ModelUsageScope scope, ModelTask<T> task, List<UntrustedInput> inputs, long units,
      Consumer<? super T> accept) {
    Objects.requireNonNull(accept);
    if (gateway == null) throw new ProviderMeter.Failure(Status.PROVIDER_UNAVAILABLE);
    ModelProvenance provenance = ModelRolloutGate.provenance(settings, task);
    ModelRequest request = new ModelRequest(
        settings.modelId(), task.instructions(), task.outputSchema(), task.maxOutputTokens(), inputs,
        task.cacheInstructions());
    Attempt<T> attempt = meter.callModelScoped(scope, task.operation(), provenance, license, units,
        () -> attempt(task, accept, gateway.send(request)), Attempt::status, Attempt::usage);
    if (attempt.status() != Status.SUCCESS) throw new ProviderMeter.Failure(attempt.status());
    return new ModelResult<>(attempt.value(), provenance);
  }

  private <T> Attempt<T> attempt(ModelTask<T> task, Consumer<? super T> accept, ModelExchange exchange) {
    var usage = new ProviderMeter.ModelUsage(exchange.inputTokens(), exchange.outputTokens(),
        ModelPricing.costUsd(settings.provider(), settings.modelId(), exchange.inputTokens(), exchange.outputTokens()).orElse(null));
    Status status = switch (exchange.outcome()) {
      case COMPLETED -> Status.SUCCESS;
      case RATE_LIMITED, TIMED_OUT, UNAVAILABLE -> Status.PROVIDER_UNAVAILABLE;
      case REFUSED, REJECTED -> Status.PROVIDER_FAILURE;
      case TRUNCATED, MALFORMED -> Status.CONTRACT_FAILURE;
    };
    if (status != Status.SUCCESS) return new Attempt<>(null, status, usage);
    if (!settings.modelId().equals(exchange.servedModel())) return new Attempt<>(null, Status.CONTRACT_FAILURE, usage);
    try {
      JsonNode output = json.readTree(exchange.output());
      if (output == null || !output.isObject()) return new Attempt<>(null, Status.CONTRACT_FAILURE, usage);
      OutputSchemaValidator.validate(task.outputSchema(), output);
      T value = task.parse().apply(output);
      if (value == null) return new Attempt<>(null, Status.CONTRACT_FAILURE, usage);
      accept.accept(value);
      return new Attempt<>(value, Status.SUCCESS, usage);
    } catch (Exception rejected) {
      // The reason is deliberately dropped: it may quote the output.
      return new Attempt<>(null, Status.CONTRACT_FAILURE, usage);
    }
  }

  private record Attempt<T>(T value, Status status, ProviderMeter.ModelUsage usage) {}
}
