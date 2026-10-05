package com.dealanalyzer.pilot;

import com.dealanalyzer.deployment.DeploymentStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** The public-pilot prerequisite record. Any unmet prerequisite fails pilot startup closed. */
@Component
public class PilotReadiness {
  static final String RESOURCE = "/pilot/pilot-readiness.json";

  /** Stable identifiers for the ticket 21 prerequisites, in their documented order. */
  public static final List<String> REQUIRED_IDS = List.of(
      "jurisdiction-pack", "provider-terms", "document-corpus", "expert-policy-validation",
      "frozen-parameters", "infrastructure-and-privacy-inventory", "legal-and-product-copy",
      "preregistered-thresholds", "schema-and-orm-evidence", "payment-provider");

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Prerequisite(String id, String requirement, String status, String blocker,
      String approvedBy, Instant approvedAt, List<String> checks, List<String> evidence,
      String notes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Record(String schemaVersion, List<Prerequisite> prerequisites) {}

  private final List<Prerequisite> prerequisites;

  @org.springframework.beans.factory.annotation.Autowired
  PilotReadiness(Environment environment, ObjectMapper json) {
    this(shipped(json).prerequisites(), DeploymentStage.from(environment).productionLike());
  }

  PilotReadiness(List<Prerequisite> prerequisites, boolean pilotProfile) {
    validate(prerequisites);
    this.prerequisites = List.copyOf(prerequisites);
    var blocked = blockers().stream().map(Prerequisite::id).toList();
    if (pilotProfile && !blocked.isEmpty()) {
      throw new IllegalStateException(
          "The public pilot is blocked by unmet readiness prerequisites: " + String.join(", ", blocked));
    }
  }

  /** Reads the record shipped on the classpath without applying the pilot gate. */
  public static PilotReadiness shipped(ObjectMapper json) {
    try (InputStream source = PilotReadiness.class.getResourceAsStream(RESOURCE)) {
      if (source == null) throw new IllegalStateException("Missing " + RESOURCE);
      return new PilotReadiness(json.readValue(source, Record.class).prerequisites(), false);
    } catch (IOException cause) {
      throw new IllegalStateException("Unreadable " + RESOURCE, cause);
    }
  }

  public List<Prerequisite> prerequisites() {
    return prerequisites;
  }

  public List<Prerequisite> blockers() {
    return prerequisites.stream().filter(value -> !"APPROVED".equals(value.status())).toList();
  }

  private static void validate(List<Prerequisite> prerequisites) {
    Set<String> seen = new LinkedHashSet<>();
    for (Prerequisite value : prerequisites) {
      if (!seen.add(value.id())) throw new IllegalArgumentException("Duplicate prerequisite " + value.id());
      if (blank(value.requirement())) throw new IllegalArgumentException("Unstated requirement " + value.id());
      switch (value.status() == null ? "" : value.status()) {
        case "APPROVED" -> {
          boolean automated = value.checks() != null && !value.checks().isEmpty()
              && value.checks().stream().noneMatch(PilotReadiness::blank);
          boolean signedOff = !blank(value.approvedBy()) && value.approvedAt() != null;
          if (!automated && !signedOff) {
            throw new IllegalArgumentException("Approved prerequisite " + value.id()
                + " needs an automated check or a named sign-off with a date");
          }
        }
        case "BLOCKED" -> {
          if (blank(value.blocker())) {
            throw new IllegalArgumentException("Blocked prerequisite " + value.id() + " must name its blocker");
          }
        }
        default -> throw new IllegalArgumentException(
            "Prerequisite " + value.id() + " must be APPROVED or BLOCKED");
      }
    }
    if (!seen.equals(Set.copyOf(REQUIRED_IDS)) || prerequisites.size() != REQUIRED_IDS.size()) {
      throw new IllegalArgumentException("The record must cover exactly " + REQUIRED_IDS);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
