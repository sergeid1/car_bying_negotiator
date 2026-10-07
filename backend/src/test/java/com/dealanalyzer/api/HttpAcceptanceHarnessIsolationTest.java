package com.dealanalyzer.api;

import static com.dealanalyzer.api.HttpAcceptanceHarness.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;

class HttpAcceptanceHarnessIsolationTest {
  @TempDir Path directory;

  @Test
  void identicalDatabaseNamesInSeparateTestDirectoriesCannotShareState() throws Exception {
    Path firstDirectory = Files.createDirectory(directory.resolve("first"));
    Path secondDirectory = Files.createDirectory(directory.resolve("second"));
    var firstHarness = new HttpAcceptanceHarness(firstDirectory);
    String caseId;
    String token;
    try (var first = firstHarness.start("same-name", false);
        var second = new HttpAcceptanceHarness(secondDirectory).start("same-name", false,
            "--document.storage.path=" + secondDirectory.resolve("explicit-objects"))) {
      int firstPort = ((WebServerApplicationContext) first.context()).getWebServer().getPort();
      int secondPort = ((WebServerApplicationContext) second.context()).getWebServer().getPort();
      assertThat(firstPort).isPositive().isNotEqualTo(secondPort);
      assertThat(first.context().getEnvironment().getProperty("spring.datasource.url"))
          .isNotEqualTo(second.context().getEnvironment().getProperty("spring.datasource.url"));

      Path firstObjects = Path.of(first.context().getEnvironment().getRequiredProperty("document.storage.path"));
      Path secondObjects = Path.of(second.context().getEnvironment().getRequiredProperty("document.storage.path"));
      assertThat(firstObjects.getParent()).isEqualTo(firstDirectory);
      assertThat(secondObjects.getParent()).isEqualTo(secondDirectory);
      assertThat(secondObjects).isEqualTo(secondDirectory.resolve("explicit-objects"));
      assertThat(firstObjects).isNotEqualTo(secondObjects);
      Files.createDirectories(firstObjects);
      Files.writeString(firstObjects.resolve("isolation-marker"), "first application only");
      assertThat(secondObjects.resolve("isolation-marker")).doesNotExist();

      var created = post(first.client(), "/api/v1/cases", CasePortabilityAcceptanceTest.buyingCase());
      caseId = created.at("/case/id").asText();
      token = created.path("accessToken").asText();
      int secondStatus = second.client().get().uri("/api/v1/cases/" + caseId)
          .header("Case-Access-Token", token)
          .exchange((request, response) -> response.getStatusCode().value());
      assertThat(secondStatus).isEqualTo(404);
    }
    try (var restarted = firstHarness.start("same-name", false)) {
      assertThat(get(restarted.client(), "/api/v1/cases/" + caseId, token).path("id").asText())
          .isEqualTo(caseId);
      Path objects = Path.of(restarted.context().getEnvironment().getRequiredProperty("document.storage.path"));
      assertThat(objects.resolve("isolation-marker")).hasContent("first application only");
    }
  }
}
