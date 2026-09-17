package com.dealanalyzer.time;

import java.time.Instant;

/** Canonical API precision for timestamp columns supported by PostgreSQL. */
public final class PersistedInstant {
  private PersistedInstant() {}

  public static Instant round(Instant value) {
    long roundedMicros = (value.getNano() + 500L) / 1_000L;
    return Instant.ofEpochSecond(value.getEpochSecond(), roundedMicros * 1_000L);
  }

  public static String format(Instant value) {
    return round(value).toString();
  }
}
