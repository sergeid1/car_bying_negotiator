package com.dealanalyzer.loan;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.loan.preapproval.CreditAuthorization;
import com.dealanalyzer.loan.preapproval.LoanApplication;
import com.dealanalyzer.loan.preapproval.PreApprovalAdapter;
import com.dealanalyzer.loan.preapproval.PreApprovalContract;
import com.dealanalyzer.loan.preapproval.PreApprovalService;
import com.dealanalyzer.partner.PartnerOutcome;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Buyer's loan application and the pre-approvals it brought back (spec 5.10, LOAN-01 to LOAN-03). A case in a
 * Buying Journey uses the journey's one application; a case without a journey holds its own, and a journey can hold
 * one without any case. The application goes only to the licensed partner behind {@link PreApprovalService}, and
 * only after the Buyer gave the written credit authorization as it is shown now. Offers are costed and ordered by
 * {@link PreApprovalContract}, which reads amount, APR, term, fees and lender name only. The offer the Buyer chooses
 * is their own financing for FIN-04 and DESK-04 ({@link ChosenLoanOffers}). Nothing here logs, and no problem echoes
 * what the Buyer entered.
 */
@Service
public class LoanShoppingService {
  /** The pilot jurisdiction's calendar, for the dates a Buyer types on an offer. */
  static final ZoneId CALENDAR = ZoneId.of("America/New_York");
  static final String SCHEMA = "loan-application.v1";
  static final long MAX_AMOUNT_MINOR = 50_000_000L;
  static final long MAX_INCOME_MINOR = 100_000_000L;
  static final long MAX_HOUSING_MINOR = 10_000_000L;
  static final long MAX_FEES_MINOR = 1_000_000L;
  static final int MAX_TERM_MONTHS = 96;
  /**
   * The written FCRA authorization the Buyer gives before anything is sent. A draft for counsel and the partner's
   * terms (tickets 123 and 130); changing the words means a new version, and a request naming an older version sends
   * nothing.
   */
  public static final String AUTHORIZATION_VERSION = "otdog-credit-authorization.draft-1";

  public record VehicleInput(String vin, Integer modelYear, String make, String model, Long mileage) {}
  /** {@code housing} is OWN, RENT or OTHER. Identity details are not part of it (spec section 13). */
  public record Input(Integer basedOnRevision, Long amountMinor, Integer termMonths, VehicleInput vehicle,
      Long annualIncomeMinor, String housing, Long monthlyHousingCostMinor) {}
  public record RequestInput(String authorizationTextVersion, Boolean authorized) {}
  /** An offer the Buyer got from a lender outside the partner, such as their credit union (PAY-02). */
  public record OwnOfferInput(String lender, Long amountMinor, BigDecimal aprPercent, Integer termMonths, Long upfrontFeesMinor,
      Long lenderPaymentMinor, String offeredOn, String expiresOn) {}
  public record ChoiceInput(String offerId) {}

  /** The validated application as stored. */
  public record Application(long amountMinor, int termMonths, VehicleInput vehicle, long annualIncomeMinor, String housing,
      long monthlyHousingCostMinor) {}

  public record View(String holder, CaseVehicle caseVehicle, Integer revision, Instant changedAt, Application application,
      Authorization authorization, Results results, ChosenOffer chosen) {}
  public record CaseVehicle(String vin, Integer modelYear, String make, String model, Long mileage) {}
  public record Authorization(String version, String partner, boolean synthetic, List<String> text) {}
  /**
   * {@code sameTerms} is ordered by total cost for the application's amount and term; {@code differentTerms},
   * {@code expired} and {@code withoutOffer} are listed by lender name and carry no rank.
   */
  public record Results(Request request, List<OfferRow> sameTerms, List<OfferRow> differentTerms, List<OfferRow> expired,
      List<Outcome> withoutOffer) {}
  public record Request(String id, Instant requestedAt, Instant retrievedAt, String partner, boolean synthetic,
      String availability, String notice, boolean outdated) {}
  /** Payment, interest and total cost are FIN-01's; the lender's payment is shown beside it as a reconciliation. */
  public record OfferRow(String id, String lender, String source, long amountMinor, BigDecimal aprPercent, int termMonths,
      long upfrontFeesMinor, Long paymentMinor, Long totalInterestMinor, Long totalCostMinor, Long lenderPaymentMinor,
      Long lenderMinusCalculatedMinor, Boolean lenderPaymentWithinTolerance, Conditions conditions, Instant offeredAt,
      Instant expiresAt, String continueUrl, List<String> differences, boolean chosen) {}
  public record Conditions(Integer maximumVehicleAgeYears, Long maximumMileage, Long minimumDownPaymentMinor) {}
  public record Outcome(String lender, PartnerOutcome outcome) {}
  /** The offer the Buyer chose as their own financing; {@code expired} offers are no longer used at the desk. */
  public record ChosenOffer(String offerId, String lender, String source, long amountMinor, BigDecimal aprPercent, int termMonths,
      long upfrontFeesMinor, Instant expiresAt, boolean expired, Instant chosenAt) {}

