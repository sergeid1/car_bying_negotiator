package com.dealanalyzer.mandate;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Audits mandate expiry promptly even when nobody opens the case; expiry already applies at read time. */
@Component
class MandateExpiryWorker {
  private final MandateService mandates;
  MandateExpiryWorker(MandateService mandates) { this.mandates = mandates; }

  @Scheduled(fixedDelayString = "${negotiation.mandate.expiry-sweep:PT1M}")
  void runOnce() { mandates.recordExpirations(Instant.now()); }
}
