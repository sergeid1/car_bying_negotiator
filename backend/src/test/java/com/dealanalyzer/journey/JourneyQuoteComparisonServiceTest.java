package com.dealanalyzer.journey;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence.DealerOfferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JourneyQuoteComparisonServiceTest {
  JdbcTemplate jdbc;
  DealerOfferPersistence offers;
  AccountJourneyAccess accounts;
  BuyingJourneyRepository journeys;
  JourneyQuoteComparisonService service;
  ObjectMapper json = new ObjectMapper();

  @BeforeEach void setup() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table journey_candidate_cases(candidate_id varchar, case_id varchar, journey_id varchar, account_id varchar, created_at timestamp)");
    jdbc.execute("create table journey_candidates(id varchar, journey_id varchar, account_id varchar, candidate_json varchar, shortlist_position int)");
    jdbc.execute("create table buyer_account_cases(case_id varchar, account_id varchar)");
    jdbc.execute("create table buyer_accounts(id varchar, lifecycle varchar)");
    jdbc.execute("create table analysis_cases(id varchar, lifecycle varchar, access_expires_at timestamp, core_review_blocked boolean, status varchar default 'READY_TO_ANALYZE')");
    jdbc.execute("create table case_negotiation_states(case_id varchar, state varchar)");
    jdbc.execute("create table document_extractions(id varchar, case_id varchar, document_id varchar, suspected_missing_pages boolean default false)");
    jdbc.execute("create table analysis_reports(case_id varchar, report_version int, payload_json varchar)");
    jdbc.update("insert into buyer_accounts values ('buyer', 'ACTIVE')");
    offers = mock(DealerOfferPersistence.class); accounts = mock(AccountJourneyAccess.class); journeys = mock(BuyingJourneyRepository.class);
    when(accounts.accountId("session")).thenReturn("buyer");
    when(journeys.findByIdAndAccountId("journey", "buyer")).thenReturn(Optional.of(new BuyingJourneyEntity("journey")));
    when(offers.latestOffer(anyString())).thenReturn(Optional.empty());
    service = new JourneyQuoteComparisonService(accounts, journeys, jdbc, offers, json);
  }

  @Test void onlyOwnedActiveLinkedCasesInBuyerShortlistOrderAreRead() {
    add("second", 2); add("first", 1); add("foreign", 0); add("expired", 0); add("deleted", 0);
    jdbc.update("update buyer_account_cases set account_id = 'other' where case_id = 'foreign'");
    jdbc.update("update analysis_cases set access_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY where id = 'expired'");
    jdbc.update("update analysis_cases set lifecycle = 'DELETION_PENDING' where id = 'deleted'");
    var result = service.view("session", "journey");
    assertThat(result.cars()).extracting(JourneyQuoteComparisonService.Car::caseId).containsExactly("first", "second");
    assertThat(result.cars()).allMatch(car -> car.amounts().isEmpty() && car.state().equals("Awaiting quote"));
    assertThat(result.otdComparable()).isFalse();
    verify(offers, never()).latestOffer("foreign");
    jdbc.update("update buyer_accounts set lifecycle = 'DELETION_PENDING'");
    assertThat(service.view("session", "journey").cars()).isEmpty();
    assertThatThrownBy(() -> service.view("session", "other-journey")).isInstanceOf(JourneyProblem.class);
  }

  @Test void completeQuotedComponentsIncludingExplicitZeroCarryDocumentEvidence() throws Exception {
    add("one", 0); verified("one", true);
    jdbc.update("insert into case_negotiation_states values ('one', 'AGREED')");
    var result = service.view("session", "journey");
    assertThat(result.otdComparable()).isTrue();
    var car = result.cars().getFirst();
    assertThat(car.state()).isEqualTo("Agreed");
    assertThat(car.amounts()).hasSize(6);
    assertThat(car.amounts().get(1).minorUnits()).isZero();
    assertThat(car.amounts().getLast().minorUnits()).isEqualTo(2200000);
    assertThat(car.amounts()).allSatisfy(amount -> {
      assertThat(amount.evidence().documentSource().documentId()).isEqualTo("doc");
      assertThat(amount.evidence().documentSource().pageNumber()).isEqualTo(2);
      assertThat(amount.evidence().documentSource().offerVersion()).isEqualTo(2);
    });
  }

  @Test void missingComponentsAndNewUnverifiedQuotesCannotEnterOtdComparison() throws Exception {
    add("one", 0); verified("one", false);
    var result = service.view("session", "journey");
    assertThat(result.otdComparable()).isFalse();
    assertThat(result.cars().getFirst().missing()).containsExactly("Add-ons");
    jdbc.update("update analysis_cases set status = 'NEEDS_REVIEW'");
    var car = service.view("session", "journey").cars().getFirst();
    assertThat(car.amounts()).isEmpty();
    assertThat(car.state()).isEqualTo("Quote unverified");
    jdbc.update("update analysis_cases set status = 'READY_TO_ANALYZE', core_review_blocked = true");
    assertThat(service.view("session", "journey").cars().getFirst().amounts()).isNotEmpty();
    jdbc.update("update document_extractions set suspected_missing_pages = true");
    assertThat(service.view("session", "journey").cars().getFirst().missing()).contains("Document pages");
  }

  @Test void marketPositionUsesOnlyLatestReportForThisOffer() throws Exception {
    add("one", 0); verified("one", true);
    String report = """
        {"version":3,"evidence":{"dealerOffer":{"id":"offer-one"}},"analysis":{
        "marketAvailability":"AVAILABLE","dealRating":"FAIR_NEGOTIATE","materialAmounts":[
        {"label":"Market selling-price low","source":"marketAssessment","minorUnits":1900000,"currency":"USD","precision":"ESTIMATED","sourceNote":"Stored provider estimate."}]}}
        """;
    jdbc.update("insert into analysis_reports values ('one', 3, ?)", report);
    var car = service.view("session", "journey").cars().getFirst();
    assertThat(car.marketPosition()).contains("within");
    assertThat(car.reportVersion()).isEqualTo(3);
    assertThat(car.amounts().getLast().evidence().sourceNote()).isEqualTo("Stored provider estimate.");
    jdbc.update("insert into analysis_reports values ('one', 4, ?)", report.replace("offer-one", "older-offer"));
    car = service.view("session", "journey").cars().getFirst();
    assertThat(car.marketPosition()).contains("unavailable");
    assertThat(car.reportVersion()).isNull();
  }

  private void add(String id, int position) {
    jdbc.update("insert into journey_candidate_cases values (?, ?, 'journey', 'buyer', CURRENT_TIMESTAMP)", id, id);
    jdbc.update("insert into journey_candidates values (?, 'journey', 'buyer', ?, ?)", id, "{\"title\":\"Car " + id + "\"}", position);
    jdbc.update("insert into buyer_account_cases values (?, 'buyer')", id);
    jdbc.update("insert into analysis_cases(id, lifecycle, access_expires_at, core_review_blocked) values (?, 'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '1' DAY, false)", id);
  }

  private void verified(String id, boolean complete) throws Exception {
    var terms = json.createObjectNode(); var candidates = terms.putArray("candidates"); terms.putArray("lineItems");
    String[] fields = {"SELLING_PRICE", "DEALER_CHARGES_TOTAL", "ADD_ON_TOTAL", "TAX_TOTAL", "GOVERNMENT_CHARGES_TOTAL", "OUT_THE_DOOR_TOTAL"};
    for (String field : fields) {
      if (!complete && field.equals("ADD_ON_TOTAL")) continue;
      var term = candidates.addObject(); term.put("field", field); term.put("sourceCandidateId", field);
      term.put("value", field.equals("OUT_THE_DOOR_TOTAL") ? "2200000" : field.equals("SELLING_PRICE") ? "2000000" : "0");
      term.put("unit", "USD_MINOR"); term.put("buyerVerified", true); term.put("originalWording", field);
      term.set("evidence", json.readTree("{\"pageNumber\":2,\"x\":0.1,\"y\":0.2,\"width\":0.5,\"height\":0.1}"));
    }
    jdbc.update("insert into document_extractions(id, case_id, document_id) values (?, ?, 'doc')", "ex-" + id, id);
    when(offers.latestOffer(id)).thenReturn(Optional.of(new DealerOfferView("offer-" + id, id, "ex-" + id,
        2, "VERIFIED", "schema", "fingerprint", terms, Instant.now())));
  }
}
