package com.dealanalyzer.insurance;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.insurance.quote.Coverage;
import com.dealanalyzer.insurance.quote.CoverageComparison;
import com.dealanalyzer.insurance.quote.CoverageSet;
import com.dealanalyzer.insurance.quote.InsuranceApplication;
import com.dealanalyzer.insurance.quote.InsuranceApplication.Term;
import com.dealanalyzer.insurance.quote.InsuranceApplication.Usage;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsuranceQuote;
import com.dealanalyzer.insurance.quote.InsuranceQuoteAdapter.InsurerEntry;
import com.dealanalyzer.insurance.quote.InsuranceQuoteConsent;
import com.dealanalyzer.insurance.quote.InsuranceQuoteScope;
import com.dealanalyzer.insurance.quote.InsuranceQuoteService;
import com.dealanalyzer.insurance.quote.YearlyDifference;
import com.dealanalyzer.partner.PartnerOutcome;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Buyer's insurance application and the quotes it brought back (spec 5.10, INS-01 to INS-04). A case in a
 * Buying Journey uses the journey's one application; a case without a journey holds its own, and a journey can
 * hold one without any case. The application goes only to the licensed partner behind
 * {@link InsuranceQuoteService}, and only after the Buyer agreed to the partner's consent as it is shown now.
 * Quotes are compared by {@link CoverageComparison}, which reads premium, term, coverage and insurer name only.
 * Nothing here logs, and no problem echoes what the Buyer entered.
 */
@Service
public class InsuranceShoppingService {
  /** The pilot jurisdiction's calendar, for the dates a Buyer types on a quote. */
  static final ZoneId CALENDAR = ZoneId.of("America/New_York");
  static final String SCHEMA = "insurance-application.v1";
  static final int MAX_TEXT = 100;
  static final long MAX_LIMIT_MINOR = 100_000_000L;
  static final long MAX_DEDUCTIBLE_MINOR = 1_000_000L;
  static final long MAX_PREMIUM_MINOR = 5_000_000L;
  /**
   * The consent the Buyer agrees to before anything is sent. A draft for counsel and the partner's terms (tickets
   * 123 and 127); changing the words means a new version, and a request naming an older version sends nothing.
   */
  public static final String CONSENT_VERSION = "otdog-insurance-consent.draft-1";

  public record DriverInput(Integer ageYears, String licenceState, Integer yearsLicensed, Integer atFaultAccidents,
      Integer violations) {}
  public record GaragingInput(String zip, String state) {}
  public record VehicleInput(String vin, Integer modelYear, String make, String model, String trim, String usage,
      Integer annualMileage) {}
  public record CoverageInput(String type, Long perPersonLimitMinor, Long perAccidentLimitMinor, Long deductibleMinor) {}
  /** {@code coverage} is SAME_AS_CHOSEN, DIFFERENT (with {@code lines}) or NOT_SURE. */
  public record CurrentPolicyInput(String insurer, Long premiumMinor, String term, String coverage, List<CoverageInput> lines) {}
  public record Input(Integer basedOnRevision, List<DriverInput> drivers, GaragingInput garaging, VehicleInput vehicle,
      List<CoverageInput> coverage, CurrentPolicyInput currentPolicy) {}
  public record RequestInput(String consentTextVersion, Boolean agreed) {}
  /** A quote the Buyer got from an insurer outside the partner, such as their current one (PAY-02). */
  public record OwnQuoteInput(String insurer, Long premiumMinor, String term, Boolean sameCoverage, List<CoverageInput> coverage,
      String quotedOn, String expiresOn) {}

  /** The validated application as stored; it holds rating facts only. */
  public record Application(List<DriverInput> drivers, GaragingInput garaging, VehicleInput vehicle, List<CoverageInput> coverage,
      CurrentPolicyInput currentPolicy) {}

