package com.dealanalyzer.explanation;

import com.dealanalyzer.explanation.ClaimVocabulary.Subject;
import com.dealanalyzer.explanation.ClaimVocabulary.Term;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an explanation may describe, read only from the finalized findings of one Deal Analysis
 * Report: review status, availability states, the deal rating, reconciliation results and the
 * material amounts with their precision. It never reads document text, evidence labels, provider
 * prose or extraction candidates, and nothing here can change the report.
 */
public record ExplanationFacts(int reportVersion, List<Finding> findings, List<Amount> amounts) {
  public static final String CORE_SCHEMA = "core-deal-analysis-report.v1";

  public ExplanationFacts {
    findings = List.copyOf(findings);
    amounts = List.copyOf(amounts);
  }

  /**
   * One recorded finding. A sentence citing it may use only the state terms in {@code permits} and
   * must use one of {@code requires} when that set is not empty, so a finding recorded as unknown,
   * unverifiable or stale is stated as such.
   * {@code state} is a code-owned value (an enum name, a boolean, or {@code RECORDED}); it is the only
   * part of the finding the model sees.
   */
  public record Finding(String id, String sourcePointer, String sourceValue, String state, Set<Subject> subjects,
      Set<Term> permits, Set<Term> requires) {
    public Finding {
      subjects = Set.copyOf(subjects);
      permits = Set.copyOf(permits);
      requires = Set.copyOf(requires);
    }
  }

  /** One material amount, identified by its index in {@code /analysis/materialAmounts}. */
  public record Amount(String id, int index, String sourcePointer, Long minorUnits, String currency,
      String precision, Set<Subject> subjects) {
    public Amount { subjects = Set.copyOf(subjects); }

    public boolean known() {
      return minorUnits != null && !"UNAVAILABLE".equals(precision);
    }

    /** The figure as the report shows it; an unknown amount is "unknown", an estimate says so. */
    public String rendered() {
      if (!known()) return "unknown";
      NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
      format.setCurrency(Currency.getInstance(currency));
      String figure = format.format(BigDecimal.valueOf(minorUnits, 2));
      return "ESTIMATED".equals(precision) ? figure + " (estimated)" : figure;
    }

    Term precisionTerm() {
      return switch (precision) {
        case "EXACT" -> Term.EXACT;
        case "ESTIMATED" -> Term.ESTIMATED;
        default -> Term.UNAVAILABLE;
      };
    }
  }

  public Finding finding(String id) {
    return findings.stream().filter(finding -> finding.id().equals(id)).findFirst().orElse(null);
  }

  public Finding findingAt(String pointer) {
    return findings.stream().filter(finding -> finding.sourcePointer().equals(pointer)).findFirst().orElse(null);
  }

  public Amount amount(String id) {
    return amounts.stream().filter(amount -> amount.id().equals(id)).findFirst().orElse(null);
  }

