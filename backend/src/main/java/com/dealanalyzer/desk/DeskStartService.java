package com.dealanalyzer.desk;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence;
import com.dealanalyzer.extraction.persistence.DocumentExtractionPersistence.ExtractionView;
import com.dealanalyzer.finance.FinancingCalculator;
import com.dealanalyzer.review.RevisedOfferPolicy;
import com.dealanalyzer.trade.TradeService;
import com.dealanalyzer.trade.desk.TradeDeskCheck;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "I'm at the dealer": routes photographed desk paperwork by the VIN read from it, says which
 * questions the paperwork cannot answer, and gives a first result once the Buyer has confirmed the
 * routing. Nothing here stores anything: the paperwork goes through ordinary document intake, the
 * trade through {@link TradeService}, and typed four-square numbers are compared and not kept.
 */
@Service
public class DeskStartService {
  public static final String VERSION = "desk-start.v1";
  private static final List<String> LOAN = List.of("APR", "TERM_MONTHS", "MONTHLY_PAYMENT", "AMOUNT_FINANCED");
  private static final List<String> TRADE = List.of("TRADE_ALLOWANCE", "TRADE_PAYOFF");
  private static final long MAX_MONEY = 100_000_000_000L;

  private final EphemeralBuyingCaseService cases;
  private final DeskCheckService desk;
  private final DocumentExtractionPersistence extractions;
  private final TradeService trades;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TradeDeskCheck tradeCheck;
  private final com.dealanalyzer.desk.tactic.DeskTacticService tactics;
  private final FinancingCalculator financing = new FinancingCalculator();

  DeskStartService(EphemeralBuyingCaseService cases, DeskCheckService desk, DocumentExtractionPersistence extractions,
      TradeService trades, JdbcTemplate jdbc, ObjectMapper json, TradeDeskCheck tradeCheck,
      com.dealanalyzer.desk.tactic.DeskTacticService tactics) {
    this.cases = cases; this.desk = desk; this.extractions = extractions; this.trades = trades;
    this.jdbc = jdbc; this.json = json; this.tradeCheck = tradeCheck;
    this.tactics = tactics;
  }

  /**
   * status: AWAITING_PAPERWORK, READING, UNREADABLE (processing failed or nothing usable was read) or READ.
   * route: AGREED_CAR (the VIN matches the Agreed Price), THIS_CASE (no Agreed Price, and the case has no car
   * yet or the same one), FRESH_CASE (another car), or CHOOSE (no VIN read; the Buyer says which car).
   */
  public record Routing(String policyVersion, String status, String documentId, String documentType, String vin,
      String route, String agreedVin, String caseVin, FreshCase freshCase, Questions questions) {}
  public record FreshCase(String vin, String dealerLocation, String purchaseMethod) {
    @com.fasterxml.jackson.annotation.JsonProperty("dealerPostalCode")
    public String dealerPostalCode() {
      return dealerLocation != null && dealerLocation.matches("[0-9]{5}") ? dealerLocation : null;
    }
  }
  public record Questions(TradeQuestion trade, FinancingQuestion financing) {}
  /**
   * ask: NONE (the journey's or case's trade is recorded and complete), OWED (recorded, but whether money is
   * owed is unanswered), DETAILS (the paperwork shows a trade that is not recorded) or WHETHER (nothing shows one).
   */
  public record TradeQuestion(String ask, boolean paperworkShowsTrade, TradeService.Trade recorded) {}
  /** Asked only when the paperwork shows a loan and the Buyer has no outside financing recorded on this case. */
  public record FinancingQuestion(boolean ask, boolean paperworkShowsLoan, BigDecimal ownAprPercent, Integer ownTermMonths) {}

  public record ResultRequest(String documentId, String route, Typed typed, BigDecimal ownAprPercent) {}
  /** The four-square's numbers as the Buyer typed them; every field is optional, but one number is needed. */
  public record Typed(String vin, Long sellingPriceMinor, Long tradeAllowanceMinor, Long cashDownMinor,
      Long monthlyPaymentMinor, Integer termMonths, BigDecimal aprPercent, Long outTheDoorMinor, Long amountFinancedMinor) {}

