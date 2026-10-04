package com.dealanalyzer.quote;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** Ticket 56 supplies case-to-journey linkage. Implementations run inside the case transaction. */
public interface JourneyAgreement {
  record OtherNegotiation(String caseId, String label) {}
  record Result(boolean journeyLinked, List<OtherNegotiation> otherOpenNegotiations) {}
  enum Action { PAUSE, STOP }
  Result agree(String caseId);
  /** Linked implementations must recheck journey membership and ownership under their journey lock. */
  default Result choose(String acceptedCaseId, String otherCaseId, Action action) {
    throw new IllegalArgumentException("This Buying Case is not linked to a journey.");
  }
  /** Read current options without replaying the AGREED transition. */
  default Result current(String caseId, Result recorded) { return recorded; }

  @Configuration
  class DefaultConfiguration {
    @Bean @ConditionalOnMissingBean(JourneyAgreement.class)
    JourneyAgreement unlinkedJourneyAgreement() { return caseId -> new Result(false, List.of()); }
  }
}
