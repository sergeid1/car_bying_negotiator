package com.dealanalyzer.verification;

public record CorrectDealTermRequest(
    TargetType targetType,
    String targetId,
    String normalizedValue,
    String normalizedUnit,
    String category,
    Long amountMinor) {
  public enum TargetType { CANDIDATE, LINE_ITEM }
}
