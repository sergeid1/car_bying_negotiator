package com.dealanalyzer.market;

import java.time.Instant;

public record MarketEvidence(
    long lowMinor,
    long highMinor,
    String confidence,
    Instant dataAsOf,
    String source,
    String providerSnapshotVersion) {}

