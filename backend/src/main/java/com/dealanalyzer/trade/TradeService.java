package com.dealanalyzer.trade;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.document.DocumentIntakeService;
import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.journey.vin.VinChecksum;
import com.dealanalyzer.journey.vin.VinDecoder;
import com.dealanalyzer.journey.vin.VinDecoderUnavailableException;
import com.dealanalyzer.journey.vin.VinDecoding;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Buyer's Trade Vehicle (TRADE-01) and the written trade offers they obtained themselves
 * (TRADE-02). A case in a Buying Journey uses the journey's one Trade Vehicle; a case without a
 * journey holds its own. Every operation is authorized by the case credential, and the trade is
 * used only to check the Buyer's own deal: nothing here requests, arranges, sells or refers a
 * trade offer, and no trade data is sent to a dealer or to anyone who buys cars.
 */
@Service
public class TradeService {
  /** The pilot jurisdiction's calendar, for good-through and expiry dates. */
  static final ZoneId CALENDAR = ZoneId.of("America/New_York");
  static final long MAX_AMOUNT_MINOR = 50_000_000L;
  static final int MAX_MILEAGE = 999_999;
  static final int MAX_TEXT = 120;

  /** Condition levels, each with the plain description the Buyer chooses by. */
  public enum Condition {
    EXCELLENT("Excellent", "Looks and drives like new. No dents, warning lights or repairs needed, and a clean title."),
    GOOD("Good", "Light wear such as small scratches or chips. Drives well and needs no repairs right now."),
    FAIR("Fair", "Clear wear or cosmetic damage, or it needs a repair soon, such as tires or brakes. It still drives."),
    POOR("Poor", "Major damage, mechanical problems, a warning light that stays on, or a branded title such as salvage or flood.");

    private final String label;
    private final String description;

    Condition(String label, String description) { this.label = label; this.description = description; }

    public String label() { return label; }
    public String description() { return description; }
  }

  /** The answer to "Do you still owe money on it?". */
  public enum MoneyOwed { YES, NO, NOT_SURE }

  public record Input(Integer basedOnRevision, String vin, Integer mileage, String condition, String moneyOwed,
      String lender, Long payoffMinor, String payoffGoodThrough, Boolean payoffUnknown) {}
  public record OfferInput(String amountMinor, String source, String offerDate, String expiresOn) {}

  public record View(String holder, Trade trade, Analysis analysis, List<ConditionOption> conditions) {}
  public record ConditionOption(Condition value, String label, String description) {}
  /** When this case was last analyzed and whether the trade changed after it. */
  public record Analysis(Instant analyzedAt, boolean outdated) {}
  public record Trade(String id, int revision, Vehicle vehicle, int mileage, Condition condition, MoneyOwed moneyOwed,
      String lender, Long payoffMinor, String payoffGoodThrough, boolean payoffUnknown, boolean payoffStale,
      boolean complete, Instant changedAt, List<Offer> offers, String bestOfferId) {}
  public record Vehicle(String vin, VinDecoding.Outcome decodeOutcome, String source, String decoderVersion,
      Instant decodedAt, String year, String make, String model, String trim) {}
  public record Offer(String id, long amountMinor, String source, String offerDate, String expiresOn, boolean expired,
      boolean usedForComparison, Evidence evidence, Instant createdAt) {}
  /** The offer's photo or PDF: PROCESSING, READY, FAILED, or REMOVED once retention erased it. */
  public record Evidence(String documentId, String state, Integer pageCount) {}

  private record Holder(String caseId, String journeyId, String accountId) {
    boolean journey() { return journeyId != null; }
  }
  private record Decoded(VinDecoding.Outcome outcome, String source, String version, Instant decodedAt, String year,
      String make, String model, String trim) {}
  private record Content(String vin, int mileage, Condition condition, MoneyOwed moneyOwed, String lender,
      Long payoffMinor, LocalDate payoffGoodThrough) {}
  private record Row(String id, int revision, Content content, Decoded decoded, Instant changedAt) {}
  private record ValidOffer(long amountMinor, String source, LocalDate offerDate, LocalDate expiresOn) {}

  private static final List<ConditionOption> CONDITIONS = java.util.Arrays.stream(Condition.values())
      .map(value -> new ConditionOption(value, value.label(), value.description())).toList();

