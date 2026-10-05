package com.dealanalyzer.relay;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DealerMailRelayIdempotencyTest {
  private static final DealerMailRelay.OutboundMail MAIL =
      new DealerMailRelay.OutboundMail("c@relay.invalid", "d@dealer.example", "Subject", "Body", "u");

  @Test void aRepeatedKeyReturnsTheOriginalProviderIdWithoutSendingAgain() {
    var fake = new FakeDealerMailRelay();
    assertThat(fake.lookup("key-1")).isEmpty();
    String first = fake.send("key-1", MAIL);
    assertThat(fake.send("key-1", MAIL)).isEqualTo(first);
    assertThat(fake.sent()).hasSize(1);
    assertThat(fake.lookup("key-1")).contains(first);
    assertThat(fake.send("key-2", MAIL)).isNotEqualTo(first);
    assertThat(fake.sent()).hasSize(2);
  }

  @Test void aLostResponseLeavesTheMessageWithTheProviderAndALookupFindsIt() {
    var fake = new FakeDealerMailRelay();
    fake.losingResponses(true);
    assertThatThrownBy(() -> fake.send("key", MAIL)).isNotInstanceOf(DealerMailRelay.RelayUnavailableException.class);
    assertThatThrownBy(() -> fake.send("key", MAIL)).isInstanceOf(RuntimeException.class);
    fake.losingResponses(false);
    assertThat(fake.sent()).hasSize(1);
    assertThat(fake.lookup("key")).isPresent();
    assertThat(fake.send("key", MAIL)).isEqualTo(fake.lookup("key").orElseThrow());
  }

  @Test void anUnavailableProviderNeitherSendsNorAnswersLookups() {
    var fake = new FakeDealerMailRelay();
    fake.failing(true);
    assertThatThrownBy(() -> fake.send("key", MAIL)).isInstanceOf(DealerMailRelay.RelayUnavailableException.class);
    assertThatThrownBy(() -> fake.lookup("key")).isInstanceOf(DealerMailRelay.RelayUnavailableException.class);
    assertThatThrownBy(() -> new UnavailableDealerMailRelay().lookup("key")).isInstanceOf(DealerMailRelay.RelayUnavailableException.class);
    fake.failing(false);
    assertThat(fake.sent()).isEmpty();
  }
}
