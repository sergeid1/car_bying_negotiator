package com.dealanalyzer.purchase;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CaseStudyPublicationTest {
  @Test void everyPublishedCaseStudyRequiresPermissionDenominatorsAndEvidenceLimits() throws Exception {
    Path published = Path.of("../docs/case-studies/published");
    if (!Files.exists(published)) return; // No studies exist; never create synthetic evidence.
    try (var files = Files.walk(published)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        assertThat(file.toString()).endsWith(".md");
        validate(Files.readString(file));
      }
    }
  }
  static void validate(String text) {
    assertThat(text).contains("Buyer permission: GRANTED");
    assertThat(text).doesNotContain("TODO", "PENDING", "YYYY-MM-DD");
    field(text, "Permission reference", "[a-zA-Z0-9_-]{8,64}");
    LocalDate.parse(field(text, "Permission date", "\\d{4}-\\d{2}-\\d{2}"));
    String window = field(text, "Review window", "\\d{4}-\\d{2}-\\d{2} / \\d{4}-\\d{2}-\\d{2}");
    assertThat(LocalDate.parse(window.substring(0, 10))).isBefore(LocalDate.parse(window.substring(13)));
    long numerator = Long.parseLong(field(text, "Numerator", "[0-9]+"));
    long denominator = Long.parseLong(field(text, "Denominator", "[0-9]+"));
    assertThat(denominator).isPositive(); assertThat(numerator).isBetween(0L, denominator);
    for (String section : new String[] {"Cohort and denominators", "Buyer-reported outcomes", "Verified document evidence", "Purchases and willingness to pay", "What was not measured"}) {
      var matcher = Pattern.compile("(?ms)^## " + Pattern.quote(section) + "\\s*\\n(.+?)(?=^## |\\z)").matcher(text);
      assertThat(matcher.find()).as(section).isTrue();
      assertThat(matcher.group(1).strip().length()).as(section).isGreaterThan(20);
    }
  }
  private static String field(String text, String name, String pattern) {
    var matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + ": (" + pattern + ")\\s*$").matcher(text);
    assertThat(matcher.find()).as(name).isTrue(); return matcher.group(1);
  }
  @Test void incompletePublicationFailsTheGate() {
    assertThatThrownBy(() -> validate("# An outcome claim without permission or denominators")).isInstanceOf(AssertionError.class);
  }
  @Test void eachRequiredPublicationFieldAndSectionIsEnforced() {
    String fixture = """
        # Structural test fixture; not published evidence
        Buyer permission: GRANTED
        Permission reference: test-only-reference
        Permission date: 2026-10-01
        Review window: 2026-09-01 / 2026-10-01
        Numerator: 1
        Denominator: 2
        ## Cohort and denominators
        Synthetic validation data only, with two total observations.
        ## Buyer-reported outcomes
        No real Buyer outcomes exist in this structural fixture.
        ## Verified document evidence
        No real document evidence exists in this structural fixture.
        ## Purchases and willingness to pay
        No real purchases or survey responses exist in this fixture.
        ## What was not measured
        Everything is synthetic; neither outcomes nor causation were measured.
        """;
    validate(fixture);
    for (String required : new String[] {"Buyer permission: GRANTED", "Permission reference: test-only-reference",
        "Permission date: 2026-10-01", "Review window: 2026-09-01 / 2026-10-01", "Numerator: 1", "Denominator: 2",
        "## Cohort and denominators", "## Buyer-reported outcomes", "## Verified document evidence",
        "## Purchases and willingness to pay", "## What was not measured"}) {
      assertThatThrownBy(() -> validate(fixture.replace(required, ""))).as(required).isInstanceOf(AssertionError.class);
    }
    assertThatThrownBy(() -> validate(fixture.replace("Denominator: 2", "Denominator: 0"))).isInstanceOf(AssertionError.class);
  }
}
