package com.dealanalyzer.expert;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpertReviewPacketTest {
  @TempDir Path temp;
  private final Path scenarios = Path.of("src/main/resources/expert-review/scenarios.v1.json");

  @Test void generationUsesShippedBoundariesAndIsStable() throws Exception {
    var first = ExpertReviewPacket.compute(scenarios);
    assertThat(first.hash()).isEqualTo(ExpertReviewPacket.compute(scenarios).hash());
    assertThat(first.rows()).hasSize(12);
    assertThat(first.rows()).allSatisfy(row -> assertThat(row.get(4)).isEqualTo("SYNTHETIC"));
    assertThat(first.rows().get(0).subList(11, 19)).containsExactly(
        "2990000", "3000000", "3010000", "3010000", "3214250", "3225000", "3235750", "3235750");
    assertThat(first.rows().get(2).get(14)).isEqualTo("2700000");
    assertThat(first.rows().get(3).get(18)).isEqualTo("2900000");
    assertThat(first.rows().get(4).get(19)).contains("OTD conversion unavailable");
    assertThat(first.rows().get(7).get(15)).isEqualTo("3851250");
    Path modified = temp.resolve("changed.json");
    Files.writeString(modified, Files.readString(scenarios) + "\n");
    assertThat(ExpertReviewPacket.compute(modified).hash()).isNotEqualTo(first.hash());
    Path output = temp.resolve("packet");
    ExpertReviewPacket.generate(scenarios, output);
    assertThat(Files.readString(output.resolve("packet.md"))).contains(first.hash(), "Market low / midpoint / high", "inputs_json");
    assertThat(ExpertReviewPacket.parseCsv(Files.readString(output.resolve("workbook.csv")))).hasSize(13);
    assertThat(Files.readAllBytes(output.resolve("scenarios.json"))).isEqualTo(Files.readAllBytes(scenarios));
    assertThatThrownBy(() -> ExpertReviewPacket.generate(scenarios, output)).isInstanceOf(FileAlreadyExistsException.class);
  }

  @Test void commandLinePacketIsStableAcrossJvmProcesses() throws Exception {
    for (String name : List.of("process-a", "process-b")) {
      Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("java.class.path"), ExpertReviewPacket.class.getName(), "generate",
          scenarios.toString(), temp.resolve(name).toString()).redirectErrorStream(true).start();
      boolean finished = process.waitFor(20, TimeUnit.SECONDS);
      if (!finished) process.destroyForcibly();
      assertThat(finished).isTrue();
      assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes())).isZero();
    }
    assertThat(Files.readAllBytes(temp.resolve("process-a/workbook.csv")))
        .isEqualTo(Files.readAllBytes(temp.resolve("process-b/workbook.csv")));
  }

  private List<List<String>> completed() throws Exception {
    List<List<String>> rows = new ArrayList<>();
    rows.add(ExpertReviewPacket.header());
    int index = 0;
    for (var fixed : ExpertReviewPacket.compute(scenarios).rows()) {
      List<String> row = new ArrayList<>(fixed);
      row.addAll(List.of(List.of("SOUND", "ADJUST", "UNSOUND").get(index++ % 3), "", "", "",
          "Recorded judgment, with \"quotes\"\nand a second line", "Expert Reviewer", "Consumer-side automotive experience", "2026-10-03"));
      rows.add(row);
    }
    return rows;
  }
  private void refuses(List<List<String>> rows, String reason) throws Exception {
    Path workbook = temp.resolve("completed.csv");
    Files.writeString(workbook, ExpertReviewPacket.csv(rows));
    Path output = temp.resolve("review");
    assertThatThrownBy(() -> ExpertReviewPacket.importReview(scenarios, workbook, output))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason);
    assertThat(output).doesNotExist();
  }

  @Test void completedReviewRecordsCountsAndPreservesSource() throws Exception {
    var rows = completed();
    rows.get(1).set(24, "2800000");
    Path workbook = temp.resolve("completed.csv");
    Files.writeString(workbook, ExpertReviewPacket.csv(rows));
    Path output = temp.resolve("record");
    ExpertReviewPacket.importReview(scenarios, workbook, output);
    var record = new ObjectMapper().readTree(Files.readString(output.resolve("review-record.json")));
    assertThat(record.path("counts").path("SOUND").asInt()).isEqualTo(4);
    assertThat(record.path("counts").path("ADJUST").asInt()).isEqualTo(4);
    assertThat(record.path("counts").path("UNSOUND").asInt()).isEqualTo(4);
    assertThat(record.path("readinessDecision").asText()).isEqualTo("UNCHANGED");
    assertThat(Files.readString(output.resolve("completed-workbook.csv"))).isEqualTo(Files.readString(workbook));
    assertThatThrownBy(() -> ExpertReviewPacket.importReview(scenarios, workbook, output)).isInstanceOf(FileAlreadyExistsException.class);
  }

  @Test void refusesEachMissingReviewFieldAndInvalidVerdict() throws Exception {
    for (String field : List.of("verdict", "rationale", "reviewer_name", "qualification_statement", "review_date")) {
      var rows = completed(); rows.get(1).set(rows.getFirst().indexOf(field), "");
      refuses(rows, field.equals("verdict") ? "verdict must" : "missing " + field);
    }
    var rows = completed(); rows.get(1).set(23, "APPROVED"); refuses(rows, "verdict must");
  }
  @Test void refusesEveryMalformedSuggestedValueAndInvalidDateOrIdentity() throws Exception {
    for (int column = 24; column <= 26; column++) {
      for (String value : List.of("1.23", "-1", "0", "abc", "9223372036854775808")) {
        var rows = completed(); rows.get(1).set(column, value); refuses(rows, "invalid suggested_");
      }
    }
    var rows = completed(); rows.get(1).set(30, "2026-02-30"); refuses(rows, "ISO calendar date");
    rows = completed(); rows.get(2).set(28, "Different reviewer"); refuses(rows, "inconsistent reviewer metadata");
  }
  @Test void refusesTamperingMissingDuplicateUnknownAndMalformedRows() throws Exception {
    for (int column = 0; column < 23; column++) {
      var rows = completed(); rows.get(1).set(column, "tampered"); refuses(rows, column == 3 ? "unknown scenario" : "mismatched");
    }
    var rows = completed(); rows.remove(1); refuses(rows, "missing row");
    rows = completed(); rows.add(new ArrayList<>(rows.get(1))); refuses(rows, "duplicate scenario");
    rows = completed(); rows.get(1).set(3, "unknown"); refuses(rows, "unknown scenario");
    rows = completed(); rows.get(1).removeLast(); refuses(rows, "wrong column count");
    rows = completed(); rows.getFirst().set(0, "wrong"); refuses(rows, "header");
    assertThatThrownBy(() -> ExpertReviewPacket.parseCsv("\"unclosed")).hasMessageContaining("Unclosed CSV quote");
    assertThatThrownBy(() -> ExpertReviewPacket.parseCsv("\"closed\"extra")).hasMessageContaining("Malformed CSV");
  }
  @Test void refusesOutputsInSourceTreesIncludingSymlinkParents() throws Exception {
    Path frontend = Files.createDirectory(temp.resolve("frontend"));
    Path backend = Files.createDirectory(temp.resolve("backend"));
    Path src = Files.createDirectory(backend.resolve("src"));
    for (Path protectedPath : List.of(frontend, src)) {
      assertThatThrownBy(() -> ExpertReviewPacket.generate(scenarios, protectedPath.resolve("packet")))
          .hasMessageContaining("outside backend/src and frontend");
    }
    Path link = temp.resolve("alias"); Files.createSymbolicLink(link, frontend);
    assertThatThrownBy(() -> ExpertReviewPacket.generate(scenarios, link.resolve("packet")))
        .hasMessageContaining("outside backend/src and frontend");
  }

  @Test void supportsPermissionedRealSnapshotsAndRejectsMissingProvenanceOrInvalidSets() throws Exception {
    ObjectMapper json = new ObjectMapper();
    var source = json.readTree(Files.readString(scenarios));
    var first = (com.fasterxml.jackson.databind.node.ObjectNode) source.path("scenarios").get(0);
    first.put("provenance", "PERMISSIONED_REAL");
    first.put("permissionReference", "permission-record-123");
    Path real = temp.resolve("real.json"); Files.writeString(real, json.writeValueAsString(source));
    assertThat(ExpertReviewPacket.compute(real).rows().getFirst().get(5)).isEqualTo("permission-record-123");
    first.remove("permissionReference"); Files.writeString(real, json.writeValueAsString(source));
    assertThatThrownBy(() -> ExpertReviewPacket.compute(real)).hasMessageContaining("permission reference");
    first.put("provenance", "UNLABELLED"); Files.writeString(real, json.writeValueAsString(source));
    assertThatThrownBy(() -> ExpertReviewPacket.compute(real)).hasMessageContaining("provenance");
    first.put("provenance", "SYNTHETIC"); first.put("marketHighMinor", 1);
    Files.writeString(real, json.writeValueAsString(source));
    assertThatThrownBy(() -> ExpertReviewPacket.compute(real)).hasMessageContaining("Invalid market range");
    first.put("marketHighMinor", 3010000); first.put("id", "wide");
    Files.writeString(real, json.writeValueAsString(source));
    assertThatThrownBy(() -> ExpertReviewPacket.compute(real)).hasMessageContaining("unique");
    ((com.fasterxml.jackson.databind.node.ArrayNode) source.path("scenarios")).remove(0);
    Files.writeString(real, json.writeValueAsString(source));
    assertThatThrownBy(() -> ExpertReviewPacket.compute(real)).hasMessageContaining("at least twelve");
  }
}