  public record View(String holder, CaseVehicle caseVehicle, Integer revision, Instant changedAt, Application application,
      Consent consent, List<CoverageGuide> coverageGuide, Results results) {}
  public record CaseVehicle(String vin, Integer modelYear, String make, String model, String trim, Long mileage) {}
  public record Consent(String version, String partner, boolean synthetic, List<String> text) {}
  public record CoverageGuide(Coverage.Type type, String label, String explanation, boolean perPersonLimit,
      boolean perAccidentLimit, boolean deductible) {}
  /**
   * {@code sameCoverage} is ordered by yearly premium; {@code differentCoverage}, {@code expired} and
   * {@code withoutQuote} are listed by insurer name and carry no rank.
   */
  public record Results(Request request, List<QuoteRow> sameCoverage, List<QuoteRow> differentCoverage, List<QuoteRow> expired,
      List<Outcome> withoutQuote, boolean currentPremiumEntered) {}
  public record Request(String id, Instant requestedAt, Instant retrievedAt, String partner, boolean synthetic,
      String availability, String notice, boolean outdated) {}
  public record QuoteRow(String id, String insurer, String source, long premiumMinor, Term term, long yearlyPremiumMinor,
      Instant quotedAt, Instant expiresAt, String continueUrl, List<CoverageComparison.Difference> differences,
      YearlyDifference yearlyDifference) {}
  public record Outcome(String insurer, PartnerOutcome outcome) {}

  /** Who holds the application: a journey (with its account) or a case without a journey. */
  record Holder(String caseId, String journeyId, String accountId) {
    boolean journey() { return journeyId != null; }
  }
  private record Row(String id, int revision, Application application, Instant changedAt) {}
  private record QuoteRecord(String id, String source, InsurerEntry entry) {}

  private static final List<CoverageGuide> GUIDE = List.of(
      new CoverageGuide(Coverage.Type.BODILY_INJURY_LIABILITY, "Bodily injury liability",
          "Pays for injuries you cause to other people in a crash, up to a limit for each person and a limit for each accident.",
          true, true, false),
      new CoverageGuide(Coverage.Type.PROPERTY_DAMAGE_LIABILITY, "Property damage liability",
          "Pays for damage you cause to other people's cars and property, up to the limit. Florida requires at least $10,000.",
          false, true, false),
      new CoverageGuide(Coverage.Type.PERSONAL_INJURY_PROTECTION, "Personal injury protection (PIP)",
          "Pays part of your own medical bills and lost income after a crash, whoever caused it. Florida requires $10,000. "
              + "The deductible is what you pay before it pays.", true, false, true),
      new CoverageGuide(Coverage.Type.UNINSURED_MOTORIST, "Uninsured motorist",
          "Pays for your injuries when the driver who caused the crash has no insurance or too little.", true, true, false),
      new CoverageGuide(Coverage.Type.COLLISION, "Collision",
          "Pays to repair or replace your car after a crash, minus your deductible. A lender or lease usually requires it.",
          false, false, true),
      new CoverageGuide(Coverage.Type.COMPREHENSIVE, "Comprehensive",
          "Pays for theft, flood, hail, fire, hitting an animal and falling objects, minus your deductible. A lender or lease "
              + "usually requires it.", false, false, true));

  private final EphemeralBuyingCaseService cases;
  private final AccountJourneyAccess accounts;
  private final InsuranceQuoteService quotes;
  private final InsuranceQuoteAdapter adapter;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransactionTemplate reads;
  private final TransactionTemplate writes;

