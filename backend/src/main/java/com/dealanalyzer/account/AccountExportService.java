package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.persistence.CaseExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the account export on request. Nothing is persisted, so there is no extra copy to delete. */
@Service
public class AccountExportService {
  private final BuyerAccountService accounts;
  private final AccountSessionRepository sessions;
  private final CaseExportService caseExports;
  private final ObjectMapper json;
  private final com.dealanalyzer.notification.BuyerNotifications notifications;
  private final com.dealanalyzer.journey.JourneyService journeys;
  private final com.dealanalyzer.operations.ProviderUsageStore providerUsage;
  private final com.dealanalyzer.mailbox.MailboxConnections mailboxes;
  private final com.dealanalyzer.mailbox.MailboxSendService mailboxSends;
  private final PasskeyService passkeys;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  AccountExportService(BuyerAccountService accounts, AccountSessionRepository sessions, CaseExportService caseExports,
      ObjectMapper json, com.dealanalyzer.notification.BuyerNotifications notifications,
      com.dealanalyzer.journey.JourneyService journeys, com.dealanalyzer.operations.ProviderUsageStore providerUsage,
      com.dealanalyzer.mailbox.MailboxConnections mailboxes, com.dealanalyzer.mailbox.MailboxSendService mailboxSends,
      PasskeyService passkeys, org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.accounts = accounts; this.sessions = sessions; this.caseExports = caseExports; this.json = json;
    this.notifications = notifications; this.journeys = journeys;
    this.providerUsage = providerUsage; this.mailboxes = mailboxes; this.mailboxSends = mailboxSends;
    this.passkeys = passkeys; this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public byte[] export(String sessionToken) {
    var session = accounts.requireSession(sessionToken);
    var view = accounts.view(sessionToken);
    var data = new LinkedHashMap<String, Object>();
    data.put("schemaVersion", "buyer-account-export.v1");
    data.put("contact", view.contact());
    data.put("createdAt", view.createdAt());
    data.put("passkeys", passkeys.metadata(session.accountId));
    data.put("accountSecurityEvents", jdbc.queryForList("select event_type, created_at from buyer_account_security_events where account_id = ? order by created_at", session.accountId));
    data.put("notificationPreferences", notifications.preferencesForAccount(session.accountId));
    data.put("notificationDeliveries", notifications.deliveryHistory(session.accountId));
    data.put("sessions", sessions.findByAccountIdOrderByCreatedAt(session.accountId).stream()
        .map(s -> { var row = new LinkedHashMap<String, Object>(); row.put("createdAt", s.createdAt);
          row.put("expiresAt", s.expiresAt); row.put("revokedAt", s.revokedAt); return row; }).toList());
    data.put("buyingJourneys", journeys.exportForAccount(session.accountId));
    data.put("inventorySearchRuns", providerUsage.inventoryForAccount(session.accountId));
    data.put("journeyMarketRetrievals", providerUsage.marketForAccount(session.accountId));
    // Provider and address only: mailbox tokens are never exported.
    data.put("mailboxConnections", mailboxes.exportForAccount(session.accountId));
    data.put("mailboxSends", mailboxSends.exportForAccount(session.accountId));
    var archives = new LinkedHashMap<String, byte[]>();
    List<Map<String, Object>> cases = new ArrayList<>();
    for (var attached : view.cases()) {
      var archive = caseExports.accountArchive(attached.caseId());
      var row = new LinkedHashMap<String, Object>();
      row.put("caseId", attached.caseId()); row.put("attachedAt", attached.attachedAt());
      row.put("state", attached.state()); row.put("export", archive.status().name());
      if (archive.status() == CaseExportService.ArchiveStatus.INCLUDED) {
        String path = "cases/buying-case-" + attached.caseId() + ".zip";
        row.put("file", path); archives.put(path, archive.bytes());
      }
      cases.add(row);
    }
    data.put("cases", cases);
    try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
      entry(zip, "README.txt", ("Buyer Account export\naccount.json holds your contact details, sign-in history "
          + "(times only), email preferences, notification delivery states, inventory search history including search criteria, every Buying Journey with every version of its Shopping Brief, Reference Vehicles, captured candidates, market checks, shortlist positions and linked Buying Cases, a connected Gmail or Outlook account (provider and address) "
          + "with the times and outcomes of messages sent through it, and the Buying Cases attached to the account. Each attached case that can be exported is "
          + "included as its own case export under cases/, with the same contents as exporting that case directly. "
          + "A case marked PURCHASE_REQUIRED has saved reports that are exported once the report is purchased; "
          + "a case marked UNAVAILABLE has expired or is being deleted. Sign-in links, session credentials and "
          + "case links and email connection tokens are excluded.\n").getBytes(StandardCharsets.UTF_8));
      entry(zip, "account.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
      for (var archive : archives.entrySet()) entry(zip, archive.getKey(), archive.getValue());
      zip.finish(); return output.toByteArray();
    } catch (IOException failure) { throw new IllegalStateException("Account export unavailable; retry safely", failure); }
  }

  private void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
    var entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
  }
}
