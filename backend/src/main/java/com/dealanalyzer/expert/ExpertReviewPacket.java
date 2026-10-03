package com.dealanalyzer.expert;

import com.dealanalyzer.economics.CashDealEconomics.*;
import com.dealanalyzer.economics.CashDealEconomicsCalculator;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.*;
import com.dealanalyzer.plan.PriceBoundaryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Offline evidence tooling: no application context, persistence, or provider clients. */
public final class ExpertReviewPacket {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> FIXED = List.of("packet_hash", "scenario_set_version", "policy_version",
      "scenario_id", "provenance", "permission_reference", "description", "inputs_json", "currency",
      "market_low_minor", "market_high_minor", "opening_minor", "target_minor", "policy_maximum_minor",
      "walk_away_minor", "otd_opening_minor", "otd_target_minor", "otd_policy_maximum_minor",
      "otd_walk_away_minor", "otd_unavailable_reason", "warnings", "method", "scenario_set_hash");
  private static final List<String> REVIEW = List.of("verdict", "suggested_opening_minor",
      "suggested_target_minor", "suggested_walk_away_minor", "rationale", "reviewer_name",
      "qualification_statement", "review_date");
  public record ScenarioSet(String version, Policy economicsPolicy, List<Scenario> scenarios) {}
  public record Scenario(String id, String provenance, String permissionReference, String description,
      long marketLowMinor, long marketHighMinor, Long buyerSellingLimitMinor, Long buyerOtdLimitMinor,
      Offer offer) {}
  public record Packet(String hash, List<List<String>> rows, byte[] source) {}
  public record Inputs(Scenario scenario, Policy economicsPolicy) {}

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && args[0].equals("generate")) {
      generate(Path.of(args[1]), Path.of(args[2]));
    } else if (args.length == 4 && args[0].equals("import")) {
      importReview(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    } else {
      throw new IllegalArgumentException("Usage: generate <scenario-json> <new-output-directory> | import <scenario-json> <completed-csv> <new-output-directory>");
    }
  }

  public static Packet compute(Path scenarios) throws Exception {
    byte[] source = Files.readAllBytes(scenarios);
    String scenarioHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    ScenarioSet set = JSON.readValue(source, ScenarioSet.class);
    if (set.version() == null || set.version().isBlank() || set.scenarios() == null || set.scenarios().size() < 12)
      throw new IllegalArgumentException("A versioned set of at least twelve scenarios is required");
    List<List<String>> rows = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Scenario scenario : set.scenarios()) {
      if (scenario.id() == null || scenario.id().isBlank() || !ids.add(scenario.id()))
        throw new IllegalArgumentException("Scenario IDs must be present and unique");
      if (!Set.of("SYNTHETIC", "PERMISSIONED_REAL").contains(Objects.toString(scenario.provenance(), ""))
          || (scenario.provenance().equals("PERMISSIONED_REAL")
              && (scenario.permissionReference() == null || scenario.permissionReference().isBlank())))
        throw new IllegalArgumentException("Scenario " + scenario.id() + " requires provenance and real-deal permission reference");
      if (scenario.marketLowMinor() <= 0 || scenario.marketHighMinor() < scenario.marketLowMinor())
        throw new IllegalArgumentException("Invalid market range for " + scenario.id());
      var deal = new CashDealEconomicsCalculator().calculate(scenario.offer(), set.economicsPolicy());
      var market = new MarketAssessmentView(scenario.id(), scenario.id(), "expert-review.v1", 1,
          "AVAILABLE", new RangeView(scenario.marketLowMinor(), scenario.marketHighMinor(),
          scenario.offer().currency().getCurrencyCode()), "Recorded scenario", null, 0,
          "retrospective-input", "UNASSESSED", "Expert review input", null, List.of(), List.of(), Instant.EPOCH);
      var plan = new PriceBoundaryPolicy().calculate(market, deal,
          scenario.buyerSellingLimitMinor(), scenario.buyerOtdLimitMinor());
      var selling = plan.sellingPrice();
      var otd = plan.outTheDoor();
      List<String> row = new ArrayList<>(List.of("", set.version(), plan.policyVersion(), scenario.id(),
          scenario.provenance(), value(scenario.permissionReference()), value(scenario.description()),
          inputsJson(scenario, set.economicsPolicy()),
          plan.currency(), value(scenario.marketLowMinor()), value(scenario.marketHighMinor()),
          value(selling.openingMinor()), value(selling.targetMinor()), value(selling.policyMaximumMinor()),
          value(selling.walkAwayMinor()), otd == null ? "" : value(otd.openingMinor()),
          otd == null ? "" : value(otd.targetMinor()), otd == null ? "" : value(otd.policyMaximumMinor()),
          otd == null ? "" : value(otd.walkAwayMinor()),
          otd == null ? String.join("; ", plan.warnings()) : "", String.join("; ", plan.warnings()), plan.method(), scenarioHash));
      rows.add(row);
    }
    // Bind exact scenario bytes AND computed output, including both engines' recorded policy inputs.
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(source);
    digest.update(csv(rows).getBytes(StandardCharsets.UTF_8));
    String hash = HexFormat.of().formatHex(digest.digest());
    rows.forEach(row -> row.set(0, hash));
    return new Packet(hash, rows, source);
  }

  public static void generate(Path scenarios, Path output) throws Exception {
    Packet packet = compute(scenarios);
    List<List<String>> workbook = new ArrayList<>();
    workbook.add(header());
    for (List<String> fixed : packet.rows()) {
      List<String> row = new ArrayList<>(fixed);
      REVIEW.forEach(ignored -> row.add(""));
      workbook.add(row);
    }
    StringBuilder markdown = new StringBuilder("# Price boundary expert review\n\nSynthetic scenarios are not representative of any jurisdiction. Amounts are integer currency minor units.\n\nPacket SHA-256: `" + packet.hash() + "`\n\n");
    for (List<String> row : packet.rows()) {
      markdown.append("## ").append(escape(row.get(3))).append("\n\n");
      for (int i = 1; i < FIXED.size(); i++)
        markdown.append("- **").append(FIXED.get(i)).append("**: ").append(escape(row.get(i))).append("\n");
      markdown.append("\nVerdict, suggested opening/target/walk-away, rationale, reviewer name, qualifications, and date: complete in workbook.csv.\n\n");
    }
    Path dir = newDirectory(output);
    write(dir.resolve("workbook.csv"), csv(workbook));
    write(dir.resolve("packet.md"), markdown.toString());
    Files.write(dir.resolve("scenarios.json"), packet.source(), StandardOpenOption.CREATE_NEW);
  }

  public static void importReview(Path scenarios, Path workbook, Path output) throws Exception {
    Packet packet = compute(scenarios);
    String completedWorkbook = Files.readString(workbook);
    List<List<String>> rows = parseCsv(completedWorkbook);
    List<String> errors = new ArrayList<>();
    if (rows.isEmpty() || !rows.getFirst().equals(header()))
      throw new IllegalArgumentException("Workbook header does not match the packet schema");
    Map<String, List<String>> expected = new LinkedHashMap<>();
    packet.rows().forEach(row -> expected.put(row.get(3), row));
    Set<String> seen = new HashSet<>();
    Map<String, Integer> counts = new LinkedHashMap<>(Map.of("SOUND", 0, "ADJUST", 0, "UNSOUND", 0));
    List<Map<String, String>> answers = new ArrayList<>();
    List<String> identity = null;
    for (int r = 1; r < rows.size(); r++) {
      List<String> row = rows.get(r);
      String label = "Row " + (r + 1);
      if (row.size() != header().size()) { errors.add(label + ": wrong column count"); continue; }
      String id = row.get(3);
      label += " (" + id + ")";
      if (!seen.add(id)) errors.add(label + ": duplicate scenario");
      if (!expected.containsKey(id)) errors.add(label + ": unknown scenario");
      else for (int c = 0; c < FIXED.size(); c++)
        if (!row.get(c).equals(expected.get(id).get(c))) errors.add(label + ": mismatched " + FIXED.get(c));
      int start = FIXED.size();
      String verdict = row.get(start);
      if (!counts.containsKey(verdict)) errors.add(label + ": verdict must be SOUND, ADJUST, or UNSOUND");
      else counts.merge(verdict, 1, Integer::sum);
      for (int c = start + 1; c <= start + 3; c++) {
        String amount = row.get(c);
        if (!amount.isEmpty()) {
          try {
            if (!amount.matches("[0-9]+") || Long.parseLong(amount) <= 0) throw new NumberFormatException();
          } catch (NumberFormatException invalid) { errors.add(label + ": invalid " + header().get(c)); }
        }
      }
      for (int c = start + 4; c < header().size(); c++)
        if (row.get(c).isBlank()) errors.add(label + ": missing " + header().get(c));
      try { LocalDate.parse(row.get(start + 7)); }
      catch (Exception invalid) { errors.add(label + ": review_date must be an ISO calendar date"); }
      List<String> currentIdentity = List.copyOf(row.subList(start + 5, start + 8));
      if (identity == null) identity = currentIdentity;
      else if (!identity.equals(currentIdentity)) errors.add(label + ": inconsistent reviewer metadata");
      Map<String, String> answer = new LinkedHashMap<>();
      answer.put("scenario_id", id);
      for (int c = start; c < header().size(); c++) answer.put(header().get(c), row.get(c));
      answers.add(answer);
    }
    for (String id : expected.keySet()) if (!seen.contains(id)) errors.add("Scenario " + id + ": missing row");
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
    String record = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
        "schemaVersion", "expert-review-record.v1", "packetHash", packet.hash(),
        "scenarioSetHash", packet.rows().getFirst().get(22),
        "scenarioSetVersion", packet.rows().getFirst().get(1),
        "policyVersion", PriceBoundaryPolicy.VERSION, "counts", counts, "reviews", answers,
        "readinessDecision", "UNCHANGED"));
    Path dir = newDirectory(output);
    write(dir.resolve("review-record.json"), record + "\n");
    write(dir.resolve("completed-workbook.csv"), completedWorkbook);
  }

  private static Path newDirectory(Path output) throws Exception {
    Path absolute = output.toAbsolutePath().normalize();
    Path resolved = absolute.getParent().toRealPath().resolve(absolute.getFileName());
    for (int i = 0; i < resolved.getNameCount(); i++) {
      String part = resolved.getName(i).toString();
      if (part.equals("frontend") || (part.equals("backend") && i + 1 < resolved.getNameCount()
          && resolved.getName(i + 1).toString().equals("src")))
        throw new IllegalArgumentException("Output must be outside backend/src and frontend");
    }
    return Files.createDirectory(resolved);
  }

  private static void write(Path path, String value) throws Exception {
    Files.writeString(path, value, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }
  private static String inputsJson(Scenario scenario, Policy policy) throws Exception {
    var inputs = JSON.valueToTree(new Inputs(scenario, policy));
    var policyNode = (com.fasterxml.jackson.databind.node.ObjectNode) inputs.path("economicsPolicy");
    var types = policyNode.putArray("taxableLineTypes");
    policy.taxableLineTypes().stream().map(Enum::name).sorted().forEach(types::add);
    return JSON.writeValueAsString(inputs);
  }
  private static String value(Object value) { return value == null ? "" : value.toString(); }
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("`", "&#96;").replace("*", "\\*").replace("\n", " ");
  }
  static List<String> header() {
    List<String> result = new ArrayList<>(FIXED); result.addAll(REVIEW); return result;
  }
  static String csv(List<List<String>> rows) {
    StringBuilder text = new StringBuilder();
    for (List<String> row : rows) {
      StringJoiner fields = new StringJoiner(",");
      row.forEach(cell -> fields.add("\"" + cell.replace("\"", "\"\"") + "\""));
      text.append(fields).append("\r\n");
    }
    return text.toString();
  }
  static List<List<String>> parseCsv(String text) {
    List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder(); boolean quoted = false, closed = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
          else { quoted = false; closed = true; }
        } else cell.append(c);
      } else if (c == ',' || c == '\n' || c == '\r') {
        row.add(cell.toString()); cell.setLength(0); closed = false;
        if (c != ',') {
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          rows.add(row); row = new ArrayList<>();
        }
      } else if (c == '"' && cell.isEmpty() && !closed) quoted = true;
      else {
        if (closed || c == '"') throw new IllegalArgumentException("Malformed CSV at row " + (rows.size() + 1));
        cell.append(c);
      }
    }
    if (quoted) throw new IllegalArgumentException("Unclosed CSV quote at row " + (rows.size() + 1));
    if (!row.isEmpty() || !cell.isEmpty() || closed) { row.add(cell.toString()); rows.add(row); }
    return rows;
  }
}
