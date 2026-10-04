package com.dealanalyzer.appointment;

import com.dealanalyzer.notification.BuyerNotifications;
import com.dealanalyzer.notification.NotificationType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reminds the account holder once per booked test drive when it is within the notice window. The email
 * carries only the fixed appointment prompt; the appointment ID is the stable dedupe key.
 */
@Component
class TestDriveReminder {
  private final BuyerNotifications notifications;
  private final JdbcTemplate jdbc;
  private final Duration notice;

  TestDriveReminder(BuyerNotifications notifications, JdbcTemplate jdbc,
      @Value("${account.notifications.test-drive-reminder-notice:PT24H}") Duration notice) {
    if (notice.isNegative() || notice.isZero()) throw new IllegalArgumentException("Test-drive reminder notice must be positive");
    this.notifications = notifications; this.jdbc = jdbc; this.notice = notice;
  }

  @Scheduled(fixedDelayString = "${account.notifications.test-drive-reminder-sweep:PT5M}")
  void runOnce() { remind(Instant.now()); }

  int remind(Instant now) {
    var due = jdbc.query("""
        select a.id, b.account_id from test_drive_appointments a
        join buyer_account_cases b on b.case_id = a.case_id
        join analysis_cases c on c.id = a.case_id and c.lifecycle = 'ACTIVE'
        where a.state = 'SCHEDULED' and a.starts_at > ? and a.starts_at <= ?
        """, (row, n) -> new String[] {row.getString(1), row.getString(2)}, Timestamp.from(now), Timestamp.from(now.plus(notice)));
    for (String[] appointment : due)
      notifications.notify(appointment[1], NotificationType.APPOINTMENT, "appointment-reminder:" + appointment[0]);
    return due.size();
  }
}
