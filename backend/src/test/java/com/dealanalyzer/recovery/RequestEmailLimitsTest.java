package com.dealanalyzer.recovery;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RequestEmailLimitsTest {
  @Test void eachHourlyWindowReopensCapacityAndAlertsOnlyOnce(CapturedOutput output) {
    var limits = new RequestEmailLimits(1);
    long start = 42, hour = Duration.ofHours(1).toNanos();
    assertThat(limits.acquireDelivery(start)).isTrue();
    assertThat(limits.acquireDelivery(start + 1)).isFalse();
    assertThat(limits.acquireDelivery(start + hour - 1)).isFalse();
    assertThat(output.getAll().split("REQUEST_EMAIL_HOURLY_LIMIT_REACHED", -1)).hasSize(2);
    assertThat(limits.acquireDelivery(start + hour)).isTrue();
    assertThat(limits.acquireDelivery(start + hour + 1)).isFalse();
    assertThat(output.getAll().split("REQUEST_EMAIL_HOURLY_LIMIT_REACHED", -1)).hasSize(3);
  }
}
