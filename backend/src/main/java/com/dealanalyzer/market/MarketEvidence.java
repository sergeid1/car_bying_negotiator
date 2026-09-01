package com.dealanalyzer.market;

import java.time.Instant;

public record MarketEvidence(
    long lowMinor,
    long highMinor,
    Confidence confidence,
    Instant dataAsOf,
    String source,
    String providerSnapshotVersion) {
  public enum Confidence {
    LOW,
    MEDIUM,
    HIGH
  }
}
