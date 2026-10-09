package com.dealanalyzer.account;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.buyingcase.CaseAccessService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PasswordAttemptsTest {
  @Test void accountBackoffSurvivesChangingClientAndDoublesBeforeExpiring() {
    var limits = limits();
    long start = Duration.ofSeconds(1).toNanos();
    for (int i = 0; i < 5; i++) assertThat(limits.acquire("buyer@example.com", "client" + i, start)).isTrue();
    assertThat(limits.acquire("buyer@example.com", "another", start)).isFalse();
    assertThat(limits.acquire("buyer@example.com", "another", start + Duration.ofSeconds(1).toNanos())).isTrue();
    assertThat(limits.acquire("buyer@example.com", "another", start + Duration.ofSeconds(2).toNanos())).isFalse();
    assertThat(limits.acquire("buyer@example.com", "another", start + Duration.ofSeconds(3).toNanos())).isTrue();
    assertThat(limits.acquire("buyer@example.com", "another", start + Duration.ofSeconds(6).toNanos())).isFalse();
    assertThat(limits.acquire("buyer@example.com", "another", start + Duration.ofHours(1).toNanos())).isTrue();
  }

  @Test void clientBackoffSurvivesChangingAccount() {
    var limits = limits();
    long start = Duration.ofSeconds(1).toNanos();
    for (int i = 0; i < 20; i++) assertThat(limits.acquire("buyer" + i + "@example.com", "same-client", start)).isTrue();
    assertThat(limits.acquire("other@example.com", "same-client", start)).isFalse();
    assertThat(limits.acquire("other@example.com", "another-client", start)).isTrue();
  }

  private static PasswordAttempts limits() {
    var secrets = org.mockito.Mockito.mock(CaseAccessService.class);
    org.mockito.Mockito.when(secrets.protectAccountSecret(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> call.getArgument(0, String.class) + ":" + call.getArgument(1, String.class));
    return new PasswordAttempts(secrets);
  }
}
