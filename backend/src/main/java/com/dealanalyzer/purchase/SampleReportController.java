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
  private static final Set<String> SENSITIVE = Set.of("vin", "dealer", "dealerIdentity", "dealerName", "dealerId",
      "listingUrl", "buyerZip", "dealerZip", "buyerLocation", "documentId", "documentReference", "documentReferences",
      "page", "pageNumber", "region", "pageRegion", "pageRegions", "regions", "id", "caseId", "reproduction");
  private static final Set<String> TEXT = Set.of("schemaVersion", "reviewStatus", "dealRating", "currency", "precision",
      "calculationVersion", "mode", "appliedAt", "confidence", "generatedAt", "dataAsOf", "providerSnapshotVersion");
  private static final Set<String> NUMBERS = Set.of("version", "minorUnits", "resultMinorUnits", "currencyScale");
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
        "redaction", Map.of("method", "STRUCTURAL_ALLOWLIST", "version", "sample-redaction.v1", "removed", REMOVED),
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
        if (SENSITIVE.contains(field)) {
          if (value.isArray()) object.putArray(field);
          else if (value.isObject()) clear(value);
          else object.putNull(field);
        } else if (value.isTextual() && !TEXT.contains(field)) object.putNull(field);
        else if (value.isNumber() && !NUMBERS.contains(field)) object.putNull(field);
        else clean(value);
      }
    } else if (node.isArray()) {
      var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        if (array.get(i).isValueNode()) array.set(i, com.fasterxml.jackson.databind.node.NullNode.instance);
        else clean(array.get(i));
      }
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