  public static ExplanationFacts from(JsonNode report) {
    if (!CORE_SCHEMA.equals(report.path("schemaVersion").asText()))
      throw new IllegalArgumentException("Finalized core report required");
    JsonNode analysis = report.path("analysis");
    List<Finding> findings = new ArrayList<>();
    String review = analysis.path("reviewStatus").asText();
    switch (review) {
      case "MATCHES_APPROVED_BASELINE" -> add(findings, report, "review-status", "/analysis/reviewStatus",
          EnumSet.of(Subject.APPROVAL), EnumSet.of(Term.MATCH, Term.WITHIN), Set.of());
      case "REVIEW_REQUIRED" -> add(findings, report, "review-status", "/analysis/reviewStatus",
          EnumSet.of(Subject.APPROVAL), EnumSet.of(Term.REVIEW, Term.DIFFERS), Set.of());
      case "UNABLE_TO_VERIFY" -> add(findings, report, "review-status", "/analysis/reviewStatus",
          EnumSet.of(Subject.APPROVAL), EnumSet.of(Term.UNVERIFIABLE, Term.UNAVAILABLE), EnumSet.of(Term.UNVERIFIABLE));
      default -> throw new IllegalArgumentException("Unknown finalized review status");
    }
    switch (analysis.path("dealRating").asText()) {
      case "STRONG_DEAL" -> add(findings, report, "deal-rating", "/analysis/dealRating",
          EnumSet.of(Subject.RATING, Subject.MARKET), EnumSet.of(Term.BELOW, Term.STRONG, Term.ESTIMATED, Term.AVAILABLE), Set.of());
      case "FAIR_NEGOTIATE" -> add(findings, report, "deal-rating", "/analysis/dealRating",
          EnumSet.of(Subject.RATING, Subject.MARKET), EnumSet.of(Term.WITHIN, Term.FAIR, Term.ESTIMATED, Term.AVAILABLE), Set.of());
      case "POOR_DEAL" -> add(findings, report, "deal-rating", "/analysis/dealRating",
          EnumSet.of(Subject.RATING, Subject.MARKET), EnumSet.of(Term.ABOVE, Term.POOR, Term.ESTIMATED, Term.AVAILABLE), Set.of());
      case "INSUFFICIENT_DATA" -> add(findings, report, "deal-rating", "/analysis/dealRating",
          EnumSet.of(Subject.RATING, Subject.MARKET), EnumSet.of(Term.UNAVAILABLE), EnumSet.of(Term.UNAVAILABLE));
      default -> throw new IllegalArgumentException("Unknown finalized deal rating");
    }
    availability(findings, report, "market-availability", "/analysis/marketAvailability",
        EnumSet.of(Subject.MARKET), EnumSet.of(Term.ESTIMATED));
    availability(findings, report, "cash-availability", "/analysis/cashAvailability",
        EnumSet.of(Subject.CASH), Set.of());
    String financing = analysis.path("financingAvailability").asText();
    if (!financing.isEmpty() && !"NOT_APPLICABLE".equals(financing)) {
      availability(findings, report, "financing-availability", "/analysis/financingAvailability",
          EnumSet.of(Subject.FINANCING), Set.of());
      JsonNode tolerance = analysis.at("/financing/withinTolerance");
      if ("AVAILABLE".equals(financing) && tolerance.isBoolean()) {
        reconciliation(findings, report, "payment-reconciliation", "/analysis/financing/withinTolerance",
            EnumSet.of(Subject.PAYMENT, Subject.FINANCING), tolerance.booleanValue());
      }
      JsonNode addOns = analysis.at("/financingTerms/financedAddOns");
      if ("AVAILABLE".equals(financing) && addOns.isArray() && !addOns.isEmpty()) {
        add(findings, report, "financed-add-ons", "/analysis/financingTerms/financedAddOns",
            EnumSet.of(Subject.FINANCING, Subject.ADD_ONS), EnumSet.of(Term.AVAILABLE), Set.of());
      }
    }
    JsonNode quoted = report.at("/evidence/cashEconomics/result/reconciliation/withinTolerance");
    if ("AVAILABLE".equals(analysis.path("cashAvailability").asText()) && quoted.isBoolean()) {
      reconciliation(findings, report, "quoted-total-reconciliation",
          "/evidence/cashEconomics/result/reconciliation/withinTolerance", EnumSet.of(Subject.CASH), quoted.booleanValue());
    }
    List<Amount> amounts = new ArrayList<>();
    JsonNode material = analysis.path("materialAmounts");
    for (int index = 0; index < material.size(); index++) {
      JsonNode amount = material.get(index);
      String precision = amount.path("precision").asText();
      if (!List.of("EXACT", "ESTIMATED", "UNAVAILABLE").contains(precision))
        throw new IllegalArgumentException("Unknown amount precision");
      Long minor = amount.path("minorUnits").isIntegralNumber() ? amount.path("minorUnits").longValue() : null;
      String currency = amount.path("currency").asText();
      if (minor != null && !"UNAVAILABLE".equals(precision) && !"USD".equals(currency)) {
        // Only US dollars are rendered; any other recorded figure is described as unknown.
        minor = null;
        precision = "UNAVAILABLE";
      }
      amounts.add(new Amount("amount-" + index, index, "/analysis/materialAmounts/" + index, minor, currency,
          precision, subjects(amount.path("source").asText())));
    }
    return new ExplanationFacts(report.path("version").asInt(), findings, amounts);
  }

