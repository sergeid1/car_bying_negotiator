package com.dealanalyzer.ai;

import java.util.List;
import java.util.Map;

/** Sends one fully built request to a model vendor and reports the outcome without throwing. */
public interface ModelGateway {
  ModelExchange send(ModelRequest request);

  record ModelRequest(
      String modelId,
      String instructions,
      Map<String, Object> outputSchema,
      int maxOutputTokens,
      List<UntrustedInput> inputs) {
    public ModelRequest {
      inputs = List.copyOf(inputs);
      outputSchema = Map.copyOf(outputSchema);
    }
  }

  enum Outcome {
    /** The model finished its turn; the output still has to pass the task's parser. */
    COMPLETED,
    REFUSED,
    /** Output stopped at the token or context limit, so it is incomplete by definition. */
    TRUNCATED,
    /** The vendor answered with something other than a finished structured turn. */
    MALFORMED,
    RATE_LIMITED,
    TIMED_OUT,
    UNAVAILABLE,
    /** The vendor rejected the request itself, such as bad credentials or an invalid request. */
    REJECTED
  }

  /**
   * What came back. Only {@code COMPLETED} carries output; token counts are present whenever the
   * vendor reported them, including refusals and truncations, so spend is metered either way.
   */
  record ModelExchange(Outcome outcome, String servedModel, String output, long inputTokens, long outputTokens) {
    public static ModelExchange failed(Outcome outcome) {
      return new ModelExchange(outcome, null, null, 0, 0);
    }
  }
}
