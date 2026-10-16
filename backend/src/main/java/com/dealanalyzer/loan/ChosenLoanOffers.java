package com.dealanalyzer.loan;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The loan offer a Buyer chose as their own financing for a case (LOAN-03): the pre-approval, or the offer they
 * entered from a non-participating lender, held by the case's application or its journey's. Callers have already
 * authorized the case. It carries only the offer's terms; nothing here reads or knows what a lender pays otdog.
 */
@Component
public class ChosenLoanOffers {
  /** source: PARTNER (a pre-approval through the licensed partner) or BUYER_ENTERED. */
  public record Chosen(String offerId, String lender, String source, long amountMinor, BigDecimal aprPercent, int termMonths,
      long upfrontFeesMinor, Long lenderPaymentMinor, Instant expiresAt, Instant chosenAt) {}

  private final JdbcTemplate jdbc;

  public ChosenLoanOffers(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** The case's chosen offer while it has not expired; an expired pre-approval is no longer an offer. */
  public Optional<Chosen> forCase(String caseId, Instant now) {
    return jdbc.query("""
        select o.id, o.lender, o.source, o.amount_minor, o.apr_percent, o.term_months, o.upfront_fees_minor,
          o.lender_payment_minor, o.expires_at, a.chosen_at
        from loan_applications a join loan_offers o on o.id = a.chosen_offer_id and o.application_id = a.id
        where o.outcome = 'OFFERED' and (a.case_id = ? or a.journey_id in (
          select journey_id from journey_candidate_cases where case_id = ?
          union select journey_id from trade_case_journeys where case_id = ?))
        order by a.chosen_at desc, o.id
        """, (r, n) -> new Chosen(r.getString(1), r.getString(2), r.getString(3), r.getLong(4), LoanShoppingService.plain(r.getBigDecimal(5)),
            r.getInt(6), r.getLong(7), (Long) r.getObject(8, Long.class), instant(r.getTimestamp(9)), instant(r.getTimestamp(10))),
        caseId, caseId, caseId).stream().findFirst().filter(chosen -> chosen.expiresAt() == null || chosen.expiresAt().isAfter(now));
  }

  private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
