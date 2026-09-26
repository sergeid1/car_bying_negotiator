package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.*;

import com.dealanalyzer.pilot.PilotReadiness.Prerequisite;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PilotReadinessTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Test void datedSignOffSurvivesTheRecordSerializationBoundary() throws Exception {
    var record = new PilotReadiness.Record("pilot-readiness.v1", approveAll());
    var restored = JSON.readValue(JSON.writeValueAsBytes(record), PilotReadiness.Record.class);
    assertThatCode(() -> new PilotReadiness(restored.prerequisites(), true)).doesNotThrowAnyException();
    assertThat(restored.prerequisites().getFirst().approvedAt())
        .isEqualTo(Instant.parse("2026-09-26T00:00:00Z"));
  }

  @Test void everyNamedAutomatedCheckIsAnExecutableTestClass() throws Exception {
    for (var prerequisite : PilotReadiness.shipped(JSON).prerequisites()) {
      if (prerequisite.checks() == null) continue;
      for (String name : prerequisite.checks()) {
        Class<?> check = Class.forName(name.contains(".") ? name : "com.dealanalyzer.pilot." + name);
        assertThat(java.util.Arrays.stream(check.getDeclaredMethods())
            .anyMatch(method -> org.junit.platform.commons.support.AnnotationSupport.isAnnotated(
                method, org.junit.platform.commons.annotation.Testable.class)))
            .as("%s must name an executable test class", name).isTrue();
      }
    }
  }

  @Test void theShippedRecordCoversEveryTicketPrerequisiteExactlyOnce() {
    var ids = PilotReadiness.shipped(JSON).prerequisites().stream().map(Prerequisite::id).toList();
    assertThat(ids).containsExactlyElementsOf(PilotReadiness.REQUIRED_IDS);
  }

  @Test void theShippedRecordStillBlocksThePublicPilot() {
    var blockers = PilotReadiness.shipped(JSON).blockers().stream().map(Prerequisite::id).toList();
    assertThat(blockers).isNotEmpty();
    assertThatThrownBy(() -> new PilotReadiness(PilotReadiness.shipped(JSON).prerequisites(), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blocked")
        .hasMessageContaining(blockers.get(0));
  }

  @Test void unmetPrerequisitesDoNotBlockOrdinaryNonPilotStartup() {
    assertThatCode(() -> new PilotReadiness(PilotReadiness.shipped(JSON).prerequisites(), false))
        .doesNotThrowAnyException();
  }

  @Test void aFullyApprovedRecordReleasesThePilotGate() {
    assertThatCode(() -> new PilotReadiness(approveAll(), true)).doesNotThrowAnyException();
  }

  @Test void approvalRequiresAutomatedEvidenceOrANamedSignOff() {
    var unsupported = replace(approveAll(), 0, previous -> new Prerequisite(previous.id(),
        previous.requirement(), "APPROVED", null, null, null, List.of(), previous.evidence(), null));
    assertThatThrownBy(() -> new PilotReadiness(unsupported, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PilotReadiness.REQUIRED_IDS.get(0));
  }

  @Test void aBlockedPrerequisiteMustNameItsBlocker() {
    var silent = replace(approveAll(), 1, previous -> new Prerequisite(previous.id(),
        previous.requirement(), "BLOCKED", "  ", null, null, List.of(), previous.evidence(), null));
    assertThatThrownBy(() -> new PilotReadiness(silent, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PilotReadiness.REQUIRED_IDS.get(1));
  }

  @Test void anIncompleteRecordCannotStandInForTheTicketPrerequisites() {
    var missing = new ArrayList<>(approveAll());
    missing.remove(missing.size() - 1);
    assertThatThrownBy(() -> new PilotReadiness(missing, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void theReviewableRecordAgreesWithTheEnforcedStatuses() throws Exception {
    var document = java.nio.file.Files.readString(
        java.nio.file.Path.of("../docs/pilot-readiness-record.md"), java.nio.charset.StandardCharsets.UTF_8);
    var row = java.util.regex.Pattern.compile("^\\|[^|]*\\|\\s*`([a-z-]+)`\\s*\\|\\s*\\*{0,2}(APPROVED|BLOCKED)\\*{0,2}\\s*\\|",
        java.util.regex.Pattern.MULTILINE);

    var documented = new java.util.LinkedHashMap<String, String>();
    var matcher = row.matcher(document);
    while (matcher.find()) documented.put(matcher.group(1), matcher.group(2));

    var enforced = new java.util.LinkedHashMap<String, String>();
    PilotReadiness.shipped(JSON).prerequisites()
        .forEach(value -> enforced.put(value.id(), value.status()));

    assertThat(documented)
        .as("docs/pilot-readiness-record.md must state the same statuses the gate enforces")
        .isEqualTo(enforced);
  }

  private static List<Prerequisite> approveAll() {
    return PilotReadiness.shipped(JSON).prerequisites().stream()
        .map(previous -> new Prerequisite(previous.id(), previous.requirement(), "APPROVED", null,
            "named-reviewer", Instant.parse("2026-09-26T00:00:00Z"), List.of(), previous.evidence(), null))
        .toList();
  }

  private static List<Prerequisite> replace(
      List<Prerequisite> source, int index, java.util.function.UnaryOperator<Prerequisite> change) {
    var copy = new ArrayList<>(source);
    copy.set(index, change.apply(copy.get(index)));
    return copy;
  }
}
