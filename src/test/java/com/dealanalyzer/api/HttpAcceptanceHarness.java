package com.dealanalyzer.api;

import com.dealanalyzer.DealAnalyzerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
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
    String dataSourceUrl = "jdbc:h2:file:"
        + temporaryDirectory.resolve(databaseName).toAbsolutePath()
        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(DealAnalyzerApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.datasource.url=" + dataSourceUrl,
                "--analysis.worker.enabled=" + workerEnabled,
                "--spring.task.execution.pool.core-size=1");
    int port = ((WebServerApplicationContext) context).getWebServer().getPort();
    return new RunningApplication(context, RestClient.create("http://localhost:" + port));
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

  static JsonNode awaitCompleted(RestClient client, String operationId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    JsonNode operation;
    do {
      operation = get(client, "/api/v1/operations/" + operationId);
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

  record RunningApplication(ConfigurableApplicationContext context, RestClient client)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }
}
