package com.dealanalyzer.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.LogLevel;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.ResponsesModel;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * The OpenAI Responses API behind {@link ModelGateway}, with the same guarantees as
 * {@link AnthropicModelGateway}: the same instructions and framing, a strict JSON-schema output, no
 * tools, and every exception or response body reduced to an {@link ModelGateway.Outcome}.
 */
public final class OpenAiModelGateway implements ModelGateway {
  /** Structured-output names must match {@code [a-zA-Z0-9_-]+}; the name carries no meaning. */
  static final String SCHEMA_NAME = "task_output";

  private final OpenAIClient client;

  public OpenAiModelGateway(OpenAIClient client) {
    this.client = client;
  }

  /**
   * The settings every client must have: no SDK retries, because {@code ProviderMeter} owns the time
   * budget and a retried 429 would hide rate limiting; the meter's timeout; and SDK logging off,
   * because debug logging writes request bodies. Credentials come only from the builder, which in
   * production is {@code fromEnv()} (OPENAI_API_KEY); no Spring property can carry a key.
   */
  public static OpenAIClient configure(OpenAIOkHttpClient.Builder builder, Duration timeout) {
    return builder.maxRetries(0).timeout(timeout).logLevel(LogLevel.OFF).build();
  }

  public static OpenAiModelGateway fromEnvironment(Duration timeout) {
    return new OpenAiModelGateway(configure(OpenAIOkHttpClient.builder().fromEnv(), timeout));
  }

  @Override
  public ModelExchange send(ModelRequest request) {
    try {
      // Reading the response is inside the guard: the SDK validates fields lazily on access.
      return exchange(client.responses().create(params(request)));
    } catch (RateLimitException limited) {
      return ModelExchange.failed(Outcome.RATE_LIMITED);
    } catch (InternalServerException unavailable) {
      return ModelExchange.failed(Outcome.UNAVAILABLE);
    } catch (OpenAIServiceException rejected) {
      return ModelExchange.failed(rejected.statusCode() == 408 ? Outcome.UNAVAILABLE : Outcome.REJECTED);
    } catch (OpenAIIoException io) {
      return ModelExchange.failed(timedOut(io) ? Outcome.TIMED_OUT : Outcome.UNAVAILABLE);
    } catch (OpenAIInvalidDataException malformed) {
      return ModelExchange.failed(Outcome.MALFORMED);
    } catch (OpenAIException other) {
      return ModelExchange.failed(Outcome.UNAVAILABLE);
    }
  }

  static ResponseCreateParams params(ModelRequest request) {
    var schema = ResponseFormatTextJsonSchemaConfig.Schema.builder();
    request.outputSchema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
    var message = ResponseInputItem.Message.builder().role(ResponseInputItem.Message.Role.USER);
    if (request.inputs().isEmpty()) throw new IllegalArgumentException("A model request needs input");
    for (UntrustedInput input : request.inputs()) {
      switch (input) {
        case UntrustedInput.Text text -> message.addInputTextContent(UntrustedFraming.text(text));
        case UntrustedInput.PngImage image -> {
          message.addInputTextContent(UntrustedFraming.imageMarker(image));
          message.addContent(ResponseInputImage.builder()
              .detail(ResponseInputImage.Detail.HIGH)
              .imageUrl("data:image/png;base64," + Base64.getEncoder().encodeToString(image.png()))
              .build());
        }
      }
    }
    // store=false: the Responses API otherwise retains the response, and retention is governed by
    // the vendor terms, not by vendor defaults. No tools are offered.
    return ResponseCreateParams.builder()
        .model(request.modelId())
        .instructions(UntrustedFraming.instructions(request.instructions()))
        .inputOfResponse(List.of(ResponseInputItem.ofMessage(message.build())))
        .text(ResponseTextConfig.builder()
            .format(ResponseFormatTextJsonSchemaConfig.builder()
                .name(SCHEMA_NAME).strict(true).schema(schema.build()).build())
            .build())
        .maxOutputTokens(request.maxOutputTokens())
        .store(false)
        .build();
  }

  private static ModelExchange exchange(Response response) {
    long input = response.usage().map(usage -> usage.inputTokens()).orElse(0L);
    long output = response.usage().map(usage -> usage.outputTokens()).orElse(0L);
    String served = served(response.model());
    ResponseStatus status = response.status().orElse(null);
    if (ResponseStatus.INCOMPLETE.equals(status)) {
      String reason = response.incompleteDetails().flatMap(details -> details.reason())
          .map(value -> value.asString()).orElse("");
      Outcome outcome = "content_filter".equals(reason) ? Outcome.REFUSED : Outcome.TRUNCATED;
      return new ModelExchange(outcome, served, null, input, output);
    }
    if (ResponseStatus.FAILED.equals(status)) return new ModelExchange(Outcome.UNAVAILABLE, served, null, input, output);
    if (!ResponseStatus.COMPLETED.equals(status)) return new ModelExchange(Outcome.MALFORMED, served, null, input, output);
    StringBuilder text = new StringBuilder();
    boolean refused = false;
    for (ResponseOutputItem item : response.output()) {
      if (item.isReasoning()) continue;
      if (!item.isMessage()) return new ModelExchange(Outcome.MALFORMED, served, null, input, output);
      for (ResponseOutputMessage.Content content : item.asMessage().content()) {
        if (content.isRefusal()) refused = true;
        content.outputText().ifPresent(value -> text.append(value.text()));
      }
    }
    if (refused) return new ModelExchange(Outcome.REFUSED, served, null, input, output);
    return new ModelExchange(Outcome.COMPLETED, served, text.toString(), input, output);
  }

  private static String served(ResponsesModel model) {
    if (model.isString()) return model.asString();
    if (model.isChat()) return model.asChat().asString();
    return model.asOnly().asString();
  }

  private static boolean timedOut(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedIOException) return true;
    }
    return false;
  }
}
