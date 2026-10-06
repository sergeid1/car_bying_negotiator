package com.dealanalyzer.mandate;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.mandate.MandateEntities.MandateEntity;
import com.dealanalyzer.mandate.MandateEntities.RevocationEntity;
import com.dealanalyzer.mandate.MandatePolicy.DealPlanBasis;
import com.dealanalyzer.mandate.MandatePolicy.Disclosure;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence;
import com.dealanalyzer.plan.PricePlanService;
import com.dealanalyzer.time.PersistedInstant;
import com.dealanalyzer.verification.persistence.AuditEventEntity;
import com.dealanalyzer.verification.persistence.AuditEventRepository;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Buyer-approved Negotiation Mandates. Nothing here contacts a dealer; later tickets read the active
 * mandate through {@link #activeMandate(String)} and must stop when it is revoked, expired, or its Deal
 * Plan has been replaced.
 */
@Service
public class MandateService {
  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final MarketAssessmentPersistence market;
  private final PricePlanService plans;
  private final MandateRepository mandates;
  private final RevocationRepository revocations;
  private final AuditEventRepository auditEvents;
  private final DealerOfferPersistence fingerprints;
  private final EntityManager manager;
  private final ObjectMapper json;
  private final MandatePolicy policy = new MandatePolicy();
  private final org.springframework.context.ApplicationEventPublisher events;

  MandateService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks,
      MarketAssessmentPersistence market, PricePlanService plans, MandateRepository mandates,
      RevocationRepository revocations, AuditEventRepository auditEvents, DealerOfferPersistence fingerprints,
      EntityManager manager, ObjectMapper json, org.springframework.context.ApplicationEventPublisher events) {
    this.events = events;
    this.cases = cases; this.locks = locks; this.market = market; this.plans = plans;
    this.mandates = mandates; this.revocations = revocations; this.auditEvents = auditEvents;
    this.fingerprints = fingerprints; this.manager = manager; this.json = json;
  }

  /** What the Buyer is asked to approve, or the plain reason a mandate is not possible yet. */
  @Transactional(readOnly = true)
  public Proposal proposal(String caseId, String token) {
    cases.reopen(caseId, token);
    Instant now = PersistedInstant.round(Instant.now());
    var disclosures = Arrays.asList(Disclosure.values());
    try {
      DealPlanBasis plan = basis(caseId);
      return new Proposal(true, null, plan.planVersion(), plan.currency(), plan.targetMinor(), plan.ceilingMinor(),
          disclosures, now.plus(MandatePolicy.DEFAULT_LIFETIME),
          now.plus(MandatePolicy.MAXIMUM_LIFETIME), MandatePolicy.VERSION);
    } catch (MandateUnavailableException unavailable) {
      return new Proposal(false, unavailable.getMessage(), null, null, null, null, disclosures,
          null, null, MandatePolicy.VERSION);
    }
  }

  @Transactional
  public Mandate approve(String caseId, String token, String key, MandatePolicy.Request request) {
    locks.lock(caseId);
    cases.reopen(caseId, token);
    String fingerprint = fingerprints.fingerprint(request == null ? Map.of() : request);
    var existing = mandates.findByCaseIdAndIdempotencyKey(caseId, key);
    if (existing.isPresent()) {
      fingerprints.requireMatchingFingerprint(existing.get().requestFingerprint, fingerprint);
      return view(existing.get(), Instant.now());
    }
    Instant now = PersistedInstant.round(Instant.now());
    recordExpiry(caseId, now);
    DealPlanBasis plan = basis(caseId);
    var terms = policy.decide(plan, request, now);
    var previous = mandates.findTopByCaseIdOrderByVersionDesc(caseId);
    int version = previous.map(value -> value.version + 1).orElse(1);
    var saved = mandates.save(new MandateEntity(UUID.randomUUID().toString(), caseId, version,
        plan.planSnapshotId(), plan.planVersion(), terms, key, fingerprint, now));
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("policyVersion", MandatePolicy.VERSION);
    metadata.put("planVersion", plan.planVersion());
    metadata.put("targetMinor", terms.targetMinor());
    metadata.put("ceilingMinor", terms.ceilingMinor());
    metadata.put("disclosures", terms.disclosures());
    metadata.put("expiresAt", terms.expiresAt().toString());
    previous.ifPresent(value -> metadata.put("supersedesVersion", value.version));
    auditEvents.save(new AuditEventEntity(UUID.randomUUID().toString(), caseId, "MANDATE_APPROVED",
        "NEGOTIATION_MANDATE", saved.id, saved.version, key, write(metadata), now));
    return view(saved, now);
  }

  /** Revocation takes effect at once; only the current version can be revoked. */
  @Transactional
  public Mandate revoke(String caseId, String token, String key, int version) {
    locks.lock(caseId);
    cases.reopen(caseId, token);
    var mandate = mandates.findByCaseIdAndVersion(caseId, version)
        .orElseThrow(() -> new ResourceNotFoundException("Negotiation Mandate not found"));
    Instant now = PersistedInstant.round(Instant.now());
    var revoked = revocations.findById(mandate.id);
    if (revoked.isPresent()) {
      if (!revoked.get().idempotencyKey.equals(key))
        throw new MandateConflictException("This mandate is already revoked");
      return view(mandate, now);
    }
    if (mandates.findTopByCaseIdOrderByVersionDesc(caseId).orElseThrow().version != version)
      throw new MandateConflictException("Only the current mandate can be revoked");
    recordExpiry(caseId, now);
    revocations.save(new RevocationEntity(mandate.id, caseId, key, now));
    auditEvents.save(new AuditEventEntity(UUID.randomUUID().toString(), caseId, "MANDATE_REVOKED",
        "NEGOTIATION_MANDATE", mandate.id, mandate.version, key,
        write(Map.of("policyVersion", MandatePolicy.VERSION)), now));
    events.publishEvent(new MandateEnded(caseId));
    return view(mandate, now);
  }

  @Transactional
  public List<Mandate> list(String caseId, String token) {
    locks.lock(caseId);
    cases.reopen(caseId, token);
    Instant now = Instant.now();
    recordExpiry(caseId, now);
    return mandates.findByCaseIdOrderByVersionDesc(caseId).stream().map(value -> view(value, now)).toList();
  }

  @Transactional(readOnly = true)
  public Mandate get(String caseId, String token, int version) {
    cases.reopen(caseId, token);
    return mandates.findByCaseIdAndVersion(caseId, version).map(value -> view(value, Instant.now()))
        .orElseThrow(() -> new ResourceNotFoundException("Negotiation Mandate not found"));
  }

  /** The mandate later tickets act under, if one is approved, current, unrevoked and unexpired now. */
  @Transactional(readOnly = true)
  public Optional<Mandate> activeMandate(String caseId) {
    return activeMandate(caseId, Instant.now());
  }

  @Transactional(readOnly = true)
  public Optional<Mandate> activeMandate(String caseId, Instant at) {
    return mandates.findTopByCaseIdOrderByVersionDesc(caseId).map(value -> view(value, at))
        .filter(value -> value.status() == Status.ACTIVE);
  }

  /** Active mandates that expire after now and at or before the cutoff, soonest first. */
  @Transactional(readOnly = true)
  public List<Mandate> expiringBefore(Instant cutoff) {
    return expiringBetween(Instant.now(), cutoff);
  }

  @Transactional(readOnly = true)
  public List<Mandate> expiringBetween(Instant after, Instant cutoff) {
    return mandates.currentExpiringBetween(after, cutoff).stream().map(value -> view(value, after))
        .filter(value -> value.status() == Status.ACTIVE).toList();
  }

  /** Audits each current mandate that has expired by now; expiry itself already applies at read time. */
  @Transactional
  public int recordExpirations(Instant now) {
    List<String> expired = manager.createNativeQuery("""
        select distinct m.case_id from negotiation_mandates m
        where m.expires_at <= :now
          and m.version = (select max(o.version) from negotiation_mandates o where o.case_id = m.case_id)
          and not exists (select 1 from negotiation_mandate_revocations r where r.mandate_id = m.id)
          and not exists (select 1 from audit_events a where a.case_id = m.case_id
            and a.event_type = 'MANDATE_EXPIRED' and a.aggregate_id = m.id)
        """).setParameter("now", now).getResultList().stream().map(Object::toString).toList();
    int recorded = 0;
    for (String caseId : expired) {
      locks.lock(caseId);
      if (recordExpiry(caseId, now)) recorded++;
    }
    return recorded;
  }

  private boolean recordExpiry(String caseId, Instant now) {
    var current = mandates.findTopByCaseIdOrderByVersionDesc(caseId);
    if (current.isEmpty() || current.get().expiresAt.isAfter(now)
        || revocations.findById(current.get().id).isPresent()) return false;
    var mandate = current.get();
    long audited = ((Number) manager.createNativeQuery("""
        select count(*) from audit_events where case_id = :caseId
          and event_type = 'MANDATE_EXPIRED' and aggregate_id = :id
        """).setParameter("caseId", caseId).setParameter("id", mandate.id).getSingleResult()).longValue();
    if (audited > 0) return false;
    manager.createNativeQuery("""
        insert into audit_events (id, case_id, event_type, aggregate_type, aggregate_id, aggregate_version,
          idempotency_key, actor, metadata_json, created_at)
        values (:eventId, :caseId, 'MANDATE_EXPIRED', 'NEGOTIATION_MANDATE', :id, :version,
          :key, 'SYSTEM', :metadata, :now)
        """).setParameter("eventId", UUID.randomUUID().toString()).setParameter("caseId", caseId)
        .setParameter("id", mandate.id).setParameter("version", mandate.version)
        .setParameter("key", "expiry-" + mandate.id)
        .setParameter("metadata", write(Map.of("policyVersion", MandatePolicy.VERSION,
            "expiresAt", mandate.expiresAt.toString())))
        .setParameter("now", PersistedInstant.round(now)).executeUpdate();
    events.publishEvent(new MandateEnded(caseId));
    return true;
  }

  private DealPlanBasis basis(String caseId) {
    var assessment = market.latest(caseId).orElseThrow(() -> new MandateUnavailableException(
        "A Negotiation Mandate needs a market check for this car first. Run the market assessment, then review price boundaries."));
    if (!"AVAILABLE".equals(assessment.status()) || assessment.range() == null)
      throw new MandateUnavailableException("A Negotiation Mandate needs an available Market Assessment, and the latest one is unavailable"
          + (assessment.limitation() == null || assessment.limitation().isBlank() ? "." : ": " + assessment.limitation()));
    var snapshot = plans.latest(caseId, "PLAN").orElseThrow(() -> new MandateUnavailableException(
        "Review price boundaries first. The mandate's target and walk-away ceiling come from the Deal Plan."));
    if (!assessment.id().equals(snapshot.payload().at("/marketAssessment/id").asText()))
      throw new MandateUnavailableException(
          "The market check changed after the Deal Plan was made. Review price boundaries again first.");
    var selling = snapshot.payload().at("/plan/sellingPrice");
    return new DealPlanBasis(snapshot.id(), snapshot.version(), snapshot.payload().at("/plan/currency").asText(),
        selling.path("targetMinor").asLong(), selling.path("walkAwayMinor").asLong());
  }

  private Mandate view(MandateEntity entity, Instant now) {
    var revocation = revocations.findById(entity.id);
    int latest = mandates.findTopByCaseIdOrderByVersionDesc(entity.caseId).map(value -> value.version).orElse(entity.version);
    boolean planCurrent = plans.latest(entity.caseId, "PLAN").map(plan -> plan.id().equals(entity.planSnapshotId)).orElse(false);
    Status status = revocation.isPresent() ? Status.REVOKED
        : entity.version < latest ? Status.SUPERSEDED
        : !planCurrent ? Status.PLAN_CHANGED
        : !entity.expiresAt.isAfter(now) ? Status.EXPIRED : Status.ACTIVE;
    List<Disclosure> disclosures = entity.disclosures.isEmpty() ? List.of()
        : Arrays.stream(entity.disclosures.split(",")).map(Disclosure::valueOf).collect(Collectors.toList());
    return new Mandate(entity.id, entity.caseId, entity.version, status, entity.planVersion, entity.currency,
        entity.targetMinor, entity.ceilingMinor, entity.planTargetMinor, entity.planCeilingMinor,
        List.copyOf(disclosures), entity.expiresAt,
        entity.approvedBy, entity.approvedAt,
        revocation.map(value -> value.revokedBy).orElse(null), revocation.map(value -> value.revokedAt).orElse(null));
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }

  /**
   * Published synchronously inside the revoking or expiry-recording transaction, under the case lock,
   * so negotiation state and queued drafts change in the same commit that ends the mandate.
   */
  public record MandateEnded(String caseId) {}

  /** PLAN_CHANGED: a newer Deal Plan replaced the one this mandate was approved against, so it no longer applies. */
  public enum Status { ACTIVE, SUPERSEDED, REVOKED, PLAN_CHANGED, EXPIRED }

  public record Proposal(boolean available, String unavailableReason, Integer planVersion, String currency,
      Long targetMinor, Long ceilingMinor, List<Disclosure> disclosures,
      Instant defaultExpiresAt, Instant latestExpiresAt, String policyVersion) {}

  public record Mandate(String id, String caseId, int version, Status status, int planVersion, String currency,
      long targetMinor, long ceilingMinor, long planTargetMinor, long planCeilingMinor,
      List<Disclosure> disclosures, Instant expiresAt,
      String approvedBy, Instant approvedAt, String revokedBy, Instant revokedAt) {}

  @ResponseStatus(HttpStatus.CONFLICT)
  public static final class MandateUnavailableException extends RuntimeException {
    MandateUnavailableException(String message) { super(message); }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static final class MandateConflictException extends RuntimeException {
    MandateConflictException(String message) { super(message); }
  }
}
