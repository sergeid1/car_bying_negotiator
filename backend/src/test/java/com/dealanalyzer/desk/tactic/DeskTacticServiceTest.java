package com.dealanalyzer.desk.tactic;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dealanalyzer.loan.ChosenLoanOffers;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DeskTacticServiceTest {
  @Test void outsideReferenceIsLatestWithinCaseAndQuestionRetainsItsInputs() throws Exception {
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    createTables(jdbc);
    jdbc.update("insert into outside_finance_offers values (?, ?, ?, current_timestamp)", "one", 1, "{\"aprPercent\":4,\"termMonths\":48}");
    jdbc.update("insert into outside_finance_offers values (?, ?, ?, current_timestamp)", "one", 2, "{\"aprPercent\":6,\"termMonths\":60}");
    jdbc.update("insert into outside_finance_offers values (?, ?, ?, current_timestamp)", "two", 3, "{\"aprPercent\":9,\"termMonths\":84}");
    var json = new ObjectMapper(); var service = new DeskTacticService(jdbc, json, new ChosenLoanOffers(jdbc));
    var terms = json.readTree("{\"candidates\":[{\"field\":\"APR\",\"value\":\"8\",\"unit\":\"PERCENT\",\"buyerVerified\":true},{\"field\":\"TERM_MONTHS\",\"value\":\"72\",\"unit\":\"MONTH\",\"buyerVerified\":true}]}");
    var findings = service.findings("one", terms, null);
    assertThat(findings).extracting(DeskTacticPolicy.Finding::code).containsExactly("STRETCHED_TERM", "APR_ABOVE_OWN_OFFER");
    assertThat(findings.get(1).evidenceRegions().getLast().region().path("version").asInt()).isEqualTo(2);
    assertThat(findings.get(1).evidenceRegions().getLast().region().at("/inputs/aprPercent").asInt()).isEqualTo(6);
    // The dealer's 8% is below the Buyer's own 9%: the dealer's financing costs less, and the check says so.
    assertThat(service.findings("two", terms, null)).singleElement().satisfies(f -> {
      assertThat(f.code()).isEqualTo(DeskTacticPolicy.DEALER_FINANCING_COSTS_LESS);
      assertThat(f.summary()).startsWith("The dealer's financing costs less than your own offer");
    });
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

  @Test void aChosenLoanOfferIsTheBuyersOwnFinancingWhenItIsLaterThanTheSavedOutsideOffer() throws Exception {
    var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    createTables(jdbc);
    var json = new ObjectMapper(); var service = new DeskTacticService(jdbc, json, new ChosenLoanOffers(jdbc));
    var now = Instant.now();
    jdbc.update("insert into outside_finance_offers values ('case', 1, '{\"aprPercent\":4,\"termMonths\":48}', ?)", Timestamp.from(now.minusSeconds(60)));
    jdbc.update("insert into journey_candidate_cases values ('journey-case', 'journey')");
    jdbc.update("insert into loan_applications values ('application', 'journey', null, 'offer', ?)", Timestamp.from(now));
    jdbc.update("insert into loan_offers values ('offer', 'application', 'Synthetic lender', 'PARTNER', 'OFFERED', 3000000, 6.25000000, 60, 0, null, ?)",
        Timestamp.from(now.plusSeconds(3600)));
    var own = service.ownOffer("journey-case");
    assertThat(own.aprPercent()).isEqualByComparingTo("6.25");
    assertThat(own.termMonths()).isEqualTo(60);
    assertThat(own.evidence().path("source").asText()).isEqualTo("chosen_loan_offer");
    assertThat(own.evidence().path("lender").asText()).isEqualTo("Synthetic lender");
    // A case outside the journey keeps its own saved outside offer.
    assertThat(service.ownOffer("case").aprPercent()).isEqualByComparingTo("4");
    // A later saved outside offer on the journey's case wins over the earlier choice.
    jdbc.update("insert into outside_finance_offers values ('journey-case', 1, '{\"aprPercent\":5,\"termMonths\":48}', ?)", Timestamp.from(now.plusSeconds(1)));
    assertThat(service.ownOffer("journey-case").evidence().path("source").asText()).isEqualTo("outside_finance_offers");
    // An expired chosen offer is no longer an offer.
    jdbc.update("delete from outside_finance_offers where case_id = 'journey-case'");
    jdbc.update("update loan_offers set expires_at = ?", Timestamp.from(now.minusSeconds(1)));
    assertThat(service.ownOffer("journey-case")).isNull();
    jdbc.execute("shutdown");
  }

  private static void createTables(JdbcTemplate jdbc) {
    jdbc.execute("create table outside_finance_offers (case_id varchar, version int, payload_json varchar, created_at timestamp with time zone)");
    jdbc.execute("create table journey_candidate_cases (case_id varchar, journey_id varchar)");
    jdbc.execute("create table trade_case_journeys (case_id varchar, journey_id varchar)");
    jdbc.execute("create table loan_applications (id varchar, journey_id varchar, case_id varchar, chosen_offer_id varchar, chosen_at timestamp with time zone)");
    jdbc.execute("create table loan_offers (id varchar, application_id varchar, lender varchar, source varchar, outcome varchar, amount_minor bigint, "
        + "apr_percent numeric(11, 8), term_months int, upfront_fees_minor bigint, lender_payment_minor bigint, expires_at timestamp with time zone)");
  }
}
