package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeerQuotaTest {
  @Test void peersAreIndependentAndExpiredBucketsReleaseBoundedCapacity() {
    var quota = new ApiRateLimitFilter.PeerQuota(1, Duration.ofSeconds(2), 2);
    long start = 42, second = Duration.ofSeconds(1).toNanos();
    assertThat(quota.acquire("peer-a", start)).isZero();
    assertThat(quota.acquire("peer-b", start)).isZero();
    assertThat(quota.acquire("peer-a", start + second)).isEqualTo(1);
    assertThat(quota.acquire("peer-c", start + second)).isEqualTo(2);
    assertThat(quota.acquire("peer-c", start + 2 * second)).isZero();
    assertThat(quota.acquire("peer-a", start + 2 * second)).isZero();
    assertThat(quota.acquire("peer-c", start + 2 * second + 1)).isEqualTo(2);
  }

  @Test void nonpositivePeerSettingsAreRejected() {
    assertThatThrownBy(() -> new ApiRateLimitFilter.PeerQuota(0, Duration.ofMinutes(1), 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApiRateLimitFilter.PeerQuota(1, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApiRateLimitFilter.PeerQuota(1, Duration.ofMinutes(-1), 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApiRateLimitFilter.PeerQuota(1, Duration.ofMinutes(1), 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
