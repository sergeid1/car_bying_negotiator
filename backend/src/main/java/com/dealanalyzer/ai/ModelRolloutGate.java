package com.dealanalyzer.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The configuration and release check for model-backed paths. With {@code ai.model.enabled=true} the
 * application refuses to start, in every profile, until each obligation holds for the configured
 * provider and model: validated vendor retention and training terms, a frozen evaluation set, a
 * priced model, credentials in the environment, the vendor's own endpoint, and a passing shadow
 * comparison for the exact provider, model, prompt, schema, threshold-set and evaluation-set
 * versions of every registered task. Another provider's obligations never count.
 */
@Component
public class ModelRolloutGate {
  private final ModelSettings settings;
  private final ModelObligations obligations;

  @Autowired
  ModelRolloutGate(Environment environment, ObjectMapper json, ObjectProvider<ModelTask<?>> tasks) {
    this(ModelSettings.from(environment), ModelObligations.shipped(json), tasks.orderedStream().toList(),
        ModelRolloutGate::environmentOrSystemProperty);
  }

  ModelRolloutGate(ModelSettings settings, ModelObligations obligations, List<ModelTask<?>> tasks,
      UnaryOperator<String> environment) {
    this.settings = settings;
    this.obligations = obligations;
    if (settings.enabled()) {
      List<String> unmet = unmet(settings, obligations, tasks, environment);
      if (!unmet.isEmpty()) {
        throw new IllegalStateException(
            "Model-backed paths are enabled while obligations are unmet: " + String.join(", ", unmet));
      }
    }
  }

  public ModelSettings settings() { return settings; }

  public ModelObligations obligations() { return obligations; }

  /** Reason codes only; no credential, endpoint or record content is ever included. */
  public static List<String> unmet(ModelSettings settings, ModelObligations obligations,
      List<ModelTask<?>> tasks, UnaryOperator<String> environment) {
    List<String> unmet = new ArrayList<>();
    ModelProvider provider = settings.provider();
    if (!obligations.termsFor(provider).validated()) unmet.add("VENDOR_TERMS_NOT_VALIDATED:" + provider);
    if (ModelSettings.NO_EVALUATION_SET.equals(settings.evaluationSetVersion())) unmet.add("NO_FROZEN_EVALUATION_SET");
    if (!ModelPricing.known(provider, settings.modelId())) unmet.add("MODEL_NOT_PRICED:" + provider);
    if (provider.credentials().stream().allMatch(name -> blank(environment.apply(name)))) {
      unmet.add("CREDENTIALS_MISSING:" + provider);
    }
    // Terms are validated for the vendor's endpoint; a redirected endpoint is a different processor.
    if (provider.endpointOverrides().stream().anyMatch(name -> !blank(environment.apply(name)))) {
      unmet.add("ENDPOINT_OVERRIDDEN:" + provider);
    }
    for (ModelTask<?> task : tasks) {
      ModelProvenance provenance = provenance(settings, task);
      if (obligations.comparisons().stream().noneMatch(c -> c.covers(task.operation().name(), provenance))) {
        unmet.add("NO_SHADOW_COMPARISON:" + provider + ":" + task.operation() + ":" + task.promptVersion() + ":" + task.schemaVersion());
      }
    }
    return unmet;
  }

  static ModelProvenance provenance(ModelSettings settings, ModelTask<?> task) {
    return new ModelProvenance(settings.provider(), settings.modelId(), task.promptVersion(), task.schemaVersion(),
        settings.thresholdSetVersion(), settings.evaluationSetVersion());
  }

  /** What the usage record states about the terms a call ran under. */
  String license() {
    var terms = obligations.termsFor(settings.provider());
    return terms.validated() ? terms.agreementReference() : "VENDOR_TERMS_UNVALIDATED";
  }

  /** The SDKs read credentials and endpoints from either source, so the gate checks both. */
  static String environmentOrSystemProperty(String name) {
    String value = System.getenv(name);
    return value != null ? value : System.getProperty(name);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
