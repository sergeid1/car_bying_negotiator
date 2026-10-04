package com.dealanalyzer.journey;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LinkedJourneyAppointmentTest {
  JdbcTemplate jdbc;
  BuyingJourneyRepository journeys;
  BuyingJourneyEntity journey;
  LinkedJourneyAppointment service;

  @BeforeEach void setup() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    jdbc.execute("create table journey_candidate_cases(case_id varchar primary key, journey_id varchar, account_id varchar, created_at timestamp)");
    jdbc.execute("create table buyer_account_cases(case_id varchar primary key, account_id varchar)");
    jdbc.execute("create table analysis_cases(id varchar primary key, lifecycle varchar, access_expires_at timestamp)");
    jdbc.execute("create table buyer_accounts(id varchar primary key, lifecycle varchar)");
    jdbc.update("insert into buyer_accounts values ('buyer', 'ACTIVE')");
    jdbc.update("insert into journey_candidate_cases values ('booked', 'journey', 'buyer', CURRENT_TIMESTAMP)");
    jdbc.update("insert into buyer_account_cases values ('booked', 'buyer')");
    jdbc.update("insert into analysis_cases values ('booked', 'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '1' DAY)");
    journeys = mock(BuyingJourneyRepository.class);
    journey = new BuyingJourneyEntity("journey"); journey.accountId = "buyer";
    journey.state = JourneyState.AGREED; journey.updatedAt = Instant.EPOCH;
    when(journeys.findLockedByIdAndAccountId("journey", "buyer")).thenReturn(Optional.of(journey));
    service = new LinkedJourneyAppointment(jdbc, journeys);
  }

  @Test void bookingMovesAnAgreedJourneyAndCancellationReturnsIt() {
    assertThat(service.scheduled("booked")).isTrue();
    assertThat(journey.state).isEqualTo(JourneyState.TEST_DRIVE_SCHEDULED);
    assertThat(journey.updatedAt).isAfter(Instant.EPOCH);
    assertThat(service.cancelled("booked")).isTrue();
    assertThat(journey.state).isEqualTo(JourneyState.AGREED);
  }

  @Test void leavesOtherStatesAndUnlinkedCasesAlone() {
    journey.state = JourneyState.CLOSED;
    assertThat(service.scheduled("booked")).isTrue();
    assertThat(service.cancelled("booked")).isTrue();
    assertThat(journey.state).isEqualTo(JourneyState.CLOSED);
    assertThat(service.scheduled("direct")).isFalse();
    verify(journeys, times(2)).findLockedByIdAndAccountId("journey", "buyer");
  }

  @Test void refusesWhenOwnershipChangesWhileTheJourneyLockIsAcquired() {
    when(journeys.findLockedByIdAndAccountId("journey", "buyer")).thenAnswer(call -> {
      jdbc.update("update buyer_account_cases set account_id = 'different' where case_id = 'booked'");
      return Optional.of(journey);
    });
    assertThatIllegalArgumentException().isThrownBy(() -> service.scheduled("booked"));
    assertThat(journey.state).isEqualTo(JourneyState.AGREED);
  }
}
