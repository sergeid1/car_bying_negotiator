package com.dealanalyzer.ai;

import com.dealanalyzer.ai.ModelGateway.ModelExchange;
import com.dealanalyzer.ai.ModelGateway.ModelRequest;
import com.dealanalyzer.operations.ProviderMeter;
import com.dealanalyzer.operations.ProviderMeter.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
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
        gate.settings().enabled() ? AnthropicModelGateway.fromEnvironment(meter.timeout()) : null,
        gate.license(), json);
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
    if (gateway == null) throw new ProviderMeter.Failure(Status.PROVIDER_UNAVAILABLE);
    ModelProvenance provenance = ModelRolloutGate.provenance(settings, task);
    ModelRequest request = new ModelRequest(
        settings.modelId(), task.instructions(), task.outputSchema(), task.maxOutputTokens(), inputs);
    Attempt<T> attempt = meter.callModel(caseId, task.operation(), provenance, license, units,
        () -> attempt(task, gateway.send(request)), Attempt::status, Attempt::usage);
    if (attempt.status() != Status.SUCCESS) throw new ProviderMeter.Failure(attempt.status());
    return new ModelResult<>(attempt.value(), provenance);
  }

  private <T> Attempt<T> attempt(ModelTask<T> task, ModelExchange exchange) {
    var usage = new ProviderMeter.ModelUsage(exchange.inputTokens(), exchange.outputTokens(),
        ModelPricing.costUsd(settings.modelId(), exchange.inputTokens(), exchange.outputTokens()).orElse(null));
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
      return value == null ? new Attempt<>(null, Status.CONTRACT_FAILURE, usage) : new Attempt<>(value, Status.SUCCESS, usage);
    } catch (Exception rejected) {
      // The reason is deliberately dropped: it may quote the output.
      return new Attempt<>(null, Status.CONTRACT_FAILURE, usage);
    }
  }

  private record Attempt<T>(T value, Status status, ProviderMeter.ModelUsage usage) {}
}
