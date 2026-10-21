package com.dealanalyzer.notification;

import com.dealanalyzer.mandate.MandateService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Warns the account holder once per mandate version before it expires. Only active mandates on cases
 * attached to an account qualify; a mandate whose whole lifetime fits inside the notice window was
 * approved knowing its expiry and gets no warning. The mandate ID is the stable dedupe key.
 */
@Component
class MandateExpiryNotifier {
  private final MandateService mandates;
  private final BuyerNotifications notifications;
  private final JdbcTemplate jdbc;
  private final Duration notice;

  MandateExpiryNotifier(MandateService mandates, BuyerNotifications notifications, JdbcTemplate jdbc,
      @Value("${account.notifications.mandate-expiring-notice:PT24H}") Duration notice) {
    if (notice.isNegative() || notice.isZero()) throw new IllegalArgumentException("Mandate expiry notice must be positive");
    this.mandates = mandates; this.notifications = notifications; this.jdbc = jdbc; this.notice = notice;
  }

  @Scheduled(fixedDelayString = "${account.notifications.mandate-expiring-sweep:PT5M}")
  void runOnce() { notifyExpiring(Instant.now()); }

  int notifyExpiring(Instant now) {
    int attempted = 0;
    for (var mandate : mandates.expiringBetween(now, now.plus(notice))) {
      if (!mandate.approvedAt().isBefore(mandate.expiresAt().minus(notice))) continue;
      var account = jdbc.queryForList("select account_id from buyer_account_cases where case_id = ?",
          String.class, mandate.caseId());
      if (account.isEmpty()) continue;
      notifications.notifyCase(account.getFirst(), NotificationType.MANDATE_EXPIRING, "mandate-expiring:" + mandate.id(), mandate.caseId());
      attempted++;
    }
    return attempted;
  }
}
