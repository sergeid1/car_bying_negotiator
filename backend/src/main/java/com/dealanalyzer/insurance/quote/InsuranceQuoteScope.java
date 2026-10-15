package com.dealanalyzer.insurance.quote;

/** An application belongs to a Buying Case, or to a Buying Journey without a case (INS-01). */
public record InsuranceQuoteScope(String caseId, String accountId, String journeyId) {
  public InsuranceQuoteScope {
    boolean forCase = present(caseId) && accountId == null && journeyId == null;
    boolean forJourney = caseId == null && present(accountId) && present(journeyId);
    if (!forCase && !forJourney) throw new IllegalArgumentException("A case or a journey is required");
  }

  public static InsuranceQuoteScope forCase(String caseId) { return new InsuranceQuoteScope(caseId, null, null); }
  public static InsuranceQuoteScope forJourney(String accountId, String journeyId) {
    return new InsuranceQuoteScope(null, accountId, journeyId);
  }

  private static boolean present(String id) { return id != null && !id.isBlank(); }
}
