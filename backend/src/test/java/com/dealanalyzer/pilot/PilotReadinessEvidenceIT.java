package com.dealanalyzer.pilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

/** Runs after Surefire, so approval requires actual successful execution, not just class names. */
class PilotReadinessEvidenceIT {
  @Test void approvedAutomatedChecksHavePassingUnskippedResults() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    for (var prerequisite : PilotReadiness.shipped(new ObjectMapper().findAndRegisterModules()).prerequisites()) {
      if (!"APPROVED".equals(prerequisite.status()) || prerequisite.checks() == null) continue;
      for (String check : prerequisite.checks()) {
        String name = check.contains(".") ? check : "com.dealanalyzer.pilot." + check;
        Path report = Path.of("target/surefire-reports/TEST-" + name + ".xml");
        assertThat(report).as("executed evidence for %s", prerequisite.id()).exists();
        try (var input = Files.newInputStream(report)) {
          var suite = factory.newDocumentBuilder().parse(input).getDocumentElement();
          assertThat(Integer.parseInt(suite.getAttribute("tests"))).isPositive();
          for (String count : new String[] {"failures", "errors", "skipped"}) {
            assertThat(suite.getAttribute(count)).as("%s %s", check, count).isEqualTo("0");
          }
        }
      }
    }
  }
}
