package com.dealanalyzer.notification;

import com.dealanalyzer.account.AccountNotificationAccess;
import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.recovery.RecoveryEmail;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** At-most-once delivery attempts. Uncertain delivery is retained rather than risking duplicate mail. */
@Service
public class BuyerNotifications {
  public record Preference(NotificationType type, boolean enabled, boolean mandatory) {}
  private final JdbcTemplate jdbc;
  private final AccountNotificationAccess accounts;
  private final CaseAccessService secrets;
  private final RecoveryEmail email;
  private final TransactionTemplate transaction;
  private final String journey;
  private final int hourlyLimit;

  BuyerNotifications(JdbcTemplate jdbc, AccountNotificationAccess accounts, CaseAccessService secrets,
      RecoveryEmail email, PlatformTransactionManager manager,
      @Value("${case.recovery.public-url:http://localhost:5173/}") String origin,
      @Value("${account.notifications.hourly-limit:10}") int hourlyLimit) {
    if (hourlyLimit < 1) throw new IllegalArgumentException("Notification limit must be positive");
    this.jdbc = jdbc; this.accounts = accounts; this.secrets = secrets; this.email = email;
    this.hourlyLimit = hourlyLimit;
    // No case credential or sign-in token: opening the account still requires sign-in.
    this.journey = origin.split("#", 2)[0];
    this.transaction = new TransactionTemplate(manager);
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public List<Preference> preferences(String session) { return preferencesForAccount(accounts.accountId(session)); }

  public List<Preference> update(String session, NotificationType type, boolean enabled) {
    String id = accounts.accountId(session);
    if (type.mandatory()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account-security emails are required.");
    return transaction.execute(status -> {
      if (accounts.lockRecipient(id) == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again.");
      int changed = jdbc.update("update buyer_notification_preferences set enabled = ? where account_id = ? and notification_type = ?",
          enabled, id, type.name());
      if (changed == 0) jdbc.update("insert into buyer_notification_preferences (account_id, notification_type, enabled) values (?, ?, ?)",
          id, type.name(), enabled);
      return preferencesForAccount(id);
    });
  }

  public List<Preference> preferencesForAccount(String id) {
    return Arrays.stream(NotificationType.values()).map(type -> new Preference(type, enabled(id, type), type.mandatory())).toList();
  }

  public List<Map<String, Object>> deliveryHistory(String id) {
    return jdbc.queryForList("select notification_type, state, created_at, completed_at from buyer_notification_deliveries where account_id = ? order by created_at, id", id);
  }

  private boolean enabled(String id, NotificationType type) {
    if (type.mandatory()) return true;
    var values = jdbc.queryForList("select enabled from buyer_notification_preferences where account_id = ? and notification_type = ?", Boolean.class, id, type.name());
    return values.isEmpty() || values.getFirst();
  }

  /**
   * Call after the producer's event transaction commits. Trusted producers supply a stable event key.
   * Never pass event content; the key is stored only as a keyed hash. SENT means accepted by the adapter,
   * not confirmed inbox receipt. Failed and uncertain attempts are not retried automatically.
   */
  public void notify(String accountId, NotificationType type, String dedupeKey) {
    notifyInLanguage(accountId, type, dedupeKey, accounts.preferredLanguage(accountId));
  }

  public void notifyCase(String accountId, NotificationType type, String dedupeKey, String caseId) {
    var language = jdbc.queryForList("select language from analysis_cases where id = ? and lifecycle = 'ACTIVE'", String.class, caseId);
    if (language.isEmpty()) return;
    notifyInLanguage(accountId, type, dedupeKey, language.getFirst());
  }

  private void notifyInLanguage(String accountId, NotificationType type, String dedupeKey, String language) {
    if (type == null || dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > 200)
      throw new IllegalArgumentException("A notification type and stable event key are required");
    String hash = secrets.protectAccountSecret("notification", dedupeKey);
    String delivery = transaction.execute(status -> {
      if (accounts.lockRecipient(accountId) == null) return null;
      if (jdbc.queryForObject("select count(*) from buyer_notification_deliveries where account_id = ? and notification_type = ? and dedupe_hash = ?",
          Long.class, accountId, type.name(), hash) > 0) return null;
      Instant now = Instant.now();
      String state = "DELIVERY_UNKNOWN";
      if (!enabled(accountId, type)) state = "OPTED_OUT";
      else {
        // Separate security quota prevents optional events exhausting mandatory email capacity.
        long used = jdbc.queryForObject("select count(*) from buyer_notification_deliveries where account_id = ? and created_at > ? and state in ('DELIVERY_UNKNOWN', 'SENT', 'FAILED') and "
            + (type.mandatory() ? "notification_type = 'ACCOUNT_SECURITY'" : "notification_type <> 'ACCOUNT_SECURITY'"),
            Long.class, accountId, Timestamp.from(now.minus(Duration.ofHours(1))));
        if (used >= hourlyLimit) state = "RATE_LIMITED";
      }
      String id = UUID.randomUUID().toString();
      jdbc.update("insert into buyer_notification_deliveries (id, account_id, notification_type, dedupe_hash, state, created_at, completed_at) values (?, ?, ?, ?, ?, ?, ?)",
          id, accountId, type.name(), hash, state, Timestamp.from(now), "DELIVERY_UNKNOWN".equals(state) ? null : Timestamp.from(now));
      return "DELIVERY_UNKNOWN".equals(state) ? id : null;
    });
    if (delivery == null) return;
    transaction.executeWithoutResult(status -> {
      // Serialize with deletion and preference changes; no email after deletion has started.
      String recipient = accounts.lockRecipient(accountId);
      if (recipient == null) return;
      String state;
      if (!enabled(accountId, type)) state = "OPTED_OUT";
      else {
        try {
          if ("es".equals(language)) email.send(recipient, journey, RecoveryEmail.Purpose.valueOf(type.name()), "es");
          else email.send(recipient, journey, RecoveryEmail.Purpose.valueOf(type.name()));
          state = "SENT";
        }
        catch (RuntimeException failure) { state = "FAILED"; } // Never log provider payloads or addresses.
      }
      jdbc.update("update buyer_notification_deliveries set state = ?, completed_at = ? where id = ?",
          state, Timestamp.from(Instant.now()), delivery);
    });
  }
}
