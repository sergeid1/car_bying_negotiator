package com.dealanalyzer.purchase;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.dealanalyzer.report.CashDealAnalyzer;
import com.dealanalyzer.report.ReportAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sample-report")
public class SampleReportController {
  private final JsonNode report;
  static final List<String> REMOVED = List.of("VIN", "DEALER_IDENTITY", "LISTING_URL", "BUYER_LOCATION",
      "DOCUMENT_REFERENCES", "PAGE_REGIONS", "FREE_TEXT");
  private static final Set<String> SENSITIVE = Set.of("vin", "dealer", "dealeridentity", "dealername", "dealerid",
      "listingurl", "buyerzip", "dealerzip", "buyerlocation", "documentid", "documentreference", "documentreferences",
      "page", "pagenumber", "region", "pageregion", "pageregions", "regions", "regionx", "regiony",
      "regionwidth", "regionheight", "id", "caseid", "reproduction");
  /** Source-authored text, unlike code-generated reasons, labels, instructions and limitations. */
  private static final Set<String> SOURCE_TEXT = Set.of("originalwording", "originallabel", "notes", "buyernotes",
      "dealernotes", "rawtext", "rawdocumenttext", "documenttext", "extractedtext", "listingtitle",
      "buyertext", "dealertext", "dealerresponse", "buyercomments", "dealercomments");
  public SampleReportController(ObjectMapper json, CashDealAnalyzer analyzer, ReportAssembler assembler) throws java.io.IOException {
    SyntheticAnalysisRequest input;
    try (var fixture = getClass().getResourceAsStream("/purchase/sample-golden.v1.json")) {
      input = json.readValue(fixture, SyntheticAnalysisRequest.class);
    }
    report = withReportView(redact(json.readTree(assembler.assemble("synthetic-example", 1, "synthetic-golden.v1",
        analyzer.analyzeSyntheticSample(input)).payloadJson())));
  }
  private static final List<Map.Entry<String, String>> AMOUNT_LABELS = List.of(
      Map.entry("sellingPrice", "Selling price"), Map.entry("dealerFees", "Dealer fees"), Map.entry("addOns", "Add-ons"),
      Map.entry("taxes", "Taxes"), Map.entry("governmentCharges", "Government charges"),
      Map.entry("outTheDoorTotal", "Out-the-door total"));
  /**
   * The example page draws the same view as a Buyer's own report, so the sample also carries that view's fields,
   * built only from the example's own figures. The assembled fields stay as they are for the walkthrough.
   */
  static JsonNode withReportView(JsonNode input) {
    ObjectNode report = (ObjectNode) input.deepCopy();
    ObjectNode analysis = (ObjectNode) report.get("analysis");
    JsonNode economics = analysis.path("economics");
    JsonNode market = analysis.path("marketAssessment");
    report.put("status", "COMPLETE");
    report.put("asOf", report.at("/coverage/asOf").asText(report.path("generatedAt").asText()));
    if (!report.has("evidence")) report.putObject("evidence");
    String status = analysis.path("reviewStatus").asText();
    analysis.put("reviewLabel", "MATCHES_APPROVED_BASELINE".equals(status) ? "Matches approved deal"
        : "UNABLE_TO_VERIFY".equals(status) ? "Unable to verify" : "Review required");
    analysis.put("ratingBasis", "Estimated selling-price comparison; OTD market rating is not available.");
    analysis.put("marketAvailability", market.path("low").has("minorUnits") ? "AVAILABLE" : "UNAVAILABLE");
    analysis.put("cashAvailability", economics.path("outTheDoorTotal").has("minorUnits") ? "AVAILABLE" : "UNAVAILABLE");
    ArrayNode amounts = analysis.putArray("materialAmounts");
    AMOUNT_LABELS.forEach(entry -> addAmount(amounts, entry.getValue(), economics.path(entry.getKey()),
        "cashEconomics", "/analysis/economics/" + entry.getKey()));
    addAmount(amounts, "Market selling-price low", market.path("low"), "marketAssessment", "/analysis/marketAssessment/low");
    addAmount(amounts, "Market selling-price high", market.path("high"), "marketAssessment", "/analysis/marketAssessment/high");
    return report;
  }
  private static void addAmount(ArrayNode amounts, String label, JsonNode amount, String source, String pointer) {
    if (!amount.has("minorUnits")) return;
    ObjectNode row = amounts.addObject();
    row.put("label", label);
    row.put("minorUnits", amount.get("minorUnits").asLong());
    row.put("currency", amount.path("currency").asText("USD"));
    row.put("precision", amount.path("precision").asText("ESTIMATED"));
    row.put("source", source);
    row.put("pointer", pointer);
    row.putNull("documentSource");
    row.put("sourceNote", amount.path("source").asText(null));
  }
  @GetMapping
  public Map<String, Object> sample() {
    return Map.of("label", "EXAMPLE", "source", "SYNTHETIC_CASE",
        "redaction", Map.of("method", "FIELD_CATEGORIES", "version", "sample-redaction.v2", "removed", REMOVED),
        "report", report.deepCopy());
  }
  static JsonNode redact(JsonNode input) {
    JsonNode result = input.deepCopy();
    clean(result); return result;
  }
  private static void clean(JsonNode node) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      var fields = new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(fields::add);
      for (String field : fields) {
        JsonNode value = object.get(field);
        String normalized = field.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        if (SENSITIVE.contains(normalized) || SOURCE_TEXT.contains(normalized)) {
          if (value.isArray()) object.putArray(field);
          else if (value.isObject()) clear(value);
          else object.putNull(field);
        } else clean(value);
      }
    } else if (node.isArray()) {
      node.forEach(SampleReportController::clean);
    }
  }
  /** Preserve object topology while nulling every scalar in a sensitive subtree. */
  private static void clear(JsonNode node) {
    var object = (ObjectNode) node;
    var fields = new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(fields::add);
    for (String field : fields) {
      var value = object.get(field);
      if (value.isArray()) object.putArray(field);
      else if (value.isObject()) clear(value);
      else object.putNull(field);
    }
  }
}