  InsuranceShoppingService(EphemeralBuyingCaseService cases, AccountJourneyAccess accounts, InsuranceQuoteService quotes,
      InsuranceQuoteAdapter adapter, JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions) {
    this.cases = cases; this.accounts = accounts; this.quotes = quotes; this.adapter = adapter; this.jdbc = jdbc;
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
            insert into insurance_applications(id, journey_id, account_id, case_id, application_json, schema_version, revision,
              changed_at, created_at)
            values (?, ?, ?, ?, ?, ?, 1, ?, ?)
            """, UUID.randomUUID().toString(), holder.journeyId(), holder.accountId(), holder.journey() ? null : holder.caseId(),
            stored, SCHEMA, Timestamp.from(now), Timestamp.from(now));
      } else {
        var row = current.get();
        if (row.application().equals(application)) return render(holder);
        if (input.basedOnRevision() == null || input.basedOnRevision() != row.revision()) throw InsuranceProblem.changed();
        jdbc.update("update insurance_applications set application_json = ?, revision = ?, changed_at = ? where id = ?",
            stored, row.revision() + 1, Timestamp.from(now), row.id());
      }
      return render(holder);
    });
  }

  /** Removes the application with every quote request and quote it holds. */
  View remove(Holder holder) {
    return writes.execute(status -> {
      lock(holder);
      application(holder).ifPresent(row -> {
        jdbc.update("delete from insurance_quotes where application_id = ?", row.id());
        jdbc.update("delete from insurance_quote_requests where application_id = ?", row.id());
        jdbc.update("delete from insurance_applications where id = ?", row.id());
      });
      return render(holder);
    });
  }

  /**
   * Sends the application to the licensed partner, only when the Buyer agreed to the consent version shown now
   * (INS-02). The partner is called outside any transaction; a retried request with the same key sends nothing.
   */
  View requestQuotes(Holder holder, String key, RequestInput input) {
    String keyHash = keyHash(key);
    if (input == null || !Boolean.TRUE.equals(input.agreed()) || !CONSENT_VERSION.equals(input.consentTextVersion()))
      throw InsuranceProblem.consentRequired();
    record Prepared(Row row, View settled) {}
    var prepared = reads.execute(status -> {
      var row = application(holder).orElseThrow(InsuranceProblem::noApplication);
      boolean replay = Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from insurance_quote_requests where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, row.id(), keyHash));
      return new Prepared(row, replay ? render(holder) : null);
    });
    if (prepared.settled() != null) return prepared.settled();
    var row = prepared.row();
    InsuranceApplication application;
    try { application = domain(row.application()); }
    catch (IllegalArgumentException invalid) { throw InsuranceProblem.changed(); }
    var consent = new InsuranceQuoteConsent(CONSENT_VERSION, Instant.now());
    var scope = holder.journey() ? InsuranceQuoteScope.forJourney(holder.accountId(), holder.journeyId())
        : InsuranceQuoteScope.forCase(holder.caseId());
    var result = quotes.request(scope, application, consent);
    return writes.execute(status -> {
      lock(holder);
      var current = application(holder).filter(held -> held.id().equals(row.id())).orElseThrow(InsuranceProblem::changed);
      if (Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from insurance_quote_requests where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, current.id(), keyHash))) return render(holder);
      String requestId = UUID.randomUUID().toString();
      Instant now = Instant.now();
      var provider = result.provider();
      jdbc.update("""
          insert into insurance_quote_requests(id, application_id, account_id, idempotency_key_hash, application_revision,
            consent_text_version, consent_agreed_at, provider, provider_version, licence_metadata, synthetic, availability,
            reason, retrieved_at, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """, requestId, current.id(), holder.accountId(), keyHash, row.revision(), consent.consentTextVersion(),
          Timestamp.from(consent.agreedAt()), provider.name(), provider.version(), provider.licenceMetadata(), provider.synthetic(),
          result.availability().name(), result.reason() == null ? null : result.reason().name(),
          Timestamp.from(result.retrievedAt()), Timestamp.from(now));
      for (var entry : result.insurers()) {
        var quote = entry.quote();
        jdbc.update("""
            insert into insurance_quotes(id, application_id, request_id, account_id, source, idempotency_key_hash, insurer, outcome,
              premium_minor, term, coverage_json, quoted_at, expires_at, continue_url, created_at)
            values (?, ?, ?, ?, 'PARTNER', null, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), current.id(), requestId, holder.accountId(), entry.insurer(), entry.outcome().name(),
            quote == null ? null : quote.premiumMinor(), quote == null ? null : quote.term().name(),
            quote == null ? null : write(lines(quote.coverage())), quote == null ? null : Timestamp.from(quote.quotedAt()),
            quote == null ? null : Timestamp.from(quote.expiresAt()), quote == null ? null : quote.continueUrl().toString(),
            Timestamp.from(now));
      }
      return render(holder);
    });
  }

  /** Adds a quote the Buyer got outside the partner; it is compared on the same terms (PAY-02). */
  View addOwnQuote(Holder holder, String key, OwnQuoteInput input) {
    String keyHash = keyHash(key);
    var given = input == null ? new OwnQuoteInput(null, null, null, null, null, null, null) : input;
    return writes.execute(status -> {
      lock(holder);
      var row = application(holder).orElseThrow(InsuranceProblem::noApplication);
      if (Boolean.TRUE.equals(jdbc.queryForObject(
          "select count(*) > 0 from insurance_quotes where application_id = ? and idempotency_key_hash = ?",
          Boolean.class, row.id(), keyHash))) return render(holder);
      var errors = new ArrayList<InsuranceProblem.FieldError>();
      String insurer = text(given.insurer());
      if (insurer == null) errors.add(error("insurer", "Enter the insurer's name, up to 100 characters."));
      Long premium = given.premiumMinor();
      if (premium == null || premium <= 0 || premium > MAX_PREMIUM_MINOR)
        errors.add(error("premiumMinor", "Enter the premium for the whole policy term, up to $50,000."));
      Term term = parse(Term.class, given.term());
      if (term == null) errors.add(error("term", "Choose whether the premium is for 6 months or 12 months."));
      List<CoverageInput> coverage = row.application().coverage();
      if (Boolean.FALSE.equals(given.sameCoverage())) coverage = coverage(given.coverage(), "coverage", errors);
      LocalDate today = LocalDate.now(CALENDAR);
      LocalDate quotedOn = given.quotedOn() == null || given.quotedOn().isBlank() ? today
          : date(given.quotedOn(), LocalDate.of(2000, 1, 1), today);
      if (quotedOn == null) errors.add(error("quotedOn", "Enter the date of the quote; it cannot be in the future."));
      LocalDate expiresOn = null;
      if (given.expiresOn() != null && !given.expiresOn().isBlank()) {
        LocalDate from = quotedOn == null ? today : quotedOn;
        expiresOn = date(given.expiresOn(), from, from.plusYears(2));
        if (expiresOn == null) errors.add(error("expiresOn", "Enter the date the quote ends, on or after the quote date."));
      }
      if (!errors.isEmpty()) throw InsuranceProblem.invalid(errors);
      jdbc.update("""
          insert into insurance_quotes(id, application_id, request_id, account_id, source, idempotency_key_hash, insurer, outcome,
            premium_minor, term, coverage_json, quoted_at, expires_at, continue_url, created_at)
          values (?, ?, null, ?, 'BUYER_ENTERED', ?, ?, 'OFFERED', ?, ?, ?, ?, ?, null, ?)
          """, UUID.randomUUID().toString(), row.id(), holder.accountId(), keyHash, insurer, premium, term.name(), write(coverage),
          Timestamp.from(quotedOn.atStartOfDay(CALENDAR).toInstant()),
          expiresOn == null ? null : Timestamp.from(expiresOn.plusDays(1).atStartOfDay(CALENDAR).toInstant()),
          Timestamp.from(Instant.now()));
      return render(holder);
    });
  }

  /** Removes one quote the Buyer entered; a partner's quote cannot be removed on its own. */
  View removeOwnQuote(Holder holder, String quoteId) {
    return writes.execute(status -> {
      lock(holder);
      var row = application(holder).orElseThrow(InsuranceShoppingService::quoteNotFound);
      if (jdbc.update("delete from insurance_quotes where id = ? and application_id = ? and source = 'BUYER_ENTERED'",
          quoteId, row.id()) == 0) throw quoteNotFound();
      return render(holder);
    });
  }

  private View render(Holder holder) {
    var row = application(holder);
    return new View(holder.journey() ? "JOURNEY" : "CASE", caseVehicle(holder), row.map(Row::revision).orElse(null),
        row.map(Row::changedAt).orElse(null), row.map(Row::application).orElse(null), consent(), GUIDE,
        row.map(this::results).orElse(null));
  }

  private Results results(Row row) {
    var request = jdbc.query("""
        select id, created_at, retrieved_at, provider, synthetic, availability, reason, application_revision
        from insurance_quote_requests where application_id = ? order by created_at desc, id desc
        """, (r, n) -> {
      var availability = InsuranceQuoteAdapter.Availability.valueOf(r.getString(6));
      var reason = r.getString(7) == null ? null : InsuranceQuoteAdapter.Reason.valueOf(r.getString(7));
      return new Request(r.getString(1), instant(r, 2), instant(r, 3), r.getString(4), r.getBoolean(5), availability.name(),
          notice(availability, reason, r.getBoolean(5)), r.getInt(8) != row.revision());
    }, row.id()).stream().findFirst().orElse(null);
    var records = jdbc.query("""
        select id, source, insurer, outcome, premium_minor, term, coverage_json, quoted_at, expires_at, continue_url
        from insurance_quotes where application_id = ? and (source = 'BUYER_ENTERED' or request_id = ?)
        """, (r, n) -> {
      var outcome = PartnerOutcome.valueOf(r.getString(4));
      InsuranceQuote quote = null;
      if (outcome.offered()) quote = new InsuranceQuote(r.getLong(5), "USD", Term.valueOf(r.getString(6)),
          coverageSet(read(r.getString(7), new TypeReference<List<CoverageInput>>() {})), instant(r, 8), instant(r, 9),
          r.getString(10) == null ? null : URI.create(r.getString(10)));
      return new QuoteRecord(r.getString(1), r.getString(2), new InsurerEntry(r.getString(3), outcome, quote));
    }, row.id(), request == null ? "" : request.id());
    if (request == null && records.isEmpty()) return null;

    var application = row.application();
    var chosen = coverageSet(application.coverage());
    var current = currentPolicy(application.currentPolicy(), application.coverage());
    Instant now = Instant.now();
    var byEntry = new IdentityHashMap<InsurerEntry, QuoteRecord>();
    var live = new ArrayList<InsurerEntry>();
    var expired = new ArrayList<QuoteRow>();
    for (var record : records) {
      byEntry.put(record.entry(), record);
      var quote = record.entry().quote();
      if (quote != null && quote.expiresAt() != null && !quote.expiresAt().isAfter(now))
        expired.add(quoteRow(record, CoverageComparison.differences(chosen, quote.coverage()), null));
      else live.add(record.entry());
    }
    var comparison = CoverageComparison.compare(chosen, current, live);
    expired.sort(Comparator.comparing((QuoteRow quote) -> quote.insurer().toLowerCase(Locale.ROOT)).thenComparing(QuoteRow::insurer));
    return new Results(request,
        comparison.sameCoverage().stream().map(c -> quoteRow(byEntry.get(c.entry()), c.differences(), c.yearlyDifference())).toList(),
        comparison.differentCoverage().stream().map(c -> quoteRow(byEntry.get(c.entry()), c.differences(), null)).toList(),
        expired, comparison.withoutQuote().stream().map(entry -> new Outcome(entry.insurer(), entry.outcome())).toList(),
        current != null);
  }

  private static QuoteRow quoteRow(QuoteRecord record, List<CoverageComparison.Difference> differences, YearlyDifference yearly) {
    var quote = record.entry().quote();
    return new QuoteRow(record.id(), record.entry().insurer(), record.source(), quote.premiumMinor(), quote.term(),
        quote.yearlyPremiumMinor(), quote.quotedAt(), quote.expiresAt(),
        quote.continueUrl() == null ? null : quote.continueUrl().toString(), differences, yearly);
  }

  private static String notice(InsuranceQuoteAdapter.Availability availability, InsuranceQuoteAdapter.Reason reason, boolean synthetic) {
    if (availability == InsuranceQuoteAdapter.Availability.AVAILABLE)
      return synthetic ? "Synthetic insurance quotes for development only." : null;
    return switch (reason) {
      case TIMED_OUT -> "The insurance partner timed out. No quote was substituted.";
      case RATE_LIMITED -> "The insurance partner's request limit was reached. No quote was substituted.";
      case STALE_RESPONSE -> "The insurance partner's response was too old to show. No quote was substituted.";
      case INVALID_RESPONSE -> "The insurance partner's response was invalid. No quote was substituted.";
      case PROVIDER_UNAVAILABLE -> "The insurance partner is unavailable. No quote was substituted.";
    };
  }

  private Consent consent() {
    var provider = adapter.provider();
    String partner = provider.synthetic() ? "the synthetic test partner (development only)" : provider.name();
    return new Consent(CONSENT_VERSION, partner, provider.synthetic(), List.of(
        "When you choose Get quotes, otdog sends this application to " + partner + ", a licensed insurance comparison "
            + "partner, and to the insurers that quote through it. It goes to no one else.",
        "The application holds your drivers' ages, licence states, years licensed, accidents and tickets, the ZIP code "
            + "where the car is kept, the car, how it is used, the coverage you chose and, if you entered them, your current "
            + "insurer, coverage and premium. It holds no names, dates of birth, licence numbers or Social Security numbers.",
        "otdog does not sell your application or share it with anyone else. The partner and the insurers may use it only "
            + "to quote you, and may not contact you about it unless you continue with one of them.",
        "Getting quotes does not buy a policy. To take one, you continue on that insurer's own site, which may ask for "
            + "more details, such as names and dates of birth, before giving a final price. otdog never buys, accepts or "
            + "binds a policy."));
  }

  private CaseVehicle caseVehicle(Holder holder) {
    if (holder.caseId() == null) return null;
    return jdbc.query("select vin, model_year, make, model, trim_name, mileage from vehicles where case_id = ?",
        (r, n) -> new CaseVehicle(r.getString(1), (Integer) r.getObject(2, Integer.class), r.getString(3), r.getString(4),
            r.getString(5), (Long) r.getObject(6, Long.class)), holder.caseId()).stream().findFirst().orElse(null);
  }

  /** Journey applications hold the account lock, then the journey lock, matching journey and account deletion. */
  private void lock(Holder holder) {
    if (holder.journey()) {
      accounts.lockActiveAccount(holder.accountId());
      if (jdbc.queryForList("select id from buying_journeys where id = ? for update", String.class, holder.journeyId()).isEmpty())
        throw InsuranceProblem.changed();
    } else {
      jdbc.queryForList("select id from analysis_cases where id = ? for update", String.class, holder.caseId());
    }
  }

  private Optional<Row> application(Holder holder) {
    return jdbc.query("select id, revision, application_json, changed_at from insurance_applications where %s = ?"
        .formatted(holder.journey() ? "journey_id" : "case_id"), (r, n) -> new Row(r.getString(1), r.getInt(2),
        read(r.getString(3), new TypeReference<Application>() {}), instant(r, 4)),
        holder.journey() ? holder.journeyId() : holder.caseId()).stream().findFirst();
  }

  // ---- Validation and conversion. Messages name the correction and never repeat the Buyer's input. ----

  static Application validate(Input input) {
    var errors = new ArrayList<InsuranceProblem.FieldError>();
    var given = input == null ? new Input(null, null, null, null, null, null) : input;
    var drivers = new ArrayList<DriverInput>();
    if (given.drivers() == null || given.drivers().isEmpty() || given.drivers().size() > InsuranceApplication.MAX_DRIVERS)
      errors.add(error("drivers", "Add each driver of the car, up to " + InsuranceApplication.MAX_DRIVERS + " drivers."));
    else for (int i = 0; i < given.drivers().size(); i++) {
      var driver = given.drivers().get(i);
      String field = "drivers[" + i + "].";
      if (driver == null) { errors.add(error(field + "ageYears", "Enter the driver's age.")); continue; }
      Integer age = driver.ageYears(), licensed = driver.yearsLicensed();
      if (age == null || age < 15 || age > 120) errors.add(error(field + "ageYears", "Enter the driver's age, from 15 to 120."));
      String state = state(driver.licenceState());
      if (state == null) errors.add(error(field + "licenceState", "Choose the state that issued the driver's licence."));
      if (licensed == null || licensed < 0 || (age != null && licensed > age))
        errors.add(error(field + "yearsLicensed", "Enter how many years the driver has been licensed, no more than their age."));
      Integer accidents = count(driver.atFaultAccidents()), violations = count(driver.violations());
      if (accidents == null) errors.add(error(field + "atFaultAccidents", "Enter the at-fault accidents in the last 5 years, from 0 to 20."));
      if (violations == null) errors.add(error(field + "violations", "Enter the tickets in the last 3 years, from 0 to 20."));
      drivers.add(new DriverInput(age, state, licensed, accidents, violations));
    }

    var garaging = given.garaging() == null ? new GaragingInput(null, null) : given.garaging();
    String zip = garaging.zip() == null ? null : garaging.zip().strip();
    if (zip == null || !zip.matches("[0-9]{5}")) errors.add(error("garaging.zip", "Enter the five-digit ZIP code where the car is kept overnight."));
    String garagingState = state(garaging.state());
    if (garagingState == null) errors.add(error("garaging.state", "Choose the state where the car is kept overnight."));

    var vehicle = given.vehicle() == null ? new VehicleInput(null, null, null, null, null, null, null) : given.vehicle();
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
    String trim = null;
    if (vehicle.trim() != null && !vehicle.trim().isBlank()) {
      trim = shortText(vehicle.trim());
      if (trim == null) errors.add(error("vehicle.trim", "Enter the trim in up to 64 characters, or leave it empty."));
    }
    Usage usage = parse(Usage.class, vehicle.usage());
    if (usage == null) errors.add(error("vehicle.usage", "Choose how the car is mostly driven."));
    Integer mileage = vehicle.annualMileage();
    if (mileage != null && (mileage < 0 || mileage > 200_000))
      errors.add(error("vehicle.annualMileage", "Enter the miles driven a year, up to 200,000, or leave it empty."));

    var coverage = coverage(given.coverage(), "coverage", errors);

    CurrentPolicyInput current = null;
    if (given.currentPolicy() != null) {
      var policy = given.currentPolicy();
      String insurer = null;
      if (policy.insurer() != null && !policy.insurer().isBlank()) {
        insurer = text(policy.insurer());
        if (insurer == null) errors.add(error("currentPolicy.insurer", "Enter your current insurer in up to 100 characters, or leave it empty."));
      }
      Long premium = policy.premiumMinor();
      if (premium == null || premium <= 0 || premium > MAX_PREMIUM_MINOR)
        errors.add(error("currentPolicy.premiumMinor", "Enter what you pay now for the whole policy term, up to $50,000."));
      Term term = parse(Term.class, policy.term());
      if (term == null) errors.add(error("currentPolicy.term", "Choose whether your current premium is for 6 months or 12 months."));
      String mode = policy.coverage() == null ? "NOT_SURE" : policy.coverage();
      List<CoverageInput> lines = null;
      switch (mode) {
        case "SAME_AS_CHOSEN", "NOT_SURE" -> { }
        case "DIFFERENT" -> lines = coverage(policy.lines(), "currentPolicy.lines", errors);
        default -> errors.add(error("currentPolicy.coverage", "Say whether your current coverage matches the coverage you chose."));
      }
      current = new CurrentPolicyInput(insurer, premium, term == null ? null : term.name(), mode, lines);
    }
    if (!errors.isEmpty()) throw InsuranceProblem.invalid(errors);
    return new Application(List.copyOf(drivers), new GaragingInput(zip, garagingState),
        new VehicleInput(vin, year, make, model, trim, usage.name(), mileage), coverage, current);
  }

  /** Each line carries exactly the amounts its kind of coverage has, so two quotes compare line by line. */
  static List<CoverageInput> coverage(List<CoverageInput> lines, String field, List<InsuranceProblem.FieldError> errors) {
    if (lines == null || lines.isEmpty() || lines.stream().anyMatch(Objects::isNull)) {
      errors.add(error(field, "Choose at least one kind of coverage."));
      return List.of();
    }
    var result = new ArrayList<CoverageInput>();
    var seen = new java.util.HashSet<Coverage.Type>();
    for (var line : lines) {
      var type = parse(Coverage.Type.class, line.type());
      if (type == null || !seen.add(type)) { errors.add(error(field, "Choose each kind of coverage once.")); continue; }
      var guide = GUIDE.stream().filter(g -> g.type() == type).findFirst().orElseThrow();
      String at = field + "." + type.name();
      Long person = line.perPersonLimitMinor(), accident = line.perAccidentLimitMinor(), deductible = line.deductibleMinor();
      boolean valid = present(guide.perPersonLimit(), person, MAX_LIMIT_MINOR) && present(guide.perAccidentLimit(), accident, MAX_LIMIT_MINOR)
          && present(guide.deductible(), deductible, MAX_DEDUCTIBLE_MINOR)
          && (person == null || accident == null || person <= accident);
      if (!valid) { errors.add(error(at, "Choose the limits and deductible for " + guide.label().toLowerCase(Locale.ROOT) + ".")); continue; }
      result.add(new CoverageInput(type.name(), person, accident, deductible));
    }
    result.sort(Comparator.comparing(CoverageInput::type, Comparator.comparing(name -> Coverage.Type.valueOf(name))));
    return List.copyOf(result);
  }

  private static boolean present(boolean wanted, Long amount, long max) {
    return wanted ? amount != null && amount >= 0 && amount <= max : amount == null;
  }

  static InsuranceApplication domain(Application application) {
    var vehicle = application.vehicle();
    return new InsuranceApplication(application.drivers().stream().map(driver -> new InsuranceApplication.Driver(driver.ageYears(),
        driver.yearsLicensed(), driver.atFaultAccidents(), driver.violations(), driver.licenceState())).toList(),
        new InsuranceApplication.Garaging(application.garaging().zip(), application.garaging().state()),
        new InsuranceApplication.InsuredVehicle(vehicle.vin(), vehicle.modelYear(), vehicle.make(), vehicle.model(), vehicle.trim(),
            vehicle.annualMileage(), Usage.valueOf(vehicle.usage())),
        coverageSet(application.coverage()), currentPolicy(application.currentPolicy(), application.coverage()));
  }

  private static InsuranceApplication.CurrentPolicy currentPolicy(CurrentPolicyInput policy, List<CoverageInput> chosen) {
    if (policy == null) return null;
    CoverageSet coverage = switch (policy.coverage()) {
      case "SAME_AS_CHOSEN" -> coverageSet(chosen);
      case "DIFFERENT" -> coverageSet(policy.lines());
      default -> null;
    };
    return new InsuranceApplication.CurrentPolicy(policy.insurer(), coverage, policy.premiumMinor(), Term.valueOf(policy.term()));
  }

  static CoverageSet coverageSet(List<CoverageInput> lines) {
    return new CoverageSet(lines.stream().map(line -> new Coverage(Coverage.Type.valueOf(line.type()), line.perPersonLimitMinor(),
        line.perAccidentLimitMinor(), line.deductibleMinor())).toList());
  }

  private static List<CoverageInput> lines(CoverageSet coverage) {
    return coverage.coverages().stream().map(line -> new CoverageInput(line.type().name(), line.perPersonLimitMinor(),
        line.perAccidentLimitMinor(), line.deductibleMinor())).toList();
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Insurance record could not be written"); }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try { return json.readValue(value, type); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Insurance record could not be read"); }
  }

  private static String keyHash(String key) {
    if (key == null || key.isBlank() || key.length() > 200) throw InsuranceProblem.keyRequired();
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
  }

  private static InsuranceProblem.FieldError error(String field, String message) {
    return new InsuranceProblem.FieldError(field, message);
  }

  private static ResourceNotFoundException quoteNotFound() { return new ResourceNotFoundException("Insurance quote not found"); }

  private static Integer count(Integer value) { return value == null || value < 0 || value > 20 ? null : value; }

  private static String state(String value) {
    if (value == null) return null;
    String stripped = value.strip().toUpperCase(Locale.ROOT);
    return stripped.matches("[A-Z]{2}") ? stripped : null;
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null) return null;
    return Arrays.stream(type.getEnumConstants()).filter(constant -> constant.name().equals(value)).findFirst().orElse(null);
  }

  /** A single line of Buyer text, or null when blank, too long or holding control characters. */
  private static String text(String value) { return line(value, MAX_TEXT); }
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
