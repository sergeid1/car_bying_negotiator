package com.dealanalyzer.desk.tactic;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeskTacticServiceTest {
  @Test void outsideReferenceIsLatestWithinCaseAndQuestionRetainsItsInputs() throws Exception {
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table outside_finance_offers (case_id varchar, version int, payload_json varchar)");
    jdbc.update("insert into outside_finance_offers values (?, ?, ?)", "one", 1, "{\"aprPercent\":4,\"termMonths\":48}");
    jdbc.update("insert into outside_finance_offers values (?, ?, ?)", "one", 2, "{\"aprPercent\":6,\"termMonths\":60}");
    jdbc.update("insert into outside_finance_offers values (?, ?, ?)", "two", 3, "{\"aprPercent\":9,\"termMonths\":84}");
    var json = new ObjectMapper(); var service = new DeskTacticService(jdbc, json);
    var terms = json.readTree("{\"candidates\":[{\"field\":\"APR\",\"value\":\"8\",\"unit\":\"PERCENT\",\"buyerVerified\":true},{\"field\":\"TERM_MONTHS\",\"value\":\"72\",\"unit\":\"MONTH\",\"buyerVerified\":true}]}");
    var findings = service.findings("one", terms, null);
    assertThat(findings).extracting(DeskTacticPolicy.Finding::code).containsExactly("STRETCHED_TERM", "APR_ABOVE_OWN_OFFER");
    assertThat(findings.get(1).evidenceRegions().getLast().region().path("version").asInt()).isEqualTo(2);
    assertThat(findings.get(1).evidenceRegions().getLast().region().at("/inputs/aprPercent").asInt()).isEqualTo(6);
    assertThat(service.findings("two", terms, null)).isEmpty();
    assertThat(service.findings("no-offer", terms, null)).isEmpty();
    assertThat(service.findings("one", terms, null, new BigDecimal("8"))).extracting(DeskTacticPolicy.Finding::code).containsExactly("STRETCHED_TERM");
    var baseline = json.readTree("{\"candidates\":[{\"field\":\"TERM_MONTHS\",\"value\":\"60\",\"unit\":\"MONTH\"}]}");
    assertThat(service.findings("no-offer", terms, baseline)).singleElement().satisfies(f -> {
      assertThat(f.code()).isEqualTo("STRETCHED_TERM");
      assertThat(f.evidenceRegions().getLast().region().path("source").asText()).isEqualTo("agreed_baseline");
    });
    var duplicate = baseline.deepCopy();
    ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("candidates")).add(baseline.at("/candidates/0"));
    assertThat(service.findings("no-offer", terms, duplicate)).isEmpty();
    ((com.fasterxml.jackson.databind.node.ObjectNode) baseline.at("/candidates/0")).put("unit", "YEARS");
    assertThat(service.findings("no-offer", terms, baseline)).isEmpty();
    jdbc.execute("shutdown");
  }
}
