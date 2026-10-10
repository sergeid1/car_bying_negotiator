package com.dealanalyzer.email;

import com.dealanalyzer.buyingcase.CaseAccessService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Only keyed address hashes and fixed reasons are retained; no SNS payloads or addresses. */
@Service
public class EmailSuppression {
  private final JdbcTemplate jdbc;
  private final CaseAccessService secrets;
  public EmailSuppression(JdbcTemplate jdbc, CaseAccessService secrets) {
    this.jdbc = jdbc; this.secrets = secrets;
  }
  public boolean suppressed(String address) {
    return jdbc.queryForObject("select count(*) from email_suppressions where email_hash = ?",
        Long.class, hash(address)) > 0;
  }
  public void suppress(String address, String reason) {
    if (!reason.equals("BOUNCE") && !reason.equals("COMPLAINT")) throw new IllegalArgumentException("Invalid suppression reason");
    String hash = hash(address);
    if (jdbc.queryForObject("select count(*) from email_suppressions where email_hash = ?", Long.class, hash) > 0) return;
    try {
      jdbc.update("insert into email_suppressions (email_hash, reason, created_at) values (?, ?, ?)",
          hash, reason, Timestamp.from(Instant.now()));
    } catch (DuplicateKeyException duplicate) { /* Concurrent SNS retries are idempotent. */ }
  }
  private String hash(String address) {
    return secrets.protectAccountSecret("email", address.strip().toLowerCase(Locale.ROOT));
  }
}