  private final EphemeralBuyingCaseService cases;
  private final AccountJourneyAccess accounts;
  private final DocumentIntakeService documents;
  private final VinDecoder decoder;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate reads;
  private final TransactionTemplate writes;

  TradeService(EphemeralBuyingCaseService cases, AccountJourneyAccess accounts, DocumentIntakeService documents,
      VinDecoder decoder, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
    this.cases = cases; this.accounts = accounts; this.documents = documents; this.decoder = decoder; this.jdbc = jdbc;
    this.reads = new TransactionTemplate(transactions); this.reads.setReadOnly(true);
    this.writes = new TransactionTemplate(transactions);
  }

  public View view(String caseId, String token) {
    cases.reopen(caseId, token);
    return reads.execute(status -> view(holder(caseId)));
  }

  /**
   * Records or edits the Trade Vehicle. The VIN is checksum-validated before any decoder call, and
   * a new VIN is decoded outside any transaction. {@code basedOnRevision} is the revision the Buyer
   * edited: a stale edit is refused, and saving the same details again changes nothing.
   */
  public View save(String caseId, String token, Input input) {
    cases.reopen(caseId, token);
    if (input == null) throw invalid(List.of(new TradeProblem.FieldError("vin", "Enter the VIN of the car you are trading in.")));
    String vin = validVin(input.vin());
    Content content = validate(input, vin);
    Optional<Decoded> known = reads.execute(status -> trade(holder(caseId))
        .filter(row -> row.content().vin().equals(vin)).map(Row::decoded));
    Decoded decoded = known.orElseGet(() -> decode(vin));
    return writes.execute(status -> {
      var holder = holder(caseId);
      lock(holder);
      var current = trade(holder);
      Instant now = Instant.now();
      if (current.isEmpty()) {
        insert(holder, content, decoded, now);
      } else {
        var row = current.get();
        if (row.content().equals(content)) return view(holder);
        if (input.basedOnRevision() == null || input.basedOnRevision() != row.revision()) throw TradeProblem.changed();
        Decoded kept = row.content().vin().equals(vin) ? row.decoded() : decoded;
        jdbc.update("""
            update trade_vehicles set vin = ?, decode_outcome = ?, decoder_source = ?, decoder_version = ?, decoded_at = ?,
              model_year = ?, make = ?, model = ?, trim_name = ?, mileage = ?, condition = ?, money_owed = ?, lender = ?,
              payoff_minor = ?, payoff_good_through = ?, revision = ?, changed_at = ?
            where id = ?
            """, content.vin(), kept.outcome().name(), kept.source(), kept.version(), Timestamp.from(kept.decodedAt()),
            kept.year(), kept.make(), kept.model(), kept.trim(), content.mileage(), content.condition().name(),
            name(content.moneyOwed()), content.lender(), content.payoffMinor(), date(content.payoffGoodThrough()),
            row.revision() + 1, Timestamp.from(now), row.id());
      }
      return view(holder);
    });
  }

  /** Removes the Trade Vehicle and its written offers; their evidence is erased under document retention. */
  public View remove(String caseId, String token) {
    cases.reopen(caseId, token);
    return writes.execute(status -> {
      var holder = holder(caseId);
      lock(holder);
      trade(holder).ifPresent(row -> {
        Instant now = Instant.now();
        jdbc.update("""
            update source_documents set retention_deadline = ?, updated_at = ?
            where id in (select evidence_document_id from trade_offers where trade_id = ?)
              and purpose = 'TRADE_OFFER_EVIDENCE' and retention_deadline > ?
            """, Timestamp.from(now), Timestamp.from(now), row.id(), Timestamp.from(now));
        jdbc.update("delete from trade_offers where trade_id = ?", row.id());
        jdbc.update("delete from trade_vehicles where id = ?", row.id());
      });
      return view(holder);
    });
  }

