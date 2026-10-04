package com.dealanalyzer.appointment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ticket 56 supplies case-to-journey linkage. Implementations run inside the case transaction. */
public interface JourneyAppointment {
  /** A test drive was booked: a linked journey in AGREED becomes TEST_DRIVE_SCHEDULED. True when linked. */
  boolean scheduled(String caseId);
  /** The last scheduled test drive was cancelled: a linked journey returns to AGREED. True when linked. */
  boolean cancelled(String caseId);

  @Configuration
  class DefaultConfiguration {
    @Bean @ConditionalOnMissingBean(JourneyAppointment.class)
    JourneyAppointment unlinkedJourneyAppointment() {
      return new JourneyAppointment() {
        @Override public boolean scheduled(String caseId) { return false; }
        @Override public boolean cancelled(String caseId) { return false; }
      };
    }
  }
}