  /** source: READ_FROM_PAPERWORK or TYPED_BY_BUYER; neither is verified. */
  public record FirstResult(String policyVersion, String route, String source, OutTheDoor outTheDoor, Finding lead,
      List<Finding> findings, TradeDeskCheck.Result trade,
      List<com.dealanalyzer.desk.tactic.DeskTacticPolicy.Finding> tacticFindings) {}
  /** amountMinor is null when the paperwork shows no out-the-door total; it is never treated as zero. */
  public record OutTheDoor(Long amountMinor, Long agreedMinor, Long differenceMinor) {}
  /** amountMinor is the finding's dollar effect when it can be calculated, otherwise null. */
  public record Finding(String code, String summary, Long amountMinor, String deskQuestion, JsonNode evidence, String tacticCode) {
    public Finding(String code, String summary, Long amountMinor, String deskQuestion, JsonNode evidence) {
      this(code, summary, amountMinor, deskQuestion, evidence, null);
    }
  }

  private record Document(String id, String stage) {}

  @Transactional(readOnly = true)
  public Routing routing(String caseId, String token, String documentId) {
    cases.reopen(caseId, token);
    var agreed = desk.agreedBaseline(caseId, token).orElse(null);
    String agreedVin = agreed == null ? null : agreed.agreed().vin();
    String caseVin = caseVin(caseId);
    // Without a named document, only paperwork added after the Agreed Price counts, as in the Desk Check.
    var document = document(caseId, documentId, agreed == null ? null : agreed.agreed().acceptedAt());
    if (document == null)
      return new Routing(VERSION, "AWAITING_PAPERWORK", null, null, null, null, agreedVin, caseVin, null,
          questions(caseId, token, null));
    ExtractionView extraction = null;
    if (!"FAILED".equals(document.stage())) {
      try { extraction = extractions.requireLatest(caseId, document.id()); }
      catch (ResourceNotFoundException reading) {
        return new Routing(VERSION, "READING", document.id(), null, null, null, agreedVin, caseVin, null, null);
      }
    }
    JsonNode terms = extraction == null ? null : desk.asRead(extraction);
    String vin = terms == null ? null : value(terms, "VEHICLE_VIN");
    boolean usable = terms != null && (vin != null || terms.path("lineItems").size() > 0
        || stream(terms.path("candidates")).anyMatch(term -> "USD_MINOR".equals(term.path("unit").asText(null))
            && !term.path("value").isNull()));
    String route = route(vin, agreedVin, caseVin);
    FreshCase fresh = null;
    if ("FRESH_CASE".equals(route)) {
      var place = jdbc.queryForObject("select dealer_location, purchase_method from analysis_cases where id = ?",
          (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId);
      fresh = new FreshCase(vin, place[0], place[1]);
    }
    return new Routing(VERSION, usable ? "READ" : "UNREADABLE", document.id(),
        terms == null ? null : value(terms, "DOCUMENT_TYPE"), vin, route, agreedVin, caseVin, fresh,
        questions(caseId, token, terms));
  }

  @Transactional(readOnly = true)
  public FirstResult result(String caseId, String token, ResultRequest request) {
    cases.reopen(caseId, token);
    if (request == null || !"AGREED_CAR".equals(request.route()) && !"THIS_CASE".equals(request.route()))
      throw DeskStartProblem.invalid("route", "Confirm which car this paperwork is for before it is compared.");
    var agreed = "AGREED_CAR".equals(request.route()) ? desk.agreedBaseline(caseId, token).orElseThrow(() ->
        new DeskStartProblem(HttpStatus.CONFLICT, "NO_AGREED_PRICE", "This case has no Agreed Price to compare with.", "route"))
        : null;
    String source;
    JsonNode terms;
    Set<String> typedFields = null;
    if (request.typed() != null) {
      source = "TYPED_BY_BUYER";
      typedFields = new HashSet<>();
      terms = typed(request.typed(), typedFields);
    } else {
      source = "READ_FROM_PAPERWORK";
      var document = document(caseId, request.documentId(), null);
      if (document == null) throw DeskStartProblem.invalid("documentId", "Photograph the paperwork first.");
      try { terms = desk.asRead(extractions.requireLatest(caseId, document.id())); }
      catch (ResourceNotFoundException reading) {
        throw new DeskStartProblem(HttpStatus.CONFLICT, "STILL_READING",
            "The paperwork is still being read. The result appears when it is.", "documentId");
      }
    }
    String vin = value(terms, "VEHICLE_VIN");
    if (agreed != null && vin != null && agreed.agreed().vin() != null && !vin.equals(agreed.agreed().vin()))
      throw new DeskStartProblem(HttpStatus.CONFLICT, "DIFFERENT_VIN",
          "This paperwork is for a different car, so it is not compared with your Agreed Price.", "route");
    BigDecimal ownApr = request.ownAprPercent() != null ? request.ownAprPercent() : (BigDecimal) ownFinancing(caseId)[0];
    if (ownApr != null && (ownApr.signum() < 0 || ownApr.compareTo(new BigDecimal("100")) > 0))
      throw DeskStartProblem.invalid("ownAprPercent", "Enter your own lender's APR as a percentage from 0 to 100.");

    List<Finding> findings = new ArrayList<>(agreed != null
        ? againstAgreed(agreed.terms(), terms, typedFields) : fromTermsAlone(terms));
    Long otd = amount(terms, "OUT_THE_DOOR_TOTAL");
    if (otd == null) findings.add(missingTotal(terms));
    var trade = tradeCheck.check(caseId, token, terms, agreed == null ? null : agreed.terms());
    var tacticFindings = tactics.findings(caseId, terms, agreed == null ? null : agreed.terms(), request.ownAprPercent(), trade);
    for (var tactic : tacticFindings) if (tactic.tradeFindingCode() == null)
      findings.add(new Finding(tactic.code(), tactic.summary(), tactic.totalEffectMinor(),
          tactic.deskQuestion(), json.valueToTree(tactic.evidenceRegions())));
    JsonNode allowanceEvidence = stream(terms.path("candidates"))
        .filter(term -> "TRADE_ALLOWANCE".equals(term.path("field").asText())).findFirst()
        .map(term -> nullable(term.path("evidence"))).orElse(null);
    for (var item : trade.leading()) {
      var tactic = tacticFindings.stream().filter(f -> item.code().equals(f.tradeFindingCode())).findFirst().orElse(null);
      findings.add(new Finding(item.code(), (tactic == null ? "" : tactic.label() + ": ") + item.summary(), item.amountMinor(),
          tactic == null ? item.deskQuestion() : tactic.deskQuestion(), allowanceEvidence, tactic == null ? null : tactic.code()));
    }
    // A different trade-in VIN leads; then the largest calculable dollar effect; findings without an amount
    // keep their rule order after those.
    findings.sort(Comparator.comparing((Finding finding) -> !finding.code().equals("TRADE_VIN_DIFFERENT"))
        .thenComparing(finding -> finding.amountMinor() == null)
        .thenComparing(finding -> finding.amountMinor() == null ? 0 : -finding.amountMinor()));
    Long agreedOtd = agreed == null ? null : agreed.agreed().outTheDoorMinor();
    var total = new OutTheDoor(otd, agreedOtd, otd == null || agreedOtd == null ? null : Math.subtractExact(otd, agreedOtd));
    return new FirstResult(VERSION, request.route(), source, total, findings.isEmpty() ? null : findings.get(0),
        List.copyOf(findings), "NO_ALLOWANCE".equals(trade.status()) ? null : trade,
        tacticFindings);
  }

  private static String route(String vin, String agreedVin, String caseVin) {
    if (vin == null) return agreedVin != null || caseVin != null ? "CHOOSE" : "THIS_CASE";
    if (agreedVin != null) return vin.equals(agreedVin) ? "AGREED_CAR" : "FRESH_CASE";
    return caseVin == null || caseVin.equals(vin) ? "THIS_CASE" : "FRESH_CASE";
  }

  private Questions questions(String caseId, String token, JsonNode terms) {
    boolean showsTrade = terms != null && TRADE.stream().anyMatch(field -> value(terms, field) != null);
    boolean showsLoan = terms != null && LOAN.stream().anyMatch(field -> value(terms, field) != null);
    var recorded = trades.view(caseId, token).trade();
    String ask = recorded != null ? (recorded.moneyOwed() == null ? "OWED" : "NONE") : showsTrade ? "DETAILS" : "WHETHER";
    Object[] own = ownFinancing(caseId);
    return new Questions(new TradeQuestion(ask, showsTrade, recorded),
        new FinancingQuestion(showsLoan && own[0] == null, showsLoan, (BigDecimal) own[0], (Integer) own[1]));
  }

  /** The APR and term of the Buyer's latest saved outside financing on this case, or nulls. */
  private Object[] ownFinancing(String caseId) {
    return jdbc.query("select payload_json from outside_finance_offers where case_id = ? order by version desc fetch first 1 rows only",
        (r, n) -> r.getString(1), caseId).stream().findFirst().map(payload -> {
          try {
            JsonNode loan = json.readTree(payload);
            return new Object[] {loan.path("aprPercent").isNumber() ? loan.path("aprPercent").decimalValue() : null,
                loan.path("termMonths").isInt() ? loan.path("termMonths").intValue() : null};
          } catch (java.io.IOException unreadable) { return new Object[2]; }
        }).orElse(new Object[2]);
  }

  private String caseVin(String caseId) {
    return jdbc.query("select vin from vehicles where case_id = ?", (r, n) -> r.getString(1), caseId)
        .stream().filter(Objects::nonNull).findFirst().orElse(null);
  }

  /** The named dealer paperwork, or the case's latest (after {@code after}, when given) when none is named. */
  private Document document(String caseId, String documentId, java.time.Instant after) {
    if (documentId != null && documentId.length() > 36) throw new ResourceNotFoundException("Document not found");
    var found = jdbc.query("""
        select d.id, (select op.stage from document_processing_operations op where op.document_id = d.id
                      order by op.updated_at desc fetch first 1 rows only)
        from source_documents d where d.case_id = ? and d.purpose = 'DEALER_PAPERWORK'
          and (cast(? as varchar(36)) is null or d.id = ?) and (cast(? as varchar(36)) is not null or d.created_at > ?)
        order by d.created_at desc, d.id desc fetch first 1 rows only
        """, (r, n) -> new Document(r.getString(1), r.getString(2)), caseId, documentId, documentId, documentId,
        java.sql.Timestamp.from(after == null ? java.time.Instant.EPOCH : after))
        .stream().findFirst().orElse(null);
    if (found == null && documentId != null) throw new ResourceNotFoundException("Document not found");
    return found;
  }

  /** Typed numbers in the verified-offer term shape; only the fields the Buyer filled are compared. */
  private JsonNode typed(Typed typed, Set<String> fields) {
    ObjectNode terms = json.createObjectNode();
    ArrayNode candidates = terms.putArray("candidates");
    terms.putArray("lineItems");
    if (typed.vin() != null && !typed.vin().isBlank()) {
      String vin;
      try { vin = Vin.validated(typed.vin()); }
      catch (InvalidVehicleEvidenceException invalid) {
        throw DeskStartProblem.invalid("vin", "Check the VIN: it should be 17 letters and numbers and pass the VIN check digit.");
      }
      candidates.addObject().put("field", "VEHICLE_VIN").put("value", vin).put("unit", "VIN");
    }
    money(candidates, fields, "SELLING_PRICE", typed.sellingPriceMinor(), "sellingPriceMinor");
    money(candidates, fields, "TRADE_ALLOWANCE", typed.tradeAllowanceMinor(), "tradeAllowanceMinor");
    money(candidates, fields, "CASH_DOWN", typed.cashDownMinor(), "cashDownMinor");
    money(candidates, fields, "MONTHLY_PAYMENT", typed.monthlyPaymentMinor(), "monthlyPaymentMinor");
    money(candidates, fields, "OUT_THE_DOOR_TOTAL", typed.outTheDoorMinor(), "outTheDoorMinor");
    money(candidates, fields, "AMOUNT_FINANCED", typed.amountFinancedMinor(), "amountFinancedMinor");
    if (typed.termMonths() != null) {
      if (typed.termMonths() < 1 || typed.termMonths() > 120)
        throw DeskStartProblem.invalid("termMonths", "Enter the loan term in months, from 1 to 120.");
      candidates.addObject().put("field", "TERM_MONTHS").put("value", typed.termMonths().toString()).put("unit", "MONTH");
      fields.add("candidate:TERM_MONTHS#1");
    }
    if (typed.aprPercent() != null) {
      if (typed.aprPercent().signum() < 0 || typed.aprPercent().compareTo(new BigDecimal("100")) > 0)
        throw DeskStartProblem.invalid("aprPercent", "Enter the APR as a percentage from 0 to 100.");
      candidates.addObject().put("field", "APR").put("value", typed.aprPercent().stripTrailingZeros().toPlainString()).put("unit", "PERCENT");
      fields.add("candidate:APR#1");
    }
    if (fields.isEmpty())
      throw DeskStartProblem.invalid("sellingPriceMinor", "Type at least one number from the worksheet.");
    return terms;
  }

  private static void money(ArrayNode candidates, Set<String> fields, String field, Long minor, String name) {
    if (minor == null) return;
    if (minor < 0 || minor > MAX_MONEY) throw DeskStartProblem.invalid(name, "Enter a dollar amount of zero or more.");
    candidates.addObject().put("field", field).put("value", minor.toString()).put("unit", "USD_MINOR");
    fields.add("candidate:" + field + "#1");
  }

  /** Differences from the Approved Baseline that cost the Buyer money or change the loan. */
  private List<Finding> againstAgreed(JsonNode agreedTerms, JsonNode deskTerms, Set<String> typedFields) {
    List<Finding> findings = new ArrayList<>();
    for (var row : new RevisedOfferPolicy().compare("BASELINE", agreedTerms, deskTerms)) {
      if (row.kind().equals("UNCERTAIN") || row.severity().equals("INFORMATIONAL")) continue;
      if (typedFields != null && !typedFields.contains(row.term())) continue;
      JsonNode evidence = row.observedEvidence() == null ? null : nullable(row.observedEvidence().path("evidence"));
      if (row.term().startsWith("line:")) {
        String label = row.observedEvidence() != null ? row.observedEvidence().path("originalLabel").asText()
            : row.expectedEvidence().path("originalLabel").asText();
        Long was = minor(row.expected()), now = minor(row.observed());
        if (row.kind().equals("ADDED") && now != null && now > 0)
          findings.add(new Finding("ADDED_CHARGE", "Added: " + label + ", " + money(now) + ". It is not in your Agreed Price.", now,
              "The " + label + " for " + money(now) + " was not in the price we agreed. Will you take it off?", evidence));
        else if (row.kind().equals("MISSING") && was != null && was < 0)
          findings.add(new Finding("CREDIT_REMOVED", "Missing: the " + label + " of " + money(-was) + " in your Agreed Price.", -was,
              "The " + label + " of " + money(-was) + " we agreed is not on this paperwork. Will you put it back?", null));
        else if (row.kind().equals("CHANGED") && was != null && now != null && now > was)
          findings.add(new Finding("CHARGE_INCREASED", label + " went from " + money(was) + " to " + money(now) + ".", now - was,
              "We agreed " + money(was) + " for the " + label + ". Why is it " + money(now) + " now?", evidence));
        continue;
      }
      String field = row.term().replaceFirst("^candidate:", "").replaceFirst("#\\d+$", "");
      Long was = minor(row.expected()), now = minor(row.observed());
      switch (field) {
        case "SELLING_PRICE" -> { if (was != null && now != null && now > was)
          findings.add(new Finding("PRICE_INCREASED", "Selling price went from " + money(was) + " to " + money(now) + ".", now - was,
              "We agreed a selling price of " + money(was) + ". Why does this say " + money(now) + "?", evidence)); }
        case "DISCOUNT_TOTAL", "REBATE_TOTAL" -> {
          String name = field.equals("DISCOUNT_TOTAL") ? "discount" : "rebate";
          long before = was == null ? 0 : Math.abs(was), after = now == null ? 0 : Math.abs(now);
          if (was != null && after < before)
            findings.add(new Finding("CREDIT_REDUCED", "The " + name + " went from " + money(before) + " to " + money(after) + ".",
                before - after, "We agreed a " + name + " of " + money(before) + ". Why is it " + money(after) + " here?", evidence)); }
        case "CASH_DOWN" -> { if (was != null && now != null && now > was)
          findings.add(new Finding("DOWN_PAYMENT_INCREASED", "Cash down went from " + money(was) + " to " + money(now) + ".", now - was,
              "We agreed " + money(was) + " down. Why does this ask for " + money(now) + "?", evidence)); }
        case "TRADE_ALLOWANCE" -> { if (was != null && now != null && now < was)
          findings.add(new Finding("TRADE_ALLOWANCE_LOWER", "Trade-in allowance went from " + money(was) + " to " + money(now) + ".",
              was - now, "We agreed " + money(was) + " for my trade-in. Why is it " + money(now) + " here?", evidence)); }
        case "MONTHLY_PAYMENT" -> { if (was != null && now != null && now > was) {
          Integer term = integer(value(deskTerms, "TERM_MONTHS"));
          findings.add(new Finding("PAYMENT_INCREASED", "Monthly payment went from " + money(was) + " to " + money(now)
              + (term == null ? "." : ", " + money((now - was) * term) + " over " + term + " months."),
              term == null ? null : (now - was) * term,
              "We agreed a payment of " + money(was) + ". Why is it " + money(now) + "?", evidence)); } }
        case "APR" -> { BigDecimal a = decimal(row.expected()), b = decimal(row.observed());
          if (a != null && b != null && b.compareTo(a) > 0) {
            Long cost = interestDifference(deskTerms, a, b);
            findings.add(new Finding("APR_INCREASED", "APR went from " + a.toPlainString() + "% to " + b.toPlainString() + "%"
                + (cost == null ? "." : ", about " + money(cost) + " more in payments over the loan."), cost,
                "We agreed " + a.toPlainString() + "% APR. Why is it " + b.toPlainString() + "%?", evidence)); } }
        case "TERM_MONTHS" -> { Integer a = integer(row.expected()), b = integer(row.observed());
          if (a != null && b != null && !a.equals(b))
            findings.add(new Finding("TERM_CHANGED", "Loan term went from " + a + " to " + b + " months.", null,
                "We agreed " + a + " months. Why is the term " + b + " months?", evidence)); }
        default -> { }
      }
    }
    return findings;
  }

  /** Without an Agreed Price: each dealer add-on and fee as read, largest first once sorted. */
  private List<Finding> fromTermsAlone(JsonNode terms) {
    List<Finding> findings = new ArrayList<>();
    for (JsonNode line : terms.path("lineItems")) {
      long amount = line.path("amountMinor").asLong(0);
      String label = line.path("originalLabel").asText();
      JsonNode evidence = nullable(line.path("evidence"));
      if (amount <= 0) continue;
      if (line.path("category").asText().equals("ADD_ON"))
        findings.add(new Finding("ADD_ON", label + ", " + money(amount) + ", is an add-on.", amount,
            "Is the " + label + " optional? Please show me the price without it.", evidence));
      else if (line.path("category").asText().equals("DEALER_FEE"))
        findings.add(new Finding("DEALER_FEE", label + ", " + money(amount) + ", is a dealer charge.", amount,
            "What is the " + label + " for, and will you lower or remove it?", evidence));
    }
    return findings;
  }

  private Finding missingTotal(JsonNode terms) {
    boolean paymentOnly = value(terms, "MONTHLY_PAYMENT") != null;
    return paymentOnly
        ? new Finding("PAYMENT_WITHOUT_TOTAL", "This shows a monthly payment but no out-the-door total.", null,
            "What is the out-the-door total, and what price, APR and term is this payment based on?", null)
        : new Finding("TOTAL_NOT_SHOWN", "This paperwork shows no out-the-door total.", null,
            "What is the out-the-door total, with every fee and tax included?", null);
  }

  /** Scheduled payments at the higher APR minus the lower, on the paperwork's amount financed and term. */
  private Long interestDifference(JsonNode terms, BigDecimal lower, BigDecimal higher) {
    Long principal = amount(terms, "AMOUNT_FINANCED");
    Integer months = integer(value(terms, "TERM_MONTHS"));
    if (principal == null || months == null) return null;
    var low = financing.reconcilePayment(principal, lower, months, 0L).paymentMinor();
    var high = financing.reconcilePayment(principal, higher, months, 0L).paymentMinor();
    return low == null || high == null ? null : Math.multiplyExact(high - low, (long) months);
  }

  private static String value(JsonNode terms, String field) {
    for (JsonNode term : terms.path("candidates"))
      if (term.path("field").asText().equals(field) && term.path("value").isTextual() && !term.path("value").asText().isBlank())
        return term.path("value").asText();
    return null;
  }
  private static Long amount(JsonNode terms, String field) { return minor(value(terms, field)); }
  /** A term's scalar: a candidate's value, a line's amount, or the text itself. */
  private static String scalar(Object value) {
    if (!(value instanceof JsonNode node)) return (String) value;
    if (node.isObject()) node = node.has("amountMinor") ? node.get("amountMinor") : node.path("value");
    return node.isNumber() || node.isTextual() ? node.asText() : null;
  }
  private static Long minor(Object value) {
    String text = scalar(value);
    try { return text == null ? null : Long.valueOf(text); } catch (NumberFormatException unreadable) { return null; }
  }
  private static Integer integer(Object value) {
    Long number = minor(value);
    return number == null || number > Integer.MAX_VALUE ? null : number.intValue();
  }
  private static BigDecimal decimal(Object value) {
    String text = scalar(value);
    try { return text == null ? null : new BigDecimal(text); } catch (NumberFormatException unreadable) { return null; }
  }
  private static JsonNode nullable(JsonNode value) { return value == null || value.isMissingNode() || value.isNull() ? null : value; }
  private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    return java.util.stream.StreamSupport.stream(array.spliterator(), false);
  }
  private static String money(long minor) {
    var format = NumberFormat.getCurrencyInstance(Locale.US);
    if (minor % 100 == 0) format.setMaximumFractionDigits(0);
    return format.format(minor / 100.0);
  }
}
