package com.dealanalyzer.economics.fees;

import com.dealanalyzer.economics.fees.GovernmentFeeSchedule.Fee;
import com.dealanalyzer.economics.fees.GovernmentFeeSchedule.Group;
import com.dealanalyzer.economics.fees.GovernmentFeeSchedule.State;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code government-fee-check.v1}: each title, registration, plate or other state fee line on a Dealer Offer or
 * on desk paperwork against the {@link GovernmentFeeSchedule} for the Buyer's state (ticket 139). A line above
 * the most the state's listed fees add up to is asked about, and a dealer charge listed with the state fees is
 * named as one. It states the state's numbers and the paperwork's, and draws no conclusion about the dealer: a
 * dealer may also pass through a fee the schedule does not list. Taxes stay with the tax pack and are not
 * counted again.
 */
@Component
public class GovernmentFeeCheck {
  public static final String VERSION = "government-fee-check.v1";
  public static final String ITEMIZE_QUESTION = "Please itemize the state fees on this line.";
  /** Dates are read as the Buyer's pilot state reads them. */
  private static final ZoneId ZONE = ZoneId.of("America/New_York");

  private final GovernmentFeeSchedule schedule;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  @Autowired GovernmentFeeCheck(GovernmentFeeSchedule schedule, JdbcTemplate jdbc) {
    this(schedule, jdbc, Clock.system(ZONE));
  }

  GovernmentFeeCheck(GovernmentFeeSchedule schedule, JdbcTemplate jdbc, Clock clock) {
    this.schedule = schedule; this.jdbc = jdbc; this.clock = clock;
  }

  /**
   * status: CHECKED, or NOT_CHECKED when there is no current schedule for the Buyer's state, in which case no
   * amount is treated as verified. verified is true only when the fee lines rest on Buyer-verified terms.
   */
  public record Result(String policyVersion, String scheduleVersion, String state, String stateName, String status,
      LocalDate checkedOn, LocalDate reviewOn, long stateFeesMinor, Long highestMinor, List<Finding> findings,
      String summary, String scope, String note, List<Explained> fees) {}
  /**
   * code: ABOVE_SCHEDULE, LINES_ABOVE_SCHEDULE or DEALER_CHARGE_LABEL (severity MATERIAL), or WITHIN_RANGE,
   * NOT_IN_SCHEDULE, NO_TOP_AMOUNT or TAX_COVERED (INFORMATIONAL). highestMinor is the most the state's listed
   * fees for the line add up to, not counting the fees named in notCounted.
   */
  public record Finding(String code, String severity, String label, Long amountMinor, Long lowestMinor,
      Long highestMinor, String summary, String deskQuestion, List<String> dependsOn, List<String> notCounted,
      boolean verified, JsonNode evidence) {}
  /** What one of the state's fees is for, in plain words, and its range. */
  public record Explained(String name, String purpose, long minMinor, Long maxMinor, String dependsOn, String citation) {}

  /** The check for the case's state and purchase method, or null when the paperwork lists no state fee. */
  public Result check(String caseId, JsonNode terms, boolean verified) {
    var place = jdbc.query("select buyer_jurisdiction, purchase_method from analysis_cases where id = ?",
        (r, n) -> new String[] {r.getString(1), r.getString(2)}, caseId).stream().findFirst().orElse(new String[2]);
    Boolean financed = place[1] == null ? null : "FINANCE".equals(place[1]);
    return evaluate(schedule, stateOf(place[0]), financed, terms, verified, LocalDate.now(clock));
  }

  /** "US-FL:Miami-Dade" and "US-TX" name FL and TX; anything else names no state. */
  static String stateOf(String jurisdiction) {
    if (jurisdiction == null || !jurisdiction.matches("US-[A-Z]{2}(:.*)?")) return null;
    return jurisdiction.substring(3, 5);
  }

  /** amountMinor is null when the line's amount could not be read in dollars; it is never treated as zero. */
  private record FeeLine(String label, Long amountMinor, boolean verified, JsonNode evidence, String category) {
    boolean government() { return "GOVERNMENT_CHARGE".equals(category); }
  }
  private record Range(long lowest, Long highest, List<String> dependsOn, List<String> notCounted, Set<Group> groups) {}

