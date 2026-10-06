package com.dealanalyzer.recovery;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared, per-process ceiling for sign-in and case recovery delivery attempts. */
@Component
public class RequestEmailLimits {
  private static final Logger LOG = LoggerFactory.getLogger(RequestEmailLimits.class);
  private static final long HOUR = Duration.ofHours(1).toNanos();
  private final int hourlyLimit;
  private long started;
  private int attempts;
  private boolean alerted;

  public RequestEmailLimits(@Value("${security.request-email.hourly-limit:100}") int hourlyLimit) {
    if (hourlyLimit < 1) throw new IllegalArgumentException("security.request-email.hourly-limit must be positive");
    this.hourlyLimit = hourlyLimit;
  }

  /** Reserve before creating a link or calling SMTP; failed attempts still consume capacity. */
  public boolean acquireDelivery() { return acquireDelivery(System.nanoTime()); }

  synchronized boolean acquireDelivery(long now) {
    if (attempts == 0 || now - started >= HOUR) {
      started = now; attempts = 0; alerted = false;
    }
    if (attempts >= hourlyLimit) {
      if (!alerted) {
        alerted = true;
        LOG.warn("REQUEST_EMAIL_HOURLY_LIMIT_REACHED: sign-in and case recovery mail paused for this window");
      }
      return false;
    }
    attempts++;
    return true;
  }
}
