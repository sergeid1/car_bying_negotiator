package com.dealanalyzer.purchase;

import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import com.dealanalyzer.report.CashDealAnalyzer;
import com.dealanalyzer.report.ReportAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    report = redact(json.readTree(assembler.assemble("synthetic-example", 1, "synthetic-golden.v1",
        analyzer.analyzeSyntheticSample(input)).payloadJson()));
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