  static Result evaluate(GovernmentFeeSchedule schedule, String stateCode, Boolean financed, JsonNode terms,
      boolean verified, LocalDate today) {
    var lines = feeLines(schedule, terms, verified);
    if (lines.isEmpty()) return null;
    long total = 0;
    for (var line : lines) if (line.amountMinor() != null) total = Math.addExact(total, line.amountMinor());
    State state = stateCode == null ? null : schedule.state(stateCode).orElse(null);
    if (state == null)
      return notChecked(schedule, stateCode, null, total, "otdog has no fee schedule for "
          + (stateCode == null ? "your state" : stateCode) + ", so the " + money(total)
          + " in state fees on this paperwork was not checked.");
    if (today.isBefore(state.effectiveOn()) || today.isAfter(state.reviewOn()))
      return notChecked(schedule, stateCode, state, total, state.name() + "'s fee schedule is due for review, so the "
          + money(total) + " in state fees on this paperwork was not checked.");

    List<Finding> findings = new ArrayList<>();
    List<FeeLine> counted = new ArrayList<>();
    boolean lineAbove = false;
    for (var line : lines) {
      String quoted = "“" + line.label() + "”";
      var tokens = FeeLabels.tokens(line.label());
      if (!FeeLabels.match(tokens, state.taxPackLabels()).isEmpty()) {
        findings.add(new Finding("TAX_COVERED", "INFORMATIONAL", line.label(), line.amountMinor(), null, null,
            quoted + " is a tax the tax check covers, so it is not counted again with the state fees.", null,
            List.of(), List.of(), line.verified(), line.evidence()));
        continue;
      }
      var dealer = FeeLabels.match(tokens, state.dealerChargeLabels());
      var rest = new ArrayList<>(tokens);
      for (int index : dealer) rest.set(index, "");
      var groups = groups(schedule, rest);
      String shown = line.amountMinor() == null ? "" : " (" + money(line.amountMinor()) + ")";
      if (!dealer.isEmpty() && groups.isEmpty()) {
        findings.add(new Finding("DEALER_CHARGE_LABEL", "MATERIAL", line.label(), line.amountMinor(), null, null,
            quoted + shown + " is listed with the state fees, but it is a name for a charge the dealer sets, not a "
                + state.name() + " state fee.",
            "Is " + quoted + " a dealer fee? If so, please list it with the dealer's charges, not the state fees.",
            List.of(), List.of(), line.verified(), line.evidence()));
        continue;
      }
      if (!dealer.isEmpty()) {
        // "Dealer title fee", "Doc & tag": part may be the dealer's own charge, so the line is asked about, not summed.
        findings.add(new Finding("MIXED_LABEL", "MATERIAL", line.label(), line.amountMinor(), null, null,
            quoted + shown + " names both a dealer charge and a state fee, so part of it may be a charge the dealer"
                + " sets rather than a " + state.name() + " state fee.",
            "Please show the dealer's charge and the state fees on " + quoted + " separately.",
            List.of(), List.of(), line.verified(), line.evidence()));
        continue;
      }
      if (line.amountMinor() == null) {
        findings.add(new Finding("NO_AMOUNT", "INFORMATIONAL", line.label(), null, null, null,
            quoted + " is listed as a state fee, but its amount could not be read, so it was not checked.",
            "What is the amount of " + quoted + ", and which state fees does it include?", List.of(), List.of(),
            line.verified(), line.evidence()));
        continue;
      }
      if (!line.government())
        findings.add(new Finding("LISTED_AS_DEALER_CHARGE", "INFORMATIONAL", line.label(), line.amountMinor(), null, null,
            quoted + shown + " is not listed with the state fees, but its name is a state fee's, so it is checked"
                + " against " + state.name() + "'s fees too.",
            "Is " + quoted + " a state fee the dealer passes through, or the dealer's own charge?", List.of(), List.of(),
            line.verified(), line.evidence()));
      counted.add(line);
      boolean unnamed = groups.isEmpty();
      var range = range(state, groups, financed);
      if (range.highest() == null) {
        findings.add(new Finding("NOT_IN_SCHEDULE", "INFORMATIONAL", line.label(), line.amountMinor(), null, null,
            quoted + shown + " is listed as a state fee, but " + state.name()
                + "'s fee schedule has no fee by that name. A dealer can pass through a fee this schedule does not list.",
            "Which state fee is " + quoted + ", and what sets its amount?", List.of(), List.of(), line.verified(),
            line.evidence()));
      } else if (range.highest() == 0 && !range.notCounted().isEmpty()) {
        findings.add(new Finding("NO_TOP_AMOUNT", "INFORMATIONAL", line.label(), line.amountMinor(), null, null,
            quoted + shown + " is the " + String.join(" and ", range.notCounted())
                + ", which goes by " + dependsOn(state, groups) + ", so this schedule has no top amount to compare it with.",
            null, List.of(), range.notCounted(), line.verified(), line.evidence()));
      } else if (line.amountMinor() > range.highest()) {
        lineAbove = true;
        findings.add(above("ABOVE_SCHEDULE", state, line, quoted + " on this paperwork is " + money(line.amountMinor())
            + "." + (unnamed ? " It does not say which state fees it covers." : ""), range, ITEMIZE_QUESTION));
      } else if (unnamed || !range.dependsOn().isEmpty()) {
        // A line can be one fee of its group, so it is held only to the group's highest amount.
        findings.add(new Finding("WITHIN_RANGE", "INFORMATIONAL", line.label(), line.amountMinor(), range.lowest(),
            range.highest(), (unnamed ? quoted + " does not say which state fees it covers. Its amount, "
                + money(line.amountMinor()) + ", is" : quoted + " is " + money(line.amountMinor()) + ",")
                + " within the most " + state.name() + " lists for " + feesNamed(range.groups()) + ", "
                + money(range.highest()) + notCounted(range) + depends(range) + ".",
            unnamed ? ITEMIZE_QUESTION : null, range.dependsOn(), range.notCounted(), line.verified(), line.evidence()));
      }
    }
    var all = range(state, EnumSet.allOf(Group.class), financed);
    long countedTotal = 0;
    for (var line : counted) countedTotal = Math.addExact(countedTotal, line.amountMinor());
    if (!lineAbove && counted.size() > 1 && all.highest() != null && countedTotal > all.highest()) {
      var first = counted.getFirst();
      findings.add(above("LINES_ABOVE_SCHEDULE", state, new FeeLine("State fee lines", countedTotal,
          counted.stream().allMatch(FeeLine::verified), first.evidence(), "GOVERNMENT_CHARGE"),
          "The state fee lines on this paperwork add up to " + money(countedTotal) + ".", all,
          "Please itemize the state fees on this paperwork."));
    }
    findings.sort(java.util.Comparator.comparing((Finding finding) -> !"MATERIAL".equals(finding.severity())));
    String summary = findings.stream().filter(finding -> "MATERIAL".equals(finding.severity())).findFirst()
        .map(Finding::summary).orElse("The state fees on this paperwork, " + money(total) + ", are within what "
            + state.name() + " lists for them.");
    return new Result(VERSION, schedule.version(), state.state(), state.name(), "CHECKED", state.checkedOn(),
        state.reviewOn(), total, all.highest(), List.copyOf(findings), summary, state.scope(), state.note(),
        explained(state));
  }

