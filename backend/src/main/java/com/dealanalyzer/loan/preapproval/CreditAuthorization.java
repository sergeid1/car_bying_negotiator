package com.dealanalyzer.loan.preapproval;

import java.time.Instant;

/** Reference to the Buyer's recorded written FCRA authorization; recording is owned by the caller. */
public record CreditAuthorization(String authorizationTextVersion, Instant authorizedAt) {
  public CreditAuthorization {
    if (authorizationTextVersion == null || authorizationTextVersion.isBlank()
        || authorizationTextVersion.length() > 100 || authorizedAt == null)
      throw new IllegalArgumentException("Recorded written credit authorization required");
  }
  public static void require(CreditAuthorization authorization, Instant now) {
    if (authorization == null || authorization.authorizedAt().isAfter(now))
      throw new IllegalArgumentException("Recorded written credit authorization required");
  }
}