  /**
   * The model's view of the report: finding states and amount identifiers with their precision. No
   * figure, label, document text, provider prose or extraction candidate is included.
   */
  public String modelInput(ObjectMapper json) {
    ObjectNode root = json.createObjectNode();
    var findingsNode = root.putArray("findings");
    for (Finding finding : findings) {
      var node = findingsNode.addObject().put("id", finding.id());
      var subjects = node.putArray("subjects");
      finding.subjects().stream().sorted().forEach(subject -> subjects.add(subject.name().toLowerCase(Locale.ROOT)));
      node.put("state", finding.state());
    }
    var amountsNode = root.putArray("amounts");
    for (Amount amount : amounts) {
      var node = amountsNode.addObject().put("id", amount.id());
      var subjects = node.putArray("subjects");
      amount.subjects().stream().sorted().forEach(subject -> subjects.add(subject.name().toLowerCase(Locale.ROOT)));
      node.put("precision", amount.precision());
    }
    return root.toString();
  }

  private static Set<Subject> subjects(String source) {
    return switch (source) {
      case "marketAssessment" -> EnumSet.of(Subject.MARKET);
      case "financing" -> EnumSet.of(Subject.FINANCING, Subject.PAYMENT);
      case "cashEconomics" -> EnumSet.of(Subject.CASH);
      case "approvedBaseline" -> EnumSet.of(Subject.APPROVAL);
      default -> Set.of();
    };
  }

  private static void availability(List<Finding> findings, JsonNode report, String id, String pointer,
      Set<Subject> subjects, Set<Term> availableTerms) {
    String state = report.at(pointer).asText();
    switch (state) {
      case "AVAILABLE" -> {
        Set<Term> permits = EnumSet.of(Term.AVAILABLE);
        permits.addAll(availableTerms);
        add(findings, report, id, pointer, subjects, permits, Set.of());
      }
      case "UNAVAILABLE", "UNSUPPORTED" -> add(findings, report, id, pointer, subjects,
          EnumSet.of(Term.UNAVAILABLE), EnumSet.of(Term.UNAVAILABLE));
      case "STALE_OR_UNVERIFIED" -> add(findings, report, id, pointer, subjects,
          EnumSet.of(Term.STALE, Term.UNVERIFIABLE, Term.UNAVAILABLE),
          EnumSet.of(Term.STALE, Term.UNVERIFIABLE, Term.UNAVAILABLE));
      default -> throw new IllegalArgumentException("Unknown finalized availability");
    }
  }

  private static void reconciliation(List<Finding> findings, JsonNode report, String id, String pointer,
      Set<Subject> subjects, boolean within) {
    if (within) add(findings, report, id, pointer, subjects,
        EnumSet.of(Term.MATCH, Term.WITHIN, Term.RECONCILES), Set.of());
    else add(findings, report, id, pointer, subjects, EnumSet.of(Term.DIFFERS, Term.ABOVE), Set.of());
  }

  private static void add(List<Finding> findings, JsonNode report, String id, String pointer, Set<Subject> subjects,
      Set<Term> permits, Set<Term> requires) {
    JsonNode source = report.at(pointer);
    if (source.isMissingNode() || source.isNull()) throw new IllegalArgumentException("Missing finding: " + pointer);
    String value = source.isTextual() ? source.asText() : source.toString();
    String state = source.isTextual() && source.asText().matches("[A-Z_]{1,40}") || source.isBoolean() ? value : "RECORDED";
    findings.add(new Finding(id, pointer, value, state, subjects, permits, requires));
  }
}
