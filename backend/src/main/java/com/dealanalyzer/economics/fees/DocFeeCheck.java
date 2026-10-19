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
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Dealer-state disclosure and documentary fee questions; never a dealer filing determination. */
@Component
public class DocFeeCheck {
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
    long total = 0;
    boolean verified = true;
    for (var line : terms.path("lineItems")) {
      String label = normalize(line.path("originalLabel").asText());
      boolean matches = false;
      for (var known : data.path("labels")) if (normalize(known.asText()).equals(label)) matches = true;
      if (!matches || !"DEALER_FEE".equals(line.path("category").asText())) continue;
      // An unreadable matched fee cannot establish the combined amount.
      if (!line.path("amountMinor").isIntegralNumber() || !line.path("amountMinor").canConvertToLong()
          || !"USD".equals(line.path("currency").asText()) || line.path("amountMinor").longValue() < 0) return List.of();
      total = Math.addExact(total, line.path("amountMinor").longValue());
      lines.add(line);
      verified &= line.path("buyerVerified").asBoolean(false);
    }
    if (lines.isEmpty() || total == 0) return List.of();
    String kind = rule.path("kind").asText();
    boolean threshold = kind.equals("SAFE_HARBOR") || kind.equals("CAP");
    if (threshold && total <= rule.path("amountMinor").longValue()) return List.of();
    String labels = lines.stream().map(line -> line.path("originalLabel").asText()).collect(java.util.stream.Collectors.joining(" + "));
    String amount = "$" + BigDecimal.valueOf(total, 2).toPlainString();
    String reason = labels + ": " + amount + " on the paperwork. " + rule.path("summary").asText()
        + " Source: " + rule.path("citation").asText() + ". Checked "
        + rule.path("checkedAt").asText() + "; review by " + rule.path("reviewBy").asText() + ".";
    var evidence = json.createObjectNode();
    evidence.set("lines", json.valueToTree(lines));
    evidence.set("rule", rule.deepCopy());
    evidence.put("policyVersion", data.path("version").asText());
    var observed = json.createObjectNode().put("amountMinor", total).put("currency", "USD");
    return List.of(new Discrepancy("STATE_RULE", "Dealer documentary fee", "DOC_FEE_CHECK", null,
        observed, null, "USD_MINOR", threshold ? "MATERIAL" : "INFORMATIONAL", reason,
        null, evidence, null, !verified, rule.path("question").asText()));
  }

  private static String normalize(String label) {
    return label.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
  }
}
