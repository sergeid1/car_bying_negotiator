package com.dealanalyzer.pilot;

import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * First-party acquisition channels. The channel code is the only acquisition datum kept with a
 * case; analysis, reports and pricing never read it. See docs/pilot-metrics-policy.md.
 */
@Service
public class AcquisitionChannels {
  static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
  /** Dealer-paid lead and dealer outreach kinds do not exist here and cannot be configured. */
  public enum Kind { EXPLAINER_CONTENT, REFERRAL_PARTNERSHIP }
  public record Channel(@NotNull Kind kind, @Size(max = 600) String referralDisclosure, boolean active) {}
  public record Spend(@NotNull UUID spendId, @NotNull LocalDate periodStart, @NotNull LocalDate periodEnd,
      @Min(0) @Max(100000000000L) long amountMinor, @NotBlank String currency) {}

  private final EntityManager manager;
  AcquisitionChannels(EntityManager manager) { this.manager = manager; }

  /**
   * Returns the code only when it is well formed and on the active allow list. Anything else,
   * including a lookup failure, is no channel: attribution never fails or delays case creation.
   */
  public Optional<String> admit(String code) {
    if (code == null || !CODE.matcher(code).matches()) return Optional.empty();
    try {
      long active = ((Number) manager.createNativeQuery("select count(*) from acquisition_channels where code = :code and active = true")
          .setParameter("code", code).getSingleResult()).longValue();
      return active == 1 ? Optional.of(code) : Optional.empty();
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  /** The configured disclosure for a case started from a referral partnership, shown before upload. */
  @Transactional(readOnly = true)
  public Optional<String> referralDisclosure(String caseId) {
    @SuppressWarnings("unchecked") List<Object> rows = manager.createNativeQuery(
        "select ch.referral_disclosure from analysis_cases c join acquisition_channels ch on ch.code = c.acquisition_channel"
            + " where c.id = :caseId and ch.kind = 'REFERRAL_PARTNERSHIP'")
        .setParameter("caseId", caseId).getResultList();
    return rows.stream().findFirst().map(String::valueOf);
  }

  @Transactional
  public Map<String, Object> configure(String code, Channel input, String actor) {
    if (!CODE.matcher(code).matches()) throw badRequest();
    String disclosure = input.referralDisclosure() == null ? null : input.referralDisclosure().strip();
    boolean referral = input.kind() == Kind.REFERRAL_PARTNERSHIP;
    if (referral != (disclosure != null && !disclosure.isEmpty())) throw badRequest();
    var now = Instant.now();
    @SuppressWarnings("unchecked") List<Object> existing = manager.createNativeQuery(
        "select kind from acquisition_channels where code = :code for update").setParameter("code", code).getResultList();
    if (existing.isEmpty()) {
      manager.createNativeQuery("insert into acquisition_channels(code, kind, referral_disclosure, active, updated_by, created_at, updated_at)"
          + " values (:code, :kind, :disclosure, :active, :actor, :now, :now)")
          .setParameter("code", code).setParameter("kind", input.kind().name()).setParameter("disclosure", disclosure)
          .setParameter("active", input.active()).setParameter("actor", actor).setParameter("now", now).executeUpdate();
      manager.createNativeQuery("insert into acquisition_channel_totals(channel_key) values (:code)").setParameter("code", code).executeUpdate();
    } else {
      // A channel's kind is fixed once cases may carry it; a different kind needs a new code.
      if (!input.kind().name().equals(existing.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT);
      manager.createNativeQuery("update acquisition_channels set referral_disclosure = :disclosure, active = :active,"
          + " updated_by = :actor, updated_at = :now where code = :code")
          .setParameter("code", code).setParameter("disclosure", disclosure).setParameter("active", input.active())
          .setParameter("actor", actor).setParameter("now", now).executeUpdate();
    }
    return channels().stream().filter(row -> code.equals(row.get("code"))).findFirst().orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> channels() {
    @SuppressWarnings("unchecked") List<Object[]> rows = manager.createNativeQuery(
        "select code, kind, referral_disclosure, active, updated_by, updated_at from acquisition_channels order by code").getResultList();
    return rows.stream().map(row -> {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("code", row[0]); value.put("kind", row[1]); value.put("referralDisclosure", row[2]);
      value.put("active", row[3]); value.put("updatedBy", row[4]); value.put("updatedAt", row[5]);
      return value;
    }).toList();
  }

  @Transactional
  public Map<String, Object> recordSpend(String code, Spend input, String actor) {
    if (!"USD".equals(input.currency()) || input.periodEnd().isBefore(input.periodStart())) throw badRequest();
    long configured = ((Number) manager.createNativeQuery("select count(*) from acquisition_channels where code = :code")
        .setParameter("code", code).getSingleResult()).longValue();
    if (configured != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    String id = input.spendId().toString();
    @SuppressWarnings("unchecked") List<Object[]> existing = manager.createNativeQuery(
        "select channel_code, period_start, period_end, amount_minor from acquisition_channel_spend where id = :id")
        .setParameter("id", id).getResultList();
    if (!existing.isEmpty()) {
      // Identical retries do not double count; a changed retry is a conflict, never an edit.
      var row = existing.get(0);
      if (!code.equals(row[0]) || !input.periodStart().equals(date(row[1])) || !input.periodEnd().equals(date(row[2]))
          || ((Number) row[3]).longValue() != input.amountMinor()) throw new ResponseStatusException(HttpStatus.CONFLICT);
    } else {
      manager.createNativeQuery("insert into acquisition_channel_spend(id, channel_code, period_start, period_end, amount_minor, currency, recorded_by, created_at)"
          + " values (:id, :code, :start, :end, :amount, 'USD', :actor, :now)")
          .setParameter("id", id).setParameter("code", code).setParameter("start", input.periodStart())
          .setParameter("end", input.periodEnd()).setParameter("amount", input.amountMinor())
          .setParameter("actor", actor).setParameter("now", Instant.now()).executeUpdate();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("spendId", id); result.put("channel", code); result.put("periodStart", input.periodStart());
    result.put("periodEnd", input.periodEnd()); result.put("amountMinor", input.amountMinor()); result.put("currency", "USD");
    return result;
  }

  private static LocalDate date(Object value) {
    return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
  }
  private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
