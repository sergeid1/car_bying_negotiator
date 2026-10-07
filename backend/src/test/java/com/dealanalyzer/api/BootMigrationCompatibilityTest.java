package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;

/** Guard the technology auto-configurations split into separate modules in Boot 4. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:h2:mem:boot-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
    "case.access.pepper=boot-migration-test-pepper-at-least-thirty-two-bytes",
    "analysis.worker.enabled=false", "document.worker.enabled=false",
    "spring.mail.host=127.0.0.1"})
class BootMigrationCompatibilityTest {
  @Autowired ObjectMapper json;
  @Autowired JavaMailSender mail;
  @Autowired Validator validator;
  @Autowired Flyway flyway;
  @Autowired DataSource dataSource;

  @Test void unprocessableErrorBodyKeepsItsExistingTitleAndPrivacyBoundary() {
    var response = new PrivacyExceptionHandler().unexpected(
        new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "private Buyer value"));
    var problem = (org.springframework.http.ProblemDetail) response.getBody();
    assertThat(problem.getTitle()).isEqualTo("Unprocessable Entity");
    assertThat(problem.getStatus()).isEqualTo(422);
    assertThat(problem.getDetail()).isEqualTo("The request could not be processed.");
    assertThat(problem.getInstance().toString()).isEqualTo("/api");
    assertThat(problem.getProperties()).containsEntry("failureCategory", "BUYER_INPUT");
  }

  @Test void mailAndValidationRemainAutoConfigured() {
    assertThat(mail.createMimeMessage()).isNotNull();
    assertThat(validator.validate(new RequiredInput(""))).singleElement()
        .satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("value"));
    assertThat(validator.validate(new RequiredInput("present"))).isEmpty();
  }

  @Test void startupActuallyAppliesMigrationsWithoutHibernateGeneratingTables() throws Exception {
    int expected;
    try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
      expected = Math.toIntExact(files.filter(Files::isRegularFile)
          .filter(file -> file.getFileName().toString().matches("V[0-9]+__.+\\.sql"))
          .count());
    }
    assertThat(expected).isPositive();
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied()).isNotEmpty().hasSize(expected);
    try (var connection = dataSource.getConnection(); var query = connection.createStatement();
        var rows = query.executeQuery(
            "select count(*) from flyway_schema_history where success = true and version is not null")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(expected);
    }
  }

  @Test void legacyJsonStillReadsAndSerializesWithTheSameTypesAndTimestampPrecision() throws Exception {
    // Frozen JSON representation used before the upgrade: decimal scale, enum, null and ISO timestamp.
    String stored = """
        {
          "amount" : 123.40,
          "precision" : "EXACT",
          "createdAt" : "2026-09-17T22:06:00.456822Z",
          "optional" : null
        }""";
    var value = json.readValue(stored, LegacySnapshot.class);
    assertThat(value.amount()).isEqualTo(new BigDecimal("123.40"));
    assertThat(value.createdAt()).isEqualTo(Instant.parse("2026-09-17T22:06:00.456822Z"));
    assertThat(json.writeValueAsString(value)).isEqualTo(stored);
    assertThat(json.writeValueAsString(new LegacySnapshot(value.amount(), value.precision(),
        Instant.parse("2026-09-17T22:06:00.456821973Z"), null))).isEqualTo(stored);
    assertThat(json.readTree(stored).path("optional").isNull()).isTrue();
  }

  record RequiredInput(@NotBlank String value) {}
  enum Precision { EXACT }
  record LegacySnapshot(BigDecimal amount, Precision precision, Instant createdAt, String optional) {}
}
