package com.dealanalyzer.verification;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public record VerifyDealerOfferRequest(
    String extractionId,
    Set<String> confirmedCandidateIds,
    Set<String> confirmedLineItemIds) {
  public VerifyDealerOfferRequest {
    confirmedCandidateIds = confirmedCandidateIds == null ? Set.of()
        : Collections.unmodifiableSet(new TreeSet<>(confirmedCandidateIds));
    confirmedLineItemIds = confirmedLineItemIds == null ? Set.of()
        : Collections.unmodifiableSet(new TreeSet<>(confirmedLineItemIds));
  }
}
