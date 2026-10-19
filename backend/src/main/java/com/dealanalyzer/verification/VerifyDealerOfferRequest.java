package com.dealanalyzer.verification;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * confirmedSuggestionLineItemIds names the line items whose suggested catalogue charge the Buyer confirmed
 * (ticket 142); only those suggestions become the line's charge and category.
 */
public record VerifyDealerOfferRequest(
    String extractionId,
    Set<String> confirmedCandidateIds,
    Set<String> confirmedLineItemIds,
    Set<String> confirmedSuggestionLineItemIds) {
  public VerifyDealerOfferRequest(String extractionId, Set<String> confirmedCandidateIds,
      Set<String> confirmedLineItemIds) {
    this(extractionId, confirmedCandidateIds, confirmedLineItemIds, null);
  }

  public VerifyDealerOfferRequest {
    confirmedCandidateIds = confirmedCandidateIds == null ? Set.of()
        : Collections.unmodifiableSet(new TreeSet<>(confirmedCandidateIds));
    confirmedLineItemIds = confirmedLineItemIds == null ? Set.of()
        : Collections.unmodifiableSet(new TreeSet<>(confirmedLineItemIds));
    confirmedSuggestionLineItemIds = confirmedSuggestionLineItemIds == null ? Set.of()
        : Collections.unmodifiableSet(new TreeSet<>(confirmedSuggestionLineItemIds));
  }
}