  private static Finding above(String code, State state, FeeLine line, String opening, Range range, String question) {
    return new Finding(code, "MATERIAL", line.label(), line.amountMinor(), range.lowest(), range.highest(),
        opening + " " + state.name() + "'s listed " + feesNamed(range.groups()) + " add up to at most "
            + money(range.highest()) + notCounted(range) + depends(range)
            + ". A dealer can also pass through a fee this schedule does not list.",
        question, range.dependsOn(), range.notCounted(), line.verified(), line.evidence());
  }

  private static Result notChecked(GovernmentFeeSchedule schedule, String code, State state, long total, String summary) {
    return new Result(VERSION, schedule.version(), code, state == null ? null : state.name(), "NOT_CHECKED",
        state == null ? null : state.checkedOn(), state == null ? null : state.reviewOn(), total, null, List.of(),
        summary, null, null, List.of());
  }

  /**
   * GOVERNMENT_CHARGE lines, and UNKNOWN or DEALER_FEE lines whose label names a state fee, such as a lump
   * "TT&L" line the reader could not classify or a "Title fee" listed with the dealer's charges. A line whose
   * amount cannot be read is kept, so it is asked about rather than dropped.
   */
  private static List<FeeLine> feeLines(GovernmentFeeSchedule schedule, JsonNode terms, boolean verified) {
    List<FeeLine> lines = new ArrayList<>();
    if (terms == null) return lines;
    for (JsonNode line : terms.path("lineItems")) {
      String category = line.path("category").asText();
      String label = line.path("originalLabel").asText("").strip();
      boolean government = "GOVERNMENT_CHARGE".equals(category);
      if (!government && !(("UNKNOWN".equals(category) || "DEALER_FEE".equals(category))
          && !groups(schedule, FeeLabels.tokens(label)).isEmpty())) continue;
      Long amount = line.path("amountMinor").isIntegralNumber() && "USD".equals(line.path("currency").asText("USD"))
          ? line.path("amountMinor").longValue() : null;
      lines.add(new FeeLine(label.isEmpty() ? "State fees" : label, amount,
          verified && line.path("buyerVerified").asBoolean(true),
          line.path("evidence").isMissingNode() || line.path("evidence").isNull() ? null : line.path("evidence"),
          category));
    }
    return lines;
  }

