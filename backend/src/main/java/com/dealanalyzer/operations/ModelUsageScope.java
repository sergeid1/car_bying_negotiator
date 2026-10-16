package com.dealanalyzer.operations;

/** Metering ownership only; never included in a model request. */
public record ModelUsageScope(String caseId, String accountId) {
  public ModelUsageScope {
    if (caseId != null && accountId != null) throw new IllegalArgumentException("One model usage scope required");
    if ((caseId != null && caseId.isBlank()) || (accountId != null && accountId.isBlank()))
      throw new IllegalArgumentException("Usage identifier required");
  }

  public static ModelUsageScope forCase(String caseId) {
    return new ModelUsageScope(java.util.Objects.requireNonNull(caseId), null);
  }

  /** Null account means an anonymous guide answer. */
  public static ModelUsageScope guideAnswer(String accountId) {
    return new ModelUsageScope(null, accountId);
  }
}
