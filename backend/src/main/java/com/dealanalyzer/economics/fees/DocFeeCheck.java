package com.dealanalyzer.economics.fees;

import com.dealanalyzer.buyingcase.location.PostalJurisdictionResolver;
import com.dealanalyzer.buyingcase.location.PostalResolution;
import com.dealanalyzer.review.RevisedOfferPolicy.Discrepancy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Dealer-state disclosure and documentary fee questions; never a dealer filing determination. */
@Component
public class DocFeeCheck {
  /** The catalogue id a Buyer confirms for a doc fee line (ticket 142). */
  public static final String DOC_FEE_ID = "doc-fee";
  private final JdbcTemplate jdbc;
  private final PostalJurisdictionResolver postal;
  private final ObjectMapper json;
  private final JsonNode data;

  public DocFeeCheck(JdbcTemplate jdbc, PostalJurisdictionResolver postal, ObjectMapper json) {
    this.jdbc = jdbc; this.postal = postal; this.json = json;
    try (var input = DocFeeCheck.class.getResourceAsStream("/fees/doc-fee-rules.v1.json")) {
      if (input == null) throw new IllegalStateException("Missing doc fee rules");
      data = json.readTree(input);
    } catch (IOException failure) { throw new IllegalStateException("Cannot read doc fee rules", failure); }
  }

  public List<Discrepancy> findings(String caseId, JsonNode terms) {
    String location = jdbc.queryForObject("select dealer_location from analysis_cases where id = ?", String.class, caseId);
    String state = null;
    if (location != null && location.matches("[0-9]{5}")) {
      var resolved = postal.resolve(location);
      if (resolved.status() == PostalResolution.Status.RESOLVED) state = resolved.jurisdiction();
    } else if (location != null && location.matches(".+, [A-Z]{2}")) {
      state = "US-" + location.substring(location.length() - 2);
    }
    return evaluate(state, terms, LocalDate.now(java.time.ZoneOffset.UTC));
  }

  public List<Discrepancy> evaluate(String state, JsonNode terms, LocalDate date) {
    JsonNode rule = null;
    for (var entry : data.path("rules"))
      if (entry.path("state").asText().equals(state)
          && !date.isBefore(LocalDate.parse(entry.path("effectiveFrom").asText()))
          && !date.isAfter(LocalDate.parse(entry.path("reviewBy").asText()))) rule = entry;
    if (rule == null || terms == null) return List.of();
    List<JsonNode> lines = new ArrayList<>();
    Long docTotal = 0L, possibleTotal = 0L;
    boolean verified = true;
    List<String> descriptions = new ArrayList<>();
    List<String> questions = new ArrayList<>();
    for (var line : terms.path("lineItems")) {
      String label = line.path("originalLabel").asText();
      String tier = recognition(line);
      if (tier == null || !"DEALER_FEE".equals(line.path("category").asText())) continue;
      Long amount = line.path("amountMinor").isIntegralNumber() && line.path("amountMinor").canConvertToLong()
          && "USD".equals(line.path("currency").asText()) && line.path("amountMinor").longValue() >= 0
          ? line.path("amountMinor").longValue() : null;
      boolean possible = tier.equals("POSSIBLE_DOC_FEE");
      if (possible) possibleTotal = sum(possibleTotal, amount); else docTotal = sum(docTotal, amount);
      var recorded = line.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) recorded).put("recognitionTier", tier);
      lines.add(recorded);
      descriptions.add(label + ": " + money(amount) + " on the paperwork. "
          + (possible ? "This may be a documentary fee under another name." : "Identified as a documentary fee line."));
      if (possible) questions.add("What does the " + label + " cover, and is it the documentary fee under another name?");
      verified &= line.path("buyerVerified").asBoolean(false);
    }
    if (lines.isEmpty()) return List.of();
    String kind = rule.path("kind").asText();
    boolean threshold = kind.equals("SAFE_HARBOR") || kind.equals("CAP");
    boolean above = threshold && docTotal != null && docTotal > rule.path("amountMinor").longValue();
    boolean hasPossible = lines.stream().anyMatch(line -> "POSSIBLE_DOC_FEE".equals(line.path("recognitionTier").asText()));
    Long combined = sum(docTotal, possibleTotal);
    String totals = " Identified documentary fee total: " + money(docTotal) + ".";
    if (hasPossible) totals += " Possible documentary fee aliases total: " + money(possibleTotal)
        + ". Combined total including possible aliases: " + money(combined)
        + ". The combined total is not an established documentary fee amount; ask what the possible aliases cover.";
    if (above || !threshold && !hasPossible) questions.add(rule.path("question").asText());
    if (docTotal == null) questions.add("Please confirm the documentary fee amounts and currency so they can be compared with the state rule.");
    if (questions.isEmpty()) questions.add("Please confirm what these documentary fee lines cover.");
    String reason = String.join(" ", descriptions) + totals
        + (hasPossible ? " State rule for documentary fees, subject to clarifying the possible aliases: " : " ")
        + rule.path("summary").asText()
        + " Source: " + rule.path("citation").asText() + ". Checked "
        + rule.path("checkedAt").asText() + "; review by " + rule.path("reviewBy").asText() + ".";
    var evidence = json.createObjectNode();
    evidence.set("lines", json.valueToTree(lines));
    evidence.set("rule", rule.deepCopy());
    evidence.put("policyVersion", data.path("version").asText());
    var observed = json.createObjectNode().put("currency", "USD");
    observed.put("amountMinor", docTotal);
    observed.put("possibleDocFeeAmountMinor", possibleTotal);
    observed.put("combinedAmountMinor", combined);
    return List.of(new Discrepancy("STATE_RULE", "Dealer documentary fee", "DOC_FEE_CHECK", null,
        observed, null, "USD_MINOR", above ? "MATERIAL" : "INFORMATIONAL", reason,
        null, evidence, null, !verified, String.join(" ", questions)));
  }

  /**
   * DOC_FEE, POSSIBLE_DOC_FEE or null, read with {@link FeeLabels} like the other fee checks (ticket 142). A clear
   * term takes precedence over a possible alias; a Buyer-confirmed {@code chargeId} of {@code doc-fee} is a doc fee.
   */
  String recognition(JsonNode line) {
    if (DOC_FEE_ID.equals(line.path("chargeId").asText(null))) return "DOC_FEE";
    if (line.hasNonNull("chargeId")) return null;
    var tokens = FeeLabels.tokens(line.path("originalLabel").asText());
    for (String tier : List.of("DOC_FEE", "POSSIBLE_DOC_FEE")) {
      var rules = data.at("/recognition/" + tier);
      if (!FeeLabels.match(tokens, strings(rules.path("terms"))).isEmpty()
          && FeeLabels.match(tokens, strings(rules.path("unless"))).isEmpty()) return tier;
    }
    return null;
  }

  private static List<String> strings(JsonNode array) {
    List<String> values = new ArrayList<>();
    array.forEach(value -> values.add(value.asText()));
    return values;
  }

  private static Long sum(Long first, Long second) {
    if (first == null || second == null) return null;
    try { return Math.addExact(first, second); }
    catch (ArithmeticException tooLarge) { return null; }
  }

  private static String money(Long amount) {
    return amount == null ? "unavailable for a USD comparison" : "$" + BigDecimal.valueOf(amount, 2).toPlainString();
  }

}
