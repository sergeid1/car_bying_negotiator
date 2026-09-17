package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class InstantJsonConfigurationTest {
  @Test
  void apiTimestampsMatchPersistedMicrosecondPrecisionIncludingSecondRollover() throws Exception {
    var builder = new Jackson2ObjectMapperBuilder();
    new InstantJsonConfiguration().persistedInstantPrecision().customize(builder);
    var json = builder.build();

    assertThat(json.writeValueAsString(Instant.parse("2026-09-17T22:06:00.456821973Z")))
        .isEqualTo("\"2026-09-17T22:06:00.456822Z\"");
    assertThat(json.writeValueAsString(Instant.parse("2026-09-17T22:06:00.999999900Z")))
        .isEqualTo("\"2026-09-17T22:06:01Z\"");
  }
}
