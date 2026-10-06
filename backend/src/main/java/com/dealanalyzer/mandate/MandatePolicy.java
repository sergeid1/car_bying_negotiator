package com.dealanalyzer.mandate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic checks on a Buyer's mandate terms. The Deal Plan supplies the proposed target and the
 * walk-away ceiling; the Buyer may only lower either (AGT-02). Disclosures come from a fixed list. A mandate
 * bounds what the Buyer may approve; it never lets the platform send anything (AGT-06, ticket 84), so it has no
 * autonomy mode.
 */
public final class MandatePolicy {
  public static final String VERSION = "negotiation-mandate.v2";
  public static final Duration DEFAULT_LIFETIME = Duration.ofDays(14);
  public static final Duration MINIMUM_LIFETIME = Duration.ofHours(1);
  public static final Duration MAXIMUM_LIFETIME = Duration.ofDays(30);

  /** Everything the platform may ever tell a dealer about the Buyer; anything else stays private. */
  public enum Disclosure { PAYING_CASH, HAS_OUTSIDE_FINANCING, FIRST_NAME, PURCHASE_TIMELINE }

  public record DealPlanBasis(String planSnapshotId, int planVersion, String currency,
      long targetMinor, long ceilingMinor) {
    /** The proposed target never sits above the ceiling, even when a Buyer limit is below the midpoint. */
    public DealPlanBasis {
      if (ceilingMinor <= 0) throw new IllegalArgumentException("The Deal Plan has no usable walk-away ceiling");
      targetMinor = Math.min(targetMinor, ceilingMinor);
    }
  }

  public record Request(Integer planVersion, Long targetMinor, Long ceilingMinor, List<String> disclosures,
      Instant expiresAt, boolean approved) {}

  public record Terms(String currency, long targetMinor, long ceilingMinor, long planTargetMinor,
      long planCeilingMinor, List<Disclosure> disclosures, Instant expiresAt) {}

  public Terms decide(DealPlanBasis plan, Request request, Instant now) {
    if (request == null || !request.approved())
      throw new IllegalArgumentException("Explicit Buyer approval is required");
    if (request.planVersion() == null || request.planVersion() != plan.planVersion())
      throw new IllegalArgumentException("The Deal Plan changed; review the new proposal before approving");
    if (request.targetMinor() == null || request.ceilingMinor() == null)
      throw new IllegalArgumentException("A target and a walk-away ceiling are required");
    long ceiling = request.ceilingMinor();
    long target = request.targetMinor();
    if (ceiling <= 0 || target <= 0)
      throw new IllegalArgumentException("Target and walk-away ceiling must be positive amounts");
    if (ceiling > plan.ceilingMinor())
      throw new IllegalArgumentException("The walk-away ceiling cannot be above the Deal Plan's walk-away ceiling");
    if (target > plan.targetMinor())
      throw new IllegalArgumentException("The target cannot be above the Deal Plan's target");
    if (target > ceiling)
      throw new IllegalArgumentException("The target cannot be above the walk-away ceiling");
    Set<Disclosure> disclosures = EnumSet.noneOf(Disclosure.class);
    for (String value : request.disclosures() == null ? List.<String>of() : request.disclosures())
      disclosures.add(disclosure(value));
    Instant expiresAt = request.expiresAt() == null ? now.plus(DEFAULT_LIFETIME) : request.expiresAt();
    if (expiresAt.isBefore(now.plus(MINIMUM_LIFETIME)))
      throw new IllegalArgumentException("The mandate must last at least one hour");
    if (expiresAt.isAfter(now.plus(MAXIMUM_LIFETIME)))
      throw new IllegalArgumentException("The mandate can last at most 30 days");
    return new Terms(plan.currency(), target, ceiling, plan.targetMinor(), plan.ceilingMinor(),
        List.copyOf(disclosures), expiresAt);
  }

  private static Disclosure disclosure(String value) {
    try { return Disclosure.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT)); }
    catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("Not a permitted disclosure: " + value);
    }
  }
}
