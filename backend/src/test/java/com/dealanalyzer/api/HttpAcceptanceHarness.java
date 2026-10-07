package com.dealanalyzer.api;

import com.dealanalyzer.DealAnalyzerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

final class HttpAcceptanceHarness {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path temporaryDirectory;

  HttpAcceptanceHarness(Path temporaryDirectory) {
    this.temporaryDirectory = temporaryDirectory;
  }

  RunningApplication start(String databaseName) {
    return start(databaseName, true);
  }

  RunningApplication start(String databaseName, boolean workerEnabled) {
    return start(databaseName, workerEnabled, new String[0]);
  }

  RunningApplication start(
      String databaseName, boolean workerEnabled, String... additionalProperties) {
    return startWithConfiguration(
        databaseName, workerEnabled, DealAnalyzerApplication.class, additionalProperties);
  }

  RunningApplication startWithConfiguration(
      String databaseName,
      boolean workerEnabled,
      Class<?> additionalConfiguration,
      String... additionalProperties) {
    // Initialize RestClient's observation classes before scheduled workers start; concurrent
    // first use can deadlock ObservationRegistry and NoopObservationRegistry initialization.
    RestClient.create("http://127.0.0.1");
    String dataSourceUrl = "jdbc:h2:file:"
        + temporaryDirectory.resolve(databaseName).toAbsolutePath()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    List<String> properties = new ArrayList<>(List.of(
        // Bind and call one loopback address. With a wildcard bind, another local process that binds
        // 127.0.0.1 on the same port number wins the connection and answers in place of the application.
        "--server.address=127.0.0.1",
        "--server.port=0",
        "--spring.datasource.url=" + dataSourceUrl,
        "--case.access.pepper=acceptance-test-pepper-with-at-least-32-bytes",
        "--document.malware-scanner=test",
        "--analysis.worker.enabled=" + workerEnabled,
        "--document.worker.enabled=" + workerEnabled,
        "--spring.task.execution.pool.core-size=1"));
    // Spring combines duplicate command-line values instead of replacing them. Preserve explicit
    // storage paths used by backup, deletion and key-rotation fixtures without adding a second value.
    if (Arrays.stream(additionalProperties).noneMatch(value -> value.startsWith("--document.storage.path="))) {
      properties.add("--document.storage.path="
          + temporaryDirectory.resolve(databaseName + "-objects").toAbsolutePath());
    }
    properties.addAll(Arrays.asList(additionalProperties));
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(DealAnalyzerApplication.class, additionalConfiguration)
            .web(WebApplicationType.SERVLET)
            .run(properties.toArray(String[]::new));
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new RunningApplication(context, RestClient.create("http://127.0.0.1:" + port));
  }

  static JsonNode post(RestClient client, String path, Object body) throws Exception {
    return post(client, path, body, UUID.randomUUID().toString());
  }

  static JsonNode post(RestClient client, String path, Object body, String idempotencyKey)
      throws Exception {
    String response = client.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .body(body)
        .retrieve()
        .body(String.class);
    return JSON.readTree(response);
  }

  static JsonNode post(
      RestClient client,
      String path,
      Object body,
      String idempotencyKey,
      String accessToken) throws Exception {
    String response = client.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", idempotencyKey)
        .header("Case-Access-Token", accessToken)
        .body(body)
        .retrieve()
        .body(String.class);
    return JSON.readTree(response);
  }

  static JsonNode awaitCompleted(RestClient client, String operationId) throws Exception {
    throw new UnsupportedOperationException("Case access token is required");
  }

  static JsonNode awaitCompleted(
      RestClient client, String operationId, String accessToken) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode operation;
    do {
      operation = get(client, "/api/v1/operations/" + operationId, accessToken);
      if (operation.get("stage").asText().equals("COMPLETE")) {
        return operation;
      }
      Thread.sleep(25);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Operation did not complete: " + operation);
  }

  static JsonNode get(RestClient client, String path) throws Exception {
    return JSON.readTree(client.get().uri(path).retrieve().body(String.class));
  }

  static JsonNode get(RestClient client, String path, String accessToken) throws Exception {
    return JSON.readTree(client.get()
        .uri(path)
        .header("Case-Access-Token", accessToken)
        .retrieve()
        .body(String.class));
  }

  record RunningApplication(ConfigurableApplicationContext context, RestClient client)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }
}