  /** The fee groups a label names; empty when it names none. */
  static Set<Group> groups(GovernmentFeeSchedule schedule, String label) {
    return groups(schedule, FeeLabels.tokens(label));
  }

  private static Set<Group> groups(GovernmentFeeSchedule schedule, List<String> tokens) {
    Set<Group> groups = EnumSet.noneOf(Group.class);
    for (var entry : schedule.data().labels())
      if (!FeeLabels.match(tokens, entry.terms()).isEmpty() && FeeLabels.match(tokens, entry.unless()).isEmpty())
        groups.addAll(entry.groups());
    return groups;
  }

  /** The state's fees in the named groups, or in all groups when the label names none. */
  private static Range range(State state, Set<Group> named, Boolean financed) {
    Set<Group> groups = named.isEmpty() ? EnumSet.allOf(Group.class) : named;
    long lowest = 0, highest = 0;
    boolean any = false;
    Set<String> depends = new LinkedHashSet<>();
    List<String> notCounted = new ArrayList<>();
    for (Fee fee : state.fees()) {
      if (!groups.contains(fee.group())) continue;
      any = true;
      if ("FINANCED".equals(fee.onlyWhen()) && Boolean.FALSE.equals(financed)) continue;
      if (fee.maxMinor() == null) {
        notCounted.add(fee.name().toLowerCase(Locale.ROOT));
        continue;
      }
      boolean known = "FINANCED".equals(fee.onlyWhen()) && Boolean.TRUE.equals(financed);
      lowest = Math.addExact(lowest, known ? fee.maxMinor() : fee.minMinor());
      highest = Math.addExact(highest, fee.maxMinor());
      if (!known && fee.minMinor() != fee.maxMinor()) depends.add(fee.dependsOn());
    }
    return new Range(lowest, any ? highest : null, List.copyOf(depends), List.copyOf(notCounted), groups);
  }

  private static String dependsOn(State state, Set<Group> groups) {
    return state.fees().stream().filter(fee -> groups.contains(fee.group()) && fee.maxMinor() == null)
        .map(Fee::dependsOn).findFirst().orElse("facts this schedule does not hold");
  }

  private static String feesNamed(Set<Group> groups) {
    if (groups.containsAll(EnumSet.allOf(Group.class))) return "state fees";
    List<String> names = new ArrayList<>();
    for (Group group : groups) names.add(switch (group) {
      case TITLE -> "title";
      case REGISTRATION -> "registration";
      case PLATE -> "plate";
      case TEMPORARY_TAG -> "temporary tag";
      case ENVIRONMENTAL -> "tire and battery";
      case PROPERTY_TAX -> "property tax";
    });
    String joined = names.size() < 2 ? names.getFirst()
        : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    return joined + " fees";
  }

  private static String notCounted(Range range) {
    return range.notCounted().isEmpty() ? "" : ", not counting the " + String.join(" and ", range.notCounted());
  }

  private static String depends(Range range) {
    if (range.dependsOn().isEmpty()) return "";
    var facts = range.dependsOn();
    return ". Which apply depends on " + (facts.size() < 2 ? facts.getFirst()
        : String.join("; ", facts.subList(0, facts.size() - 1)) + "; and " + facts.getLast());
  }

  private static List<Explained> explained(State state) {
    return state.fees().stream().map(fee -> new Explained(fee.name(), fee.purpose(), fee.minMinor(), fee.maxMinor(),
        fee.dependsOn(), fee.citation())).toList();
  }

  static String money(long minor) {
    var format = NumberFormat.getCurrencyInstance(Locale.US);
    if (minor % 100 == 0) format.setMaximumFractionDigits(0);
    return format.format(minor / 100.0);
  }
}
