package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The acquisition channel is measurement data only. Analysis, reports and pricing must never read
 * it, so the channel may be named only by intake, deletion, export, and pilot measurement code.
 */
class AcquisitionChannelIsolationTest {
  private static final Path SOURCES = Path.of("src/main/java/com/dealanalyzer");
  private static final Pattern CHANNEL = Pattern.compile(
      "acquisition_channel|acquisitionChannel|AcquisitionChannels|referral_disclosure|referralDisclosure|request\\.channel\\(\\)");
  private static final Set<String> ALLOWED = Set.of(
      "buyingcase/CreateBuyingCaseRequest.java",
      "buyingcase/EphemeralBuyingCaseService.java",
      "buyingcase/persistence/EphemeralCasePersistence.java",
      "buyingcase/persistence/CaseGraphDeletionPlan.java",
      "buyingcase/persistence/CaseExportProjection.java",
      "pilot/AcquisitionChannels.java",
      "pilot/PilotController.java",
      "pilot/PilotMetrics.java");

  @Test void onlyIntakeDeletionExportAndPilotMeasurementNameTheChannel() throws IOException {
    List<String> readers;
    try (Stream<Path> files = Files.walk(SOURCES)) {
      readers = files.filter(file -> file.toString().endsWith(".java"))
          .filter(file -> {
            try { return CHANNEL.matcher(Files.readString(file, StandardCharsets.UTF_8)).find(); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
          })
          .map(file -> SOURCES.relativize(file).toString().replace('\\', '/'))
          .sorted().toList();
    }
    assertThat(readers).isSubsetOf(ALLOWED).contains("pilot/PilotMetrics.java", "buyingcase/persistence/CaseGraphDeletionPlan.java");
  }

  @Test void analysisReportAndPricingPackagesHaveNoPathToTheChannel() throws IOException {
    for (String area : List.of("report", "economics", "market", "plan", "negotiation", "finance", "review",
        "explanation", "extraction", "verification", "ai", "listing", "vehicle", "document")) {
      try (Stream<Path> files = Files.walk(SOURCES.resolve(area))) {
        for (Path file : files.filter(value -> value.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file, StandardCharsets.UTF_8);
          assertThat(source).as(file.toString()).doesNotContain("acquisition", "AcquisitionChannels", "referralDisclosure");
        }
      }
    }
  }

  @Test void theCaseEntityDoesNotMapTheChannel() throws IOException {
    // Writing the column natively keeps it out of every entity read path.
    assertThat(Files.readString(SOURCES.resolve("buyingcase/persistence/AnalysisCaseEntity.java"), StandardCharsets.UTF_8))
        .doesNotContain("acquisition");
  }
}