  /** Who holds the application: a journey (with its account) or a case without a journey. */
  record Holder(String caseId, String journeyId, String accountId) {
    boolean journey() { return journeyId != null; }
  }
  private record Row(String id, int revision, Application application, Instant changedAt, String chosenOfferId, Instant chosenAt) {}
  private record OfferRecord(String id, String source, String lender, PartnerOutcome outcome, Long amountMinor, BigDecimal aprPercent,
      Integer termMonths, Long upfrontFeesMinor, Long lenderPaymentMinor, Conditions conditions, Instant offeredAt, Instant expiresAt,
      String continueUrl) {}

  private final EphemeralBuyingCaseService cases;
  private final AccountJourneyAccess accounts;
  private final PreApprovalService preApprovals;
  private final PreApprovalAdapter adapter;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate reads;
  private final TransactionTemplate writes;

  LoanShoppingService(EphemeralBuyingCaseService cases, AccountJourneyAccess accounts, PreApprovalService preApprovals,
      PreApprovalAdapter adapter, JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
    this.cases = cases; this.accounts = accounts; this.preApprovals = preApprovals; this.adapter = adapter; this.jdbc = jdbc;
    this.json = json;
    this.reads = new TransactionTemplate(transactions); this.reads.setReadOnly(true);
    this.writes = new TransactionTemplate(transactions);
  }

  /** The case credential authorizes; a case in a journey uses the journey's application. */
  Holder forCase(String caseId, String token) {
    cases.reopen(caseId, token);
    return reads.execute(status -> jdbc.query("""
        select journey_id, account_id from journey_candidate_cases where case_id = ?
        union all select journey_id, account_id from trade_case_journeys where case_id = ?
        """, (r, n) -> new Holder(caseId, r.getString(1), r.getString(2)), caseId, caseId)
        .stream().findFirst().orElse(new Holder(caseId, null, null)));
  }

  /** The account session authorizes, and the journey must be the account's own. */
  Holder forJourney(String session, String journeyId) {
    String accountId = accounts.accountId(session);
    if (journeyId == null || journeyId.length() > 36 || reads.execute(status -> jdbc.queryForList(
        "select id from buying_journeys where id = ? and account_id = ?", String.class, journeyId, accountId)).isEmpty())
      throw new ResourceNotFoundException("Buying Journey not found");
    return new Holder(null, journeyId, accountId);
  }

  View view(Holder holder) { return reads.execute(status -> render(holder)); }

  /**
   * Stores the application. {@code basedOnRevision} is the revision the Buyer edited: a stale edit is refused, and
   * sending the same details again changes nothing.
   */
  View store(Holder holder, Input input) {
    var application = validate(input);
    String stored = write(application);
    return writes.execute(status -> {
      lock(holder);
      var current = application(holder);
      Instant now = Instant.now();
      if (current.isEmpty()) {
        jdbc.update("""
            insert into loan_applications(id, journey_id, account_id, case_id, application_json, schema_version, revision,
              changed_at, created_at)
            values (?, ?, ?, ?, ?, ?, 1, ?, ?)
            """, UUID.randomUUID().toString(), holder.journeyId(), holder.accountId(), holder.journey() ? null : holder.caseId(),
            stored, SCHEMA, Timestamp.from(now), Timestamp.from(now));
      } else {
        var row = current.get();
        if (row.application().equals(application)) return render(holder);
        if (input.basedOnRevision() == null || input.basedOnRevision() != row.revision()) throw LoanProblem.changed();
        jdbc.update("update loan_applications set application_json = ?, revision = ?, changed_at = ? where id = ?",
            stored, row.revision() + 1, Timestamp.from(now), row.id());
      }
      return render(holder);
    });
  }