  /**
   * Adds a written trade offer the Buyer obtained. Its photo or PDF goes through ordinary document
   * intake in this case, as trade offer evidence that is never read as dealer paperwork. A retried
   * request with the same key returns the offer it already added.
   */
  public View addOffer(String caseId, String token, String key, OfferInput input, MultipartFile file) {
    cases.reopen(caseId, token);
    if (key == null || key.isBlank() || key.length() > 200) throw new TradeProblem(HttpStatus.BAD_REQUEST,
        "IDEMPOTENCY_KEY_REQUIRED", "Retry the request; it was missing its request key.", List.of());
    var offer = validateOffer(input, file);
    String keyHash = sha256(key);
    View settled = reads.execute(status -> {
      var holder = holder(caseId);
      var trade = trade(holder).orElseThrow(TradeProblem::notRecorded);
      return offerByKey(trade.id(), keyHash) ? view(holder) : null;
    });
    if (settled != null) return settled;
    String documentId = documents.intakeTradeOfferEvidence(caseId, token, "trade-offer:" + key, file).document().id();
    try {
      return writes.execute(status -> {
        var holder = holder(caseId);
        lock(holder);
        var trade = trade(holder).orElseThrow(TradeProblem::notRecorded);
        boolean held = offerByKey(trade.id(), keyHash) || Boolean.TRUE.equals(jdbc.queryForObject(
            "select count(*) > 0 from trade_offers where evidence_document_id = ?", Boolean.class, documentId));
        if (held) return view(holder);
        Instant now = Instant.now();
        jdbc.update("""
            insert into trade_offers(id, trade_id, account_id, evidence_case_id, evidence_document_id, idempotency_key_hash,
              amount_minor, source, offer_date, expires_on, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID().toString(), trade.id(), holder.accountId(), caseId, documentId, keyHash, offer.amountMinor(),
            offer.source(), date(offer.offerDate()), date(offer.expiresOn()), Timestamp.from(now));
        touch(trade.id(), now);
        return view(holder);
      });
    } catch (TradeProblem refused) {
      expireEvidence(List.of(documentId));
      throw refused;
    }
  }

  /** Removes one written offer; its evidence is erased under document retention. */
  public View removeOffer(String caseId, String token, String offerId) {
    cases.reopen(caseId, token);
    return writes.execute(status -> {
      var holder = holder(caseId);
      lock(holder);
      var trade = trade(holder).orElseThrow(TradeService::offerNotFound);
      var evidence = jdbc.queryForList("select evidence_document_id from trade_offers where id = ? and trade_id = ?",
          String.class, offerId, trade.id());
      if (evidence.isEmpty()) throw offerNotFound();
      jdbc.update("delete from trade_offers where id = ?", offerId);
      expireEvidence(evidence);
      touch(trade.id(), Instant.now());
      return view(holder);
    });
  }

  /** One normalized page of an offer's evidence, wherever in the journey it was uploaded. */
  public byte[] evidencePage(String caseId, String token, String offerId, int pageNumber) {
    cases.reopen(caseId, token);
    String[] evidence = reads.execute(status -> {
      var trade = trade(holder(caseId)).orElseThrow(TradeService::offerNotFound);
      return jdbc.query("select evidence_case_id, evidence_document_id from trade_offers where id = ? and trade_id = ?",
          (r, n) -> new String[] {r.getString(1), r.getString(2)}, offerId, trade.id()).stream().findFirst()
          .orElseThrow(TradeService::offerNotFound);
    });
    return documents.tradeOfferEvidencePage(evidence[0], evidence[1], pageNumber);
  }

  /**
   * Carries the trade into a case started at the Desk Check for another car. When the earlier case
   * is in a journey, the new case joins that journey's Trade Vehicle; otherwise it gets its own copy
   * of the earlier case's vehicle and payoff. Both case credentials are required, and repeating it
   * changes nothing.
   */
  public View carryOver(String caseId, String token, String sourceCaseId, String sourceToken) {
    cases.reopen(caseId, token);
    if (sourceCaseId == null || sourceCaseId.length() > 36 || sourceToken == null || sourceToken.isBlank())
      throw new ResourceNotFoundException("Buying Case not found");
    cases.reopen(sourceCaseId, sourceToken);
    return writes.execute(status -> {
      var target = holder(caseId);
      if (sourceCaseId.equals(caseId) || target.journey()) return view(target);
      var source = holder(sourceCaseId);
      if (source.journey()) {
        if (trade(target).isPresent()) throw new TradeProblem(HttpStatus.CONFLICT, "TRADE_ALREADY_RECORDED",
            "This case already holds its own trade-in, so the journey's trade-in was not added to it.", List.of());
        lock(source);
        jdbc.update("insert into trade_case_journeys(case_id, journey_id, account_id, created_at) values (?, ?, ?, ?)",
            caseId, source.journeyId(), source.accountId(), Timestamp.from(Instant.now()));
        return view(holder(caseId));
      }
      var held = trade(source);
      if (held.isEmpty() || trade(target).isPresent()) return view(target);
      lock(target);
      insert(target, held.get().content(), held.get().decoded(), Instant.now());
      return view(target);
    });
  }

  private View view(Holder holder) {
    var trade = trade(holder);
    var analyzed = jdbc.query("select max(created_at) from analysis_reports where case_id = ?",
        (r, n) -> instant(r, 1), holder.caseId()).stream().filter(Objects::nonNull).findFirst().orElse(null);
    boolean outdated = analyzed != null && trade.map(row -> row.changedAt().isAfter(analyzed)).orElse(false);
    return new View(holder.journey() ? "JOURNEY" : "CASE", trade.map(this::trade).orElse(null),
        new Analysis(analyzed, outdated), CONDITIONS);
  }

  private Trade trade(Row row) {
    LocalDate today = LocalDate.now(CALENDAR);
    var content = row.content();
    var offers = jdbc.query("""
        select o.id, o.amount_minor, o.source, o.offer_date, o.expires_on, o.created_at, o.evidence_document_id,
          d.lifecycle, d.page_count, op.stage
        from trade_offers o join source_documents d on d.id = o.evidence_document_id
        left join document_processing_operations op on op.document_id = d.id
        where o.trade_id = ? order by o.created_at, o.id
        """, (r, n) -> {
      LocalDate expires = r.getDate(5).toLocalDate();
      boolean expired = expires.isBefore(today);
      String state = !"ACTIVE".equals(r.getString(8)) ? "REMOVED" : "EVIDENCE_STORED".equals(r.getString(10)) ? "READY"
          : "FAILED".equals(r.getString(10)) ? "FAILED" : "PROCESSING";
      return new Offer(r.getString(1), r.getLong(2), r.getString(3), r.getDate(4).toLocalDate().toString(), expires.toString(),
          expired, !expired, new Evidence(r.getString(7), state, (Integer) r.getObject(9)), instant(r, 6));
    }, row.id());
    // Only an unexpired written offer is used to compare a dealer's trade allowance.
    String best = offers.stream().filter(Offer::usedForComparison).max(Comparator.comparingLong(Offer::amountMinor))
        .map(Offer::id).orElse(null);
    var decoded = row.decoded();
    boolean owed = content.moneyOwed() == MoneyOwed.YES;
    return new Trade(row.id(), row.revision(), new Vehicle(content.vin(), decoded.outcome(), decoded.source(), decoded.version(),
        decoded.decodedAt(), decoded.year(), decoded.make(), decoded.model(), decoded.trim()), content.mileage(),
        content.condition(), content.moneyOwed(), content.lender(), content.payoffMinor(),
        content.payoffGoodThrough() == null ? null : content.payoffGoodThrough().toString(),
        content.moneyOwed() == MoneyOwed.NOT_SURE || owed && content.payoffMinor() == null,
        owed && content.payoffGoodThrough() != null && content.payoffGoodThrough().isBefore(today),
        content.moneyOwed() != null, row.changedAt(), offers, best);
  }

  private Holder holder(String caseId) {
    return jdbc.query("""
        select journey_id, account_id from journey_candidate_cases where case_id = ?
        union all select journey_id, account_id from trade_case_journeys where case_id = ?
        """, (r, n) -> new Holder(caseId, r.getString(1), r.getString(2)), caseId, caseId)
        .stream().findFirst().orElse(new Holder(caseId, null, null));
  }

  /** Journey trades hold the account lock, then the journey lock, matching journey and account deletion. */
  private void lock(Holder holder) {
    if (holder.journey()) {
      accounts.lockActiveAccount(holder.accountId());
      if (jdbc.queryForList("select id from buying_journeys where id = ? for update", String.class, holder.journeyId()).isEmpty())
        throw TradeProblem.changed();
    } else {
      jdbc.queryForList("select id from analysis_cases where id = ? for update", String.class, holder.caseId());
    }
  }

  private Optional<Row> trade(Holder holder) {
    return jdbc.query("""
        select id, revision, vin, mileage, condition, money_owed, lender, payoff_minor, payoff_good_through, decode_outcome,
          decoder_source, decoder_version, decoded_at, model_year, make, model, trim_name, changed_at
        from trade_vehicles where %s = ?
        """.formatted(holder.journey() ? "journey_id" : "case_id"), (r, n) -> new Row(r.getString(1), r.getInt(2),
        new Content(r.getString(3), r.getInt(4), Condition.valueOf(r.getString(5)),
            r.getString(6) == null ? null : MoneyOwed.valueOf(r.getString(6)), r.getString(7),
            (Long) r.getObject(8, Long.class), r.getDate(9) == null ? null : r.getDate(9).toLocalDate()),
        new Decoded(VinDecoding.Outcome.valueOf(r.getString(10)), r.getString(11), r.getString(12), instant(r, 13),
            r.getString(14), r.getString(15), r.getString(16), r.getString(17)), instant(r, 18)),
        holder.journey() ? holder.journeyId() : holder.caseId()).stream().findFirst();
  }

  private void insert(Holder holder, Content content, Decoded decoded, Instant now) {
    jdbc.update("""
        insert into trade_vehicles(id, journey_id, account_id, case_id, vin, decode_outcome, decoder_source, decoder_version,
          decoded_at, model_year, make, model, trim_name, mileage, condition, money_owed, lender, payoff_minor,
          payoff_good_through, revision, changed_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
        """, UUID.randomUUID().toString(), holder.journeyId(), holder.accountId(), holder.journey() ? null : holder.caseId(),
        content.vin(), decoded.outcome().name(), decoded.source(), decoded.version(), Timestamp.from(decoded.decodedAt()),
        decoded.year(), decoded.make(), decoded.model(), decoded.trim(), content.mileage(), content.condition().name(),
        name(content.moneyOwed()), content.lender(), content.payoffMinor(), date(content.payoffGoodThrough()),
        Timestamp.from(now), Timestamp.from(now));
  }

  /** An offer added or removed changes what the trade is compared with, so it also dates the trade. */
  private void touch(String tradeId, Instant now) {
    jdbc.update("update trade_vehicles set changed_at = ? where id = ?", Timestamp.from(now), tradeId);
  }

  private boolean offerByKey(String tradeId, String keyHash) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "select count(*) > 0 from trade_offers where trade_id = ? and idempotency_key_hash = ?", Boolean.class, tradeId, keyHash));
  }

  /** Brings the evidence's retention deadline forward, so the ordinary retention sweep erases it. */
  private void expireEvidence(List<String> documentIds) {
    Instant now = Instant.now();
    for (String id : documentIds) jdbc.update("""
        update source_documents set retention_deadline = ?, updated_at = ?
        where id = ? and purpose = 'TRADE_OFFER_EVIDENCE' and retention_deadline > ?
        """, Timestamp.from(now), Timestamp.from(now), id, Timestamp.from(now));
  }

  private Decoded decode(String vin) {
    VinDecoding decoding;
    try { decoding = decoder.decode(vin); }
    catch (VinDecoderUnavailableException unavailable) {
      throw new TradeProblem(HttpStatus.SERVICE_UNAVAILABLE, "VIN_DECODER_UNAVAILABLE", "The VIN decoder is not answering "
          + "right now. Nothing was saved and nothing was guessed. Try saving your trade-in again in a few minutes.", List.of());
    }
    var attributes = decoding.attributes();
    return new Decoded(decoding.outcome(), decoding.source(), decoding.decoderVersion(), Instant.now(),
        attributes.get(VehicleAttribute.MODEL_YEAR), attributes.get(VehicleAttribute.MAKE),
        attributes.get(VehicleAttribute.MODEL), attributes.get(VehicleAttribute.TRIM));
  }

  private static String validVin(String input) {
    try { return Vin.validated(input); }
    catch (InvalidVehicleEvidenceException invalid) {
      throw new TradeProblem(HttpStatus.BAD_REQUEST, "VIN_INVALID", "That VIN was not decoded because it is not a valid VIN.",
          List.of(new TradeProblem.FieldError("vin", VinChecksum.MESSAGE)));
    }
  }

  private static Content validate(Input input, String vin) {
    var errors = new ArrayList<TradeProblem.FieldError>();
    Integer mileage = input.mileage();
    if (mileage == null || mileage < 0 || mileage > MAX_MILEAGE)
      errors.add(new TradeProblem.FieldError("mileage", "Enter the mileage on the odometer, from 0 to 999,999 miles."));
    Condition condition = parse(Condition.class, input.condition());
    if (condition == null) errors.add(new TradeProblem.FieldError("condition", "Choose the description closest to the car's condition."));
    MoneyOwed owed = null;
    if (input.moneyOwed() != null) {
      owed = parse(MoneyOwed.class, input.moneyOwed());
      if (owed == null) errors.add(new TradeProblem.FieldError("moneyOwed", "Answer yes, no or not sure."));
    }
    String lender = null;
    Long payoff = null;
    LocalDate goodThrough = null;
    if (owed == MoneyOwed.YES) {
      lender = text(input.lender());
      if (lender == null) errors.add(new TradeProblem.FieldError("lender",
          "Enter the lender you make payments to, up to 120 characters."));
      if (!Boolean.TRUE.equals(input.payoffUnknown())) {
        payoff = input.payoffMinor();
        if (payoff == null || payoff <= 0 || payoff > MAX_AMOUNT_MINOR) {
          payoff = null;
          errors.add(new TradeProblem.FieldError("payoffMinor",
              "Enter the payoff amount from your lender, or choose \"I don't know the payoff yet\"."));
        }
        goodThrough = date(input.payoffGoodThrough(), LocalDate.of(2000, 1, 1), LocalDate.now(CALENDAR).plusYears(1));
        if (goodThrough == null) errors.add(new TradeProblem.FieldError("payoffGoodThrough",
            "Enter the date your lender's payoff amount is good through."));
      }
    }
    if (!errors.isEmpty()) throw invalid(errors);
    return new Content(vin, mileage, condition, owed, lender, payoff, goodThrough);
  }

  private static ValidOffer validateOffer(OfferInput input, MultipartFile file) {
    var errors = new ArrayList<TradeProblem.FieldError>();
    var given = input == null ? new OfferInput(null, null, null, null) : input;
    Long amount = null;
    try {
      if (given.amountMinor() != null && given.amountMinor().matches("\\d{1,12}")) amount = Long.parseLong(given.amountMinor());
    } catch (NumberFormatException ignored) { amount = null; }
    if (amount == null || amount <= 0 || amount > MAX_AMOUNT_MINOR)
      errors.add(new TradeProblem.FieldError("amountMinor", "Enter the amount written on the offer."));
    String source = text(given.source());
    if (source == null) errors.add(new TradeProblem.FieldError("source", "Enter who made the offer, up to 120 characters."));
    LocalDate today = LocalDate.now(CALENDAR);
    LocalDate offerDate = date(given.offerDate(), LocalDate.of(2000, 1, 1), today.plusDays(1));
    if (offerDate == null) errors.add(new TradeProblem.FieldError("offerDate", "Enter the date on the offer; it cannot be in the future."));
    LocalDate expiresOn = date(given.expiresOn(), offerDate == null ? LocalDate.of(2000, 1, 1) : offerDate,
        (offerDate == null ? today : offerDate).plusYears(1));
    if (expiresOn == null) errors.add(new TradeProblem.FieldError("expiresOn",
        "Enter the date the offer expires, on or after the offer date."));
    if (file == null || file.isEmpty()) errors.add(new TradeProblem.FieldError("file", "Add a photo or PDF of the written offer."));
    if (!errors.isEmpty()) throw invalid(errors);
    return new ValidOffer(amount, source, offerDate, expiresOn);
  }

  private static TradeProblem invalid(List<TradeProblem.FieldError> errors) {
    return new TradeProblem(HttpStatus.BAD_REQUEST, "TRADE_INVALID", "Check the highlighted trade-in details.", errors);
  }

  private static ResourceNotFoundException offerNotFound() { return new ResourceNotFoundException("Trade offer not found"); }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null) return null;
    try { return Enum.valueOf(type, value); } catch (IllegalArgumentException unknown) { return null; }
  }

  /** A short single line of Buyer text, or null when blank, too long or holding control characters. */
  private static String text(String value) {
    if (value == null) return null;
    String stripped = value.strip();
    if (stripped.isEmpty() || stripped.length() > MAX_TEXT || stripped.chars().anyMatch(Character::isISOControl)) return null;
    return stripped;
  }

  private static LocalDate date(String value, LocalDate earliest, LocalDate latest) {
    if (value == null) return null;
    try {
      var parsed = LocalDate.parse(value);
      return parsed.isBefore(earliest) || parsed.isAfter(latest) ? null : parsed;
    } catch (DateTimeParseException invalid) { return null; }
  }

  private static java.sql.Date date(LocalDate value) { return value == null ? null : java.sql.Date.valueOf(value); }

  private static String name(Enum<?> value) { return value == null ? null : value.name(); }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    Timestamp value = row.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
  }
}
