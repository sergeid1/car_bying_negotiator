package com.dealanalyzer.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.LogLevel;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.CacheControlEphemeral;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The Anthropic Messages API behind {@link ModelGateway}. Exceptions and response bodies never leave
 * this class: each is reduced to an {@link ModelGateway.Outcome}, so no vendor text can reach logs,
 * telemetry or a Buyer.
 */
public final class AnthropicModelGateway implements ModelGateway {
  private final AnthropicClient client;

  public AnthropicModelGateway(AnthropicClient client) {
    this.client = client;
  }

  /**
   * Applies the settings every client must have: no SDK retries, because {@code ProviderMeter} owns
   * the time budget and a retried 429 would hide rate limiting; the meter's timeout; and SDK logging
   * off, because debug logging writes request bodies. Credentials come only from the builder, which in
   * production is {@code fromEnv()}; there is deliberately no property that can carry a key.
   */
  public static AnthropicClient configure(AnthropicOkHttpClient.Builder builder, Duration timeout) {
    return builder.maxRetries(0).timeout(timeout).logLevel(LogLevel.OFF).build();
  }

  /** Credentials and endpoint resolved from the environment (ANTHROPIC_API_KEY and friends). */
  public static AnthropicModelGateway fromEnvironment(Duration timeout) {
    return new AnthropicModelGateway(configure(AnthropicOkHttpClient.builder().fromEnv(), timeout));
  }

  @Override
  public ModelExchange send(ModelRequest request) {
    try {
      // Reading the response is inside the guard: the SDK validates fields lazily on access.
      return exchange(client.messages().create(params(request)));
    } catch (RateLimitException limited) {
      return ModelExchange.failed(Outcome.RATE_LIMITED);
    } catch (InternalServerException unavailable) {
      return ModelExchange.failed(Outcome.UNAVAILABLE);
    } catch (AnthropicServiceException rejected) {
      return ModelExchange.failed(rejected.statusCode() == 529 || rejected.statusCode() == 408
          ? Outcome.UNAVAILABLE : Outcome.REJECTED);
    } catch (AnthropicIoException io) {
      return ModelExchange.failed(timedOut(io) ? Outcome.TIMED_OUT : Outcome.UNAVAILABLE);
    } catch (AnthropicInvalidDataException malformed) {
      return ModelExchange.failed(Outcome.MALFORMED);
    } catch (AnthropicException other) {
      return ModelExchange.failed(Outcome.UNAVAILABLE);
    }
  }

  static MessageCreateParams params(ModelRequest request) {
    var schema = JsonOutputFormat.Schema.builder();
    request.outputSchema().forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
    // Adaptive thinking is the model default and is left on. Server-side refusal fallbacks are
    // deliberately not requested: a fallback would answer with a model that has no recorded
    // shadow comparison, so a refusal fails closed instead.
    var builder = MessageCreateParams.builder()
        .model(request.modelId())
        .maxTokens(request.maxOutputTokens())
        .system(UntrustedFraming.instructions(request.instructions()))
        .outputConfig(OutputConfig.builder()
            // Preserve Opus 5's default depth when using Opus 5.5, whose default effort is medium.
            .effort(OutputConfig.Effort.HIGH)
            .format(JsonOutputFormat.builder().schema(schema.build()).build())
            .build())
        .addUserMessageOfBlockParams(blocks(request.inputs()));
    if (request.cacheInstructions()) {
      builder.systemOfTextBlockParams(List.of(TextBlockParam.builder()
          .text(UntrustedFraming.instructions(request.instructions()))
          .cacheControl(CacheControlEphemeral.builder().build()).build()));
    }
    return builder.build();
  }

  static List<ContentBlockParam> blocks(List<UntrustedInput> inputs) {
    if (inputs.isEmpty()) throw new IllegalArgumentException("A model request needs input");
    List<ContentBlockParam> blocks = new ArrayList<>();
    for (UntrustedInput input : inputs) {
      switch (input) {
        case UntrustedInput.Text text -> blocks.add(ContentBlockParam.ofText(UntrustedFraming.text(text)));
        case UntrustedInput.PngImage image -> {
          blocks.add(ContentBlockParam.ofText(UntrustedFraming.imageMarker(image)));
          blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
              .source(Base64ImageSource.builder()
                  .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                  .data(Base64.getEncoder().encodeToString(image.png()))
                  .build())
              .build()));
        }
      }
    }
    return blocks;
  }

  private static ModelExchange exchange(Message response) {
    long input = response.usage().inputTokens()
        + response.usage().cacheCreationInputTokens().orElse(0L)
        + response.usage().cacheReadInputTokens().orElse(0L);
    long output = response.usage().outputTokens();
    String served = response.model().asString();
    StopReason stop = response.stopReason().orElse(null);
    Outcome outcome;
    if (StopReason.REFUSAL.equals(stop)) outcome = Outcome.REFUSED;
    else if (StopReason.MAX_TOKENS.equals(stop) || StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED.equals(stop)) outcome = Outcome.TRUNCATED;
    else if (StopReason.END_TURN.equals(stop)) outcome = Outcome.COMPLETED;
    else outcome = Outcome.MALFORMED;
    if (outcome != Outcome.COMPLETED) return new ModelExchange(outcome, served, null, input, output);
    StringBuilder text = new StringBuilder();
    response.content().forEach(block -> block.text().ifPresent(value -> text.append(value.text())));
    return new ModelExchange(outcome, served, text.toString(), input, output);
  }

  private static boolean timedOut(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InterruptedIOException) return true;
    }
    return false;
  }
}