  /** Removes the application with every request and offer it holds, and the Buyer's choice with them. */
  View remove(Holder holder) {
    return writes.execute(status -> {
      lock(holder);
      application(holder).ifPresent(row -> {
        jdbc.update("delete from loan_offers where application_id = ?", row.id());
        jdbc.update("delete from loan_pre_approval_requests where application_id = ?", row.id());
        jdbc.update("delete from loan_applications where id = ?", row.id());
      });
      return render(holder);
    });
  }

  /**
   * Sends the application to the licensed partner, only when the Buyer gave the written authorization shown now
   * (LOAN-01). The partner is called outside any transaction; a retried request with the same key sends nothing.
   * The meter refuses any result that is not a soft inquiry, so such a result keeps no offer.
   */
  View requestPreApprovals(Holder holder, String key, RequestInput input) {
    String keyHash = keyHash(key);
    if (input == null || !Boolean.TRUE.equals(input.authorized()) || !AUTHORIZATION_VERSION.equals(input.authorizationTextVersion()))
      throw LoanProblem.authorizationRequired();
    record Prepared(Row row, View settled) {}
    var prepared = reads.execute(status -> {
      var row = application(holder).orElseThrow(LoanProblem::noApplication);
      boolean replay = Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from loan_pre_approval_requests where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, row.id(), keyHash));
      return new Prepared(row, replay ? render(holder) : null);
    });
    if (prepared.settled() != null) return prepared.settled();
    var row = prepared.row();
    LoanApplication application;
    try { application = domain(row.application()); }
    catch (IllegalArgumentException invalid) { throw LoanProblem.changed(); }
    var authorization = new CreditAuthorization(AUTHORIZATION_VERSION, Instant.now());
    var result = holder.journey()
        ? preApprovals.request(null, holder.accountId(), holder.journeyId(), application, authorization)
        : preApprovals.request(holder.caseId(), null, null, application, authorization);
    return writes.execute(status -> {
      lock(holder);
      var current = application(holder).filter(held -> held.id().equals(row.id())).orElseThrow(LoanProblem::changed);
      if (Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from loan_pre_approval_requests where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, current.id(), keyHash))) return render(holder);
      String requestId = UUID.randomUUID().toString();
      Instant now = Instant.now();
      var provider = result.provider();
      jdbc.update("""
          insert into loan_pre_approval_requests(id, application_id, account_id, idempotency_key_hash, application_revision,
            authorization_text_version, authorized_at, provider, provider_version, licence_metadata, synthetic, availability,
            reason, retrieved_at, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, requestId, current.id(), holder.accountId(), keyHash, row.revision(), authorization.authorizationTextVersion(),
          Timestamp.from(authorization.authorizedAt()), provider.name(), provider.version(), provider.licenceMetadata(),
          provider.synthetic(), result.availability().name(), result.reason() == null ? null : result.reason().name(),
          Timestamp.from(result.retrievedAt()), Timestamp.from(now));
      for (var entry : result.lenders()) {
        var offer = entry.preApproval();
        if (offer != null && offer.inquiryType() != PreApprovalAdapter.InquiryType.SOFT) continue; // Never kept (LOAN-01).
        var conditions = offer == null ? null : offer.conditions();
        jdbc.update("""
            insert into loan_offers(id, application_id, request_id, account_id, source, idempotency_key_hash, lender, outcome,
              amount_minor, apr_percent, term_months, upfront_fees_minor, lender_payment_minor, maximum_vehicle_age_years,
              maximum_mileage, minimum_down_payment_minor, inquiry_type, offered_at, expires_at, continue_url, created_at)
            values (?, ?, ?, ?, 'PARTNER', null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), current.id(), requestId, holder.accountId(), entry.lenderName(), entry.outcome().name(),
            offer == null ? null : offer.amountMinor(), offer == null ? null : offer.aprPercent(),
            offer == null ? null : offer.termMonths(), offer == null ? null : offer.upfrontFeesMinor(),
            offer == null ? null : offer.lenderPaymentMinor(), conditions == null ? null : conditions.maximumVehicleAgeYears(),
            conditions == null ? null : conditions.maximumMileage(), conditions == null ? null : conditions.minimumDownPaymentMinor(),
            offer == null ? null : offer.inquiryType().name(), offer == null ? null : Timestamp.from(offer.source().observedAt()),
            offer == null ? null : Timestamp.from(offer.expiresAt()), offer == null ? null : offer.continueUrl().toString(),
            Timestamp.from(now));
      }
      return render(holder);
    });
  }

  /** Adds an offer the Buyer got outside the partner; it is costed and ordered on the same terms (PAY-02). */
  View addOwnOffer(Holder holder, String key, OwnOfferInput input) {
    String keyHash = keyHash(key);
    var given = input == null ? new OwnOfferInput(null, null, null, null, null, null, null, null) : input;
    return writes.execute(status -> {
      lock(holder);
      var row = application(holder).orElseThrow(LoanProblem::noApplication);
      if (Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from loan_offers where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, row.id(), keyHash))) return render(holder);
      var errors = new ArrayList<LoanProblem.FieldError>();
      String lender = text(given.lender());
      if (lender == null) errors.add(error("lender", "Enter the lender's name, up to 100 characters."));
      long amount = given.amountMinor() == null ? row.application().amountMinor() : given.amountMinor();
      if (amount <= 0 || amount > MAX_AMOUNT_MINOR) errors.add(error("amountMinor", "Enter the amount offered, up to $500,000."));
      BigDecimal apr = given.aprPercent();
      if (!validApr(apr)) errors.add(error("aprPercent", "Enter the APR as a percentage from 0 to 100."));
      int term = given.termMonths() == null ? row.application().termMonths() : given.termMonths();
      if (term < 1 || term > MAX_TERM_MONTHS) errors.add(error("termMonths", "Enter the term in months, from 1 to " + MAX_TERM_MONTHS + "."));
      long fees = given.upfrontFeesMinor() == null ? 0 : given.upfrontFeesMinor();
      if (fees < 0 || fees > MAX_FEES_MINOR) errors.add(error("upfrontFeesMinor", "Enter the lender's upfront fees, up to $10,000, or leave it empty."));
      Long payment = given.lenderPaymentMinor();
      if (payment != null && (payment <= 0 || payment > MAX_AMOUNT_MINOR))
        errors.add(error("lenderPaymentMinor", "Enter the monthly payment the lender stated, or leave it empty."));
      LocalDate today = LocalDate.now(CALENDAR);
      LocalDate offeredOn = given.offeredOn() == null || given.offeredOn().isBlank() ? today
          : date(given.offeredOn(), LocalDate.of(2000, 1, 1), today);
      if (offeredOn == null) errors.add(error("offeredOn", "Enter the date of the offer; it cannot be in the future."));
      LocalDate expiresOn = null;
      if (given.expiresOn() != null && !given.expiresOn().isBlank()) {
        LocalDate from = offeredOn == null ? today : offeredOn;
        expiresOn = date(given.expiresOn(), from, from.plusYears(1));
        if (expiresOn == null) errors.add(error("expiresOn", "Enter the date the offer ends, on or after the offer date."));
      }
      if (errors.isEmpty() && PreApprovalContract.calculate(amount, apr, term, fees, payment) == null)
        errors.add(error("aprPercent", "These terms are outside the ordinary monthly loan otdog can calculate. Check the APR and term."));
      if (!errors.isEmpty()) throw LoanProblem.invalid(errors);
      jdbc.update("""
          insert into loan_offers(id, application_id, request_id, account_id, source, idempotency_key_hash, lender, outcome,
            amount_minor, apr_percent, term_months, upfront_fees_minor, lender_payment_minor, offered_at, expires_at, created_at)
          values (?, ?, null, ?, 'BUYER_ENTERED', ?, ?, 'OFFERED', ?, ?, ?, ?, ?, ?, ?, ?)
          """, UUID.randomUUID().toString(), row.id(), holder.accountId(), keyHash, lender, amount, apr, term, fees, payment,
          Timestamp.from(offeredOn.atStartOfDay(CALENDAR).toInstant()),
          expiresOn == null ? null : Timestamp.from(expiresOn.plusDays(1).atStartOfDay(CALENDAR).toInstant()),
          Timestamp.from(Instant.now()));
      return render(holder);
    });
  }

  /** Removes one offer the Buyer entered, and the choice of it; a partner's pre-approval cannot be removed on its own. */
  View removeOwnOffer(Holder holder, String offerId) {
    return writes.execute(status -> {
      lock(holder);
      var row = application(holder).orElseThrow(LoanShoppingService::offerNotFound);
      if (offerId != null && offerId.equals(row.chosenOfferId()))
        jdbc.update("update loan_applications set chosen_offer_id = null, chosen_at = null where id = ?", row.id());
      if (jdbc.update("delete from loan_offers where id = ? and application_id = ? and source = 'BUYER_ENTERED'",
          offerId, row.id()) == 0) throw offerNotFound();
      return render(holder);
    });
  }

  /**
   * "Use this pre-approval": the offer becomes the Buyer's own financing, which the outside Finance Offer (FIN-04)
   * and the desk tactics (DESK-04) use. Only an unexpired offer can be chosen. Choosing the same offer again
   * changes nothing.
   */
  View choose(Holder holder, ChoiceInput input) {
    return writes.execute(status -> {
      lock(holder);
      var row = application(holder).orElseThrow(LoanProblem::noApplication);
      String offerId = input == null ? null : input.offerId();
      if (offerId == null || offerId.length() > 36) throw offerNotFound();
      if (offerId.equals(row.chosenOfferId())) return render(holder);
      var offer = offers(row.id(), "id = ?", offerId).stream().findFirst().orElseThrow(LoanShoppingService::offerNotFound);
      if (offer.outcome() != PartnerOutcome.OFFERED || expired(offer, Instant.now())) throw LoanProblem.notChoosable();
      jdbc.update("update loan_applications set chosen_offer_id = ?, chosen_at = ? where id = ?", offerId,
          Timestamp.from(Instant.now()), row.id());
      return render(holder);
    });
  }

  /** The Buyer continues with none of the offers as their own financing. */
  View clearChoice(Holder holder) {
    return writes.execute(status -> {
      lock(holder);
      application(holder).ifPresent(row ->
          jdbc.update("update loan_applications set chosen_offer_id = null, chosen_at = null where id = ?", row.id()));
      return render(holder);
    });
  }

  private View render(Holder holder) {
    var row = application(holder);
    return new View(holder.journey() ? "JOURNEY" : "CASE", caseVehicle(holder), row.map(Row::revision).orElse(null),
        row.map(Row::changedAt).orElse(null), row.map(Row::application).orElse(null), authorization(),
        row.map(this::results).orElse(null), row.map(this::chosen).orElse(null));
  }

  private Results results(Row row) {
    var request = jdbc.query("""
        select id, created_at, retrieved_at, provider, synthetic, availability, reason, application_revision
        from loan_pre_approval_requests where application_id = ? order by created_at desc, id desc
        """, (r, n) -> {
      var availability = PreApprovalAdapter.Availability.valueOf(r.getString(6));
      var reason = r.getString(7) == null ? null : PreApprovalAdapter.Reason.valueOf(r.getString(7));
      return new Request(r.getString(1), instant(r, 2), instant(r, 3), r.getString(4), r.getBoolean(5), availability.name(),
          notice(availability, reason, r.getBoolean(5)), r.getInt(8) != row.revision());
    }, row.id()).stream().findFirst().orElse(null);
    var records = offers(row.id(), "(source = 'BUYER_ENTERED' or request_id = ?)", request == null ? "" : request.id());
    if (request == null && records.isEmpty()) return null;

    var application = row.application();
    Instant now = Instant.now();
    record Costed(OfferRecord record, PreApprovalContract.Calculation calculation) {}
    var same = new ArrayList<Costed>();
    var different = new ArrayList<OfferRow>();
    var expired = new ArrayList<OfferRow>();
    var without = new ArrayList<Outcome>();
    for (var record : records) {
      if (record.outcome() != PartnerOutcome.OFFERED) { without.add(new Outcome(record.lender(), record.outcome())); continue; }
      var calculation = PreApprovalContract.calculate(record.amountMinor(), record.aprPercent(), record.termMonths(),
          record.upfrontFeesMinor(), record.lenderPaymentMinor());
      var differences = new ArrayList<String>();
      if (record.amountMinor() != application.amountMinor()) differences.add("AMOUNT");
      if (record.termMonths() != application.termMonths()) differences.add("TERM");
      if (expired(record, now)) expired.add(offerRow(record, calculation, differences, row));
      else if (!differences.isEmpty() || calculation == null) different.add(offerRow(record, calculation, differences, row));
      else same.add(new Costed(record, calculation));
    }
    Comparator<OfferRow> byLender = Comparator.comparing((OfferRow offer) -> offer.lender().toLowerCase(Locale.ROOT))
        .thenComparing(OfferRow::lender);
    different.sort(byLender);
    expired.sort(byLender);
    without.sort(Comparator.comparing((Outcome outcome) -> outcome.lender().toLowerCase(Locale.ROOT)).thenComparing(Outcome::lender));
    return new Results(request, same.stream()
        .sorted(PreApprovalContract.byTotalCost((Costed costed) -> costed.calculation().totalCostMinor(), costed -> costed.record().lender()))
        .map(costed -> offerRow(costed.record(), costed.calculation(), List.of(), row)).toList(),
        List.copyOf(different), List.copyOf(expired), List.copyOf(without));
  }

  private static OfferRow offerRow(OfferRecord record, PreApprovalContract.Calculation calculation, List<String> differences, Row row) {
    return new OfferRow(record.id(), record.lender(), record.source(), record.amountMinor(), record.aprPercent(), record.termMonths(),
        record.upfrontFeesMinor(), calculation == null ? null : calculation.paymentMinor(),
        calculation == null ? null : calculation.totalInterestMinor(), calculation == null ? null : calculation.totalCostMinor(),
        record.lenderPaymentMinor(), calculation == null ? null : calculation.lenderMinusCalculatedMinor(),
        calculation == null ? null : calculation.reconciliation().withinTolerance(), record.conditions(), record.offeredAt(),
        record.expiresAt(), record.continueUrl(), List.copyOf(differences), record.id().equals(row.chosenOfferId()));
  }

  private ChosenOffer chosen(Row row) {
    if (row.chosenOfferId() == null) return null;
    return offers(row.id(), "id = ?", row.chosenOfferId()).stream().findFirst().filter(offer -> offer.outcome() == PartnerOutcome.OFFERED)
        .map(offer -> new ChosenOffer(offer.id(), offer.lender(), offer.source(), offer.amountMinor(), offer.aprPercent(),
            offer.termMonths(), offer.upfrontFeesMinor(), offer.expiresAt(), expired(offer, Instant.now()), row.chosenAt()))
        .orElse(null);
  }

  private List<OfferRecord> offers(String applicationId, String condition, String parameter) {
    return jdbc.query("""
        select id, source, lender, outcome, amount_minor, apr_percent, term_months, upfront_fees_minor, lender_payment_minor,
          maximum_vehicle_age_years, maximum_mileage, minimum_down_payment_minor, offered_at, expires_at, continue_url
        from loan_offers where application_id = ? and %s
        """.formatted(condition), (r, n) -> {
      var outcome = PartnerOutcome.valueOf(r.getString(4));
      Integer age = (Integer) r.getObject(10, Integer.class);
      Long mileage = (Long) r.getObject(11, Long.class), down = (Long) r.getObject(12, Long.class);
      return new OfferRecord(r.getString(1), r.getString(2), r.getString(3), outcome, (Long) r.getObject(5, Long.class),
          plain(r.getBigDecimal(6)), (Integer) r.getObject(7, Integer.class), (Long) r.getObject(8, Long.class),
          (Long) r.getObject(9, Long.class), age == null && mileage == null && down == null ? null : new Conditions(age, mileage, down),
          instant(r, 13), instant(r, 14), r.getString(15));
    }, applicationId, parameter);
  }

  private static boolean expired(OfferRecord offer, Instant now) {
    return offer.expiresAt() != null && !offer.expiresAt().isAfter(now);
  }

  private static String notice(PreApprovalAdapter.Availability availability, PreApprovalAdapter.Reason reason, boolean synthetic) {
    if (availability == PreApprovalAdapter.Availability.AVAILABLE)
      return synthetic ? "Synthetic pre-approvals for development only." : null;
    return switch (reason) {
      case TIMED_OUT -> "The lending partner timed out. No offer was substituted.";
      case RATE_LIMITED -> "The lending partner's request limit was reached. No offer was substituted.";
      case NON_SOFT_INQUIRY -> "The lending partner's response was not limited to soft inquiries, so it was refused and none "
          + "of its offers is shown. No offer was substituted.";
      case EXPIRED -> "The lending partner's offers had already expired. No offer was substituted.";
      case INVALID_RESPONSE -> "The lending partner's response was invalid. No offer was substituted.";
      case PROVIDER_UNAVAILABLE -> "The lending partner is unavailable. No offer was substituted.";
    };
  }

  private Authorization authorization() {
    var provider = adapter.provider();
    String partner = provider.synthetic() ? "the synthetic test partner (development only)" : provider.name();
    return new Authorization(AUTHORIZATION_VERSION, partner, provider.synthetic(), List.of(
        "By choosing Get pre-approvals, you give written permission, under the Fair Credit Reporting Act, for " + partner
            + ", a licensed lending partner, and the lenders that work through it to get your credit report with a soft "
            + "inquiry, only to tell you whether you are pre-approved and on what terms.",
        "A soft inquiry does not affect your credit score. No lender may make a hard inquiry through otdog: a response "
            + "that is not limited to soft inquiries is refused, and none of its offers is shown.",
        "otdog sends this application to " + partner + " and the lenders that work through it, and to no one else. It "
            + "holds the amount, term, car, yearly income and housing. If the partner needs your name, address, date of "
            + "birth or Social Security number, you enter them in the partner's own form; otdog never stores them.",
        "otdog does not sell your application or share it with anyone else. The partner and the lenders may use it only "
            + "to give you pre-approvals, and may not contact you about it unless you continue with one of them.",
        "A pre-approval is not a loan. To take one, you continue on that lender's own site, which makes its own checks "
            + "and asks before any hard inquiry. otdog never accepts an offer or signs a loan."));
  }

  private CaseVehicle caseVehicle(Holder holder) {
    if (holder.caseId() == null) return null;
    return jdbc.query("select vin, model_year, make, model, mileage from vehicles where case_id = ?",
        (r, n) -> new CaseVehicle(r.getString(1), (Integer) r.getObject(2, Integer.class), r.getString(3), r.getString(4),
            (Long) r.getObject(5, Long.class)), holder.caseId()).stream().findFirst().orElse(null);
  }

  /** Journey applications hold the account lock, then the journey lock, matching journey and account deletion. */
  private void lock(Holder holder) {
    if (holder.journey()) {
      accounts.lockActiveAccount(holder.accountId());
      if (jdbc.queryForList("select id from buying_journeys where id = ? for update", String.class, holder.journeyId()).isEmpty())
        throw LoanProblem.changed();
    } else {
      jdbc.queryForList("select id from analysis_cases where id = ? for update", String.class, holder.caseId());
    }
  }

  private Optional<Row> application(Holder holder) {
    return jdbc.query("select id, revision, application_json, changed_at, chosen_offer_id, chosen_at from loan_applications where %s = ?"
        .formatted(holder.journey() ? "journey_id" : "case_id"), (r, n) -> new Row(r.getString(1), r.getInt(2),
        read(r.getString(3)), instant(r, 4), r.getString(5), instant(r, 6)),
        holder.journey() ? holder.journeyId() : holder.caseId()).stream().findFirst();
  }

  // ---- Validation and conversion. Messages name the correction and never repeat the Buyer's input. ----

  static Application validate(Input input) {
    var errors = new ArrayList<LoanProblem.FieldError>();
    var given = input == null ? new Input(null, null, null, null, null, null, null) : input;
    Long amount = given.amountMinor();
    if (amount == null || amount <= 0 || amount > MAX_AMOUNT_MINOR)
      errors.add(error("amountMinor", "Enter the amount you want to borrow, up to $500,000."));
    Integer term = given.termMonths();
    if (term == null || term < 1 || term > MAX_TERM_MONTHS)
      errors.add(error("termMonths", "Enter the loan term in months, from 1 to " + MAX_TERM_MONTHS + "."));

    var vehicle = given.vehicle() == null ? new VehicleInput(null, null, null, null, null) : given.vehicle();
    String vin = null;
    if (vehicle.vin() != null && !vehicle.vin().isBlank()) {
      try { vin = Vin.validated(vehicle.vin()); }
      catch (InvalidVehicleEvidenceException | IllegalArgumentException invalid) {
        errors.add(error("vehicle.vin", "Enter the car's 17-character VIN, or leave it empty."));
      }
    }
    Integer year = vehicle.modelYear();
    int latest = Year.now(CALENDAR).getValue() + 2;
    if (year == null || year < 1981 || year > latest) errors.add(error("vehicle.modelYear", "Enter the car's model year."));
    String make = shortText(vehicle.make()), model = shortText(vehicle.model());
    if (make == null) errors.add(error("vehicle.make", "Enter the car's make, up to 64 characters."));
    if (model == null) errors.add(error("vehicle.model", "Enter the car's model, up to 64 characters."));
    Long mileage = vehicle.mileage();
    if (mileage == null || mileage < 0 || mileage > 1_000_000) errors.add(error("vehicle.mileage", "Enter the car's mileage, up to 1,000,000."));

    Long income = given.annualIncomeMinor();
    if (income == null || income < 0 || income > MAX_INCOME_MINOR)
      errors.add(error("annualIncomeMinor", "Enter your yearly income before tax, up to $1,000,000."));
    String housing = given.housing();
    if (housing == null || Arrays.stream(LoanApplication.Housing.values()).noneMatch(value -> value.name().equals(housing)))
      errors.add(error("housing", "Choose whether you own, rent or live another way."));
    Long housingCost = given.monthlyHousingCostMinor();
    if (housingCost == null || housingCost < 0 || housingCost > MAX_HOUSING_MINOR)
      errors.add(error("monthlyHousingCostMinor", "Enter what you pay for housing each month, up to $100,000; enter 0 if nothing."));
    if (!errors.isEmpty()) throw LoanProblem.invalid(errors);
    return new Application(amount, term, new VehicleInput(vin, year, make, model, mileage), income, housing, housingCost);
  }

  static LoanApplication domain(Application application) {
    var vehicle = application.vehicle();
    return new LoanApplication(application.amountMinor(), application.termMonths(),
        new LoanApplication.Vehicle(vehicle.modelYear(), vehicle.make(), vehicle.model(), vehicle.mileage()),
        application.annualIncomeMinor(), LoanApplication.Housing.valueOf(application.housing()), application.monthlyHousingCostMinor());
  }

  private static boolean validApr(BigDecimal apr) {
    return apr != null && apr.signum() >= 0 && apr.compareTo(new BigDecimal("100")) <= 0 && apr.stripTrailingZeros().scale() <= 8;
  }

  /** A stored APR without the column's trailing zeros, never in exponent form. */
  static BigDecimal plain(BigDecimal value) {
    if (value == null) return null;
    var stripped = value.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Loan record could not be written"); }
  }

  private Application read(String value) {
    try { return json.readValue(value, Application.class); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Loan record could not be read"); }
  }

  private static String keyHash(String key) {
    if (key == null || key.isBlank() || key.length() > 200) throw LoanProblem.keyRequired();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
  }

  private static LoanProblem.FieldError error(String field, String message) { return new LoanProblem.FieldError(field, message); }

  private static ResourceNotFoundException offerNotFound() { return new ResourceNotFoundException("Loan offer not found"); }

  /** A single line of Buyer text, or null when blank, too long or holding control characters. */
  private static String text(String value) { return line(value, 100); }
  private static String shortText(String value) { return line(value, 64); }
  private static String line(String value, int max) {
    if (value == null) return null;
    String stripped = value.strip();
    if (stripped.isEmpty() || stripped.length() > max || stripped.chars().anyMatch(Character::isISOControl)) return null;
    return stripped;
  }

  private static LocalDate date(String value, LocalDate earliest, LocalDate latest) {
    try {
      var parsed = LocalDate.parse(value);
      return parsed.isBefore(earliest) || parsed.isAfter(latest) ? null : parsed;
    } catch (DateTimeParseException invalid) { return null; }
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
