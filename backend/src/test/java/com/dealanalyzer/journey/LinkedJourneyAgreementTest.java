package com.dealanalyzer.journey;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dealanalyzer.quote.JourneyAgreement;
import com.dealanalyzer.quote.NegotiationStateService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LinkedJourneyAgreementTest {
  JdbcTemplate jdbc;
  BuyingJourneyRepository journeys;
  NegotiationStateService negotiations;
  BuyingJourneyEntity journey;
  LinkedJourneyAgreement service;

  @BeforeEach void setup() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table journey_candidate_cases(case_id varchar primary key, journey_id varchar, account_id varchar, created_at timestamp)");
    jdbc.execute("create table buyer_account_cases(case_id varchar primary key, account_id varchar)");
    jdbc.execute("create table analysis_cases(id varchar primary key, lifecycle varchar, access_expires_at timestamp)");
    jdbc.execute("create table buyer_accounts(id varchar primary key, lifecycle varchar)");
    jdbc.execute("create table negotiation_mandates(id varchar primary key, case_id varchar, version int, expires_at timestamp, plan_snapshot_id varchar)");
    jdbc.execute("create table price_plan_snapshots(id varchar primary key, case_id varchar, kind varchar, version int)");
    jdbc.execute("create table negotiation_mandate_revocations(mandate_id varchar)");
    jdbc.execute("create table case_negotiation_states(case_id varchar primary key, mandate_id varchar, state varchar)");
    jdbc.update("insert into buyer_accounts values ('buyer', 'ACTIVE')");
    journeys = mock(BuyingJourneyRepository.class);
    negotiations = mock(NegotiationStateService.class);
    journey = new BuyingJourneyEntity("journey"); journey.accountId = "buyer";
    journey.state = JourneyState.NEGOTIATING; journey.updatedAt = Instant.EPOCH;
    when(journeys.findLockedByIdAndAccountId("journey", "buyer")).thenReturn(Optional.of(journey));
    service = new LinkedJourneyAgreement(jdbc, journeys, negotiations);
    add("accepted", "journey", "buyer", "buyer");
  }

  @Test void agreementListsOpenIncludingPausedAndLeavesClosedOrForeignCasesOut() {
    add("active", "journey", "buyer", "buyer"); add("paused", "journey", "buyer", "buyer");
    add("stopped", "journey", "buyer", "buyer"); add("foreign", "journey", "buyer", "someone-else");
    state("active", "AWAITING_BUYER"); state("paused", "PAUSED"); state("stopped", "STOPPED");
    var result = service.agree("accepted");
    assertThat(journey.state).isEqualTo(JourneyState.AGREED);
    assertThat(result.journeyLinked()).isTrue();
    assertThat(result.otherOpenNegotiations()).extracting(JourneyAgreement.OtherNegotiation::caseId)
        .containsExactly("active", "paused");
    verifyNoInteractions(negotiations);
  }

  @Test void currentDoesNotReplayAgreementAndDirectCasesRemainUnlinked() {
    var updated = journey.updatedAt;
    assertThat(service.current("accepted", null).journeyLinked()).isTrue();
    assertThat(journey.state).isEqualTo(JourneyState.NEGOTIATING);
    assertThat(journey.updatedAt).isEqualTo(updated);
    assertThat(service.agree("direct").journeyLinked()).isFalse();
  }

  @Test void choiceCallsStateBoundaryAndKeepsPausedOptionsUntilStopped() {
    journey.state = JourneyState.AGREED; add("other", "journey", "buyer", "buyer");
    state("other", "AWAITING_DEALER");
    doAnswer(call -> {
      jdbc.update("update case_negotiation_states set state = ? where case_id = ?",
          call.getArgument(1, NegotiationStateService.State.class).name(), call.getArgument(0, String.class));
      return null;
    }).when(negotiations).transition(eq("other"), any());
    assertThat(service.choose("accepted", "other", JourneyAgreement.Action.PAUSE).otherOpenNegotiations()).hasSize(1);
    assertThat(service.choose("accepted", "other", JourneyAgreement.Action.STOP).otherOpenNegotiations()).isEmpty();
    verify(negotiations).transition("other", NegotiationStateService.State.PAUSED);
    verify(negotiations).transition("other", NegotiationStateService.State.STOPPED);
  }

  @Test void refusesForeignJourneyOwnershipAndMembershipChangesUnderLock() {
    journey.state = JourneyState.AGREED; add("foreign", "another", "buyer", "buyer");
    assertThatIllegalArgumentException().isThrownBy(() -> service.choose("accepted", "foreign", JourneyAgreement.Action.STOP));
    when(journeys.findLockedByIdAndAccountId("journey", "buyer")).thenAnswer(call -> {
      jdbc.update("update buyer_account_cases set account_id = 'different' where case_id = 'accepted'");
      return Optional.of(journey);
    });
    assertThatIllegalArgumentException().isThrownBy(() -> service.current("accepted", null));
    verify(negotiations, never()).transition(anyString(), any());
  }

  @Test void excludesExpiredCasesAndRechecksAccountLifecycleUnderJourneyLock() {
    add("expired", "journey", "buyer", "buyer");
    jdbc.update("update analysis_cases set access_expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY where id = 'expired'");
    assertThat(service.agree("accepted").otherOpenNegotiations()).isEmpty();
    verifyNoInteractions(negotiations);
    when(journeys.findLockedByIdAndAccountId("journey", "buyer")).thenAnswer(call -> {
      jdbc.update("update buyer_accounts set lifecycle = 'DELETION_PENDING' where id = 'buyer'");
      return Optional.of(journey);
    });
    assertThatIllegalArgumentException().isThrownBy(() -> service.current("accepted", null));
  }

  @Test void choiceRefusesContentionOnAnotherCaseWithoutWaitingForItsTransaction() throws Exception {
    journey.state = JourneyState.AGREED; add("other", "journey", "buyer", "buyer"); state("other", "PAUSED");
    try (var connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("select id from analysis_cases where id = 'other' for update")) {
        statement.executeQuery();
        assertThatIllegalArgumentException().isThrownBy(() -> service.choose("accepted", "other", JourneyAgreement.Action.STOP))
            .withMessage("That negotiation is changing. Reload the journey and try again.");
        verifyNoInteractions(negotiations);
      } finally { connection.rollback(); }
    }
  }

  @Test void optionsUseLatestLiveMandateAndTreatChangedMandateAsActive() {
    add("expired-mandate", "journey", "buyer", "buyer"); state("expired-mandate", "PAUSED");
    jdbc.update("update negotiation_mandates set expires_at = CURRENT_TIMESTAMP - INTERVAL '1' DAY where case_id = 'expired-mandate'");
    add("revoked", "journey", "buyer", "buyer"); state("revoked", "PAUSED");
    jdbc.update("insert into negotiation_mandate_revocations values ('mandate-revoked')");
    add("new-mandate", "journey", "buyer", "buyer"); state("new-mandate", "STOPPED");
    jdbc.update("insert into negotiation_mandates values ('next', 'new-mandate', 2, CURRENT_TIMESTAMP + INTERVAL '1' DAY, 'plan-new-mandate')");
    add("plan-changed", "journey", "buyer", "buyer"); state("plan-changed", "PAUSED");
    jdbc.update("insert into price_plan_snapshots values ('changed', 'plan-changed', 'PLAN', 2)");
    assertThat(service.agree("accepted").otherOpenNegotiations()).extracting(JourneyAgreement.OtherNegotiation::caseId)
        .containsExactly("new-mandate");
    verifyNoInteractions(negotiations);
  }

  private void state(String id, String state) {
    jdbc.update("insert into price_plan_snapshots values (?, ?, 'PLAN', 1)", "plan-" + id, id);
    jdbc.update("insert into negotiation_mandates values (?, ?, 1, CURRENT_TIMESTAMP + INTERVAL '1' DAY, ?)", "mandate-" + id, id, "plan-" + id);
    jdbc.update("insert into case_negotiation_states values (?, ?, ?)", id, "mandate-" + id, state);
  }

  private void add(String id, String journeyId, String linkOwner, String caseOwner) {
    jdbc.update("insert into journey_candidate_cases values (?, ?, ?, CURRENT_TIMESTAMP)", id, journeyId, linkOwner);
    jdbc.update("insert into buyer_account_cases values (?, ?)", id, caseOwner);
    jdbc.update("insert into analysis_cases values (?, 'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '1' DAY)", id);
  }
}
