package com.dealanalyzer.plan;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.buyingcase.persistence.EphemeralCasePersistence;
import com.dealanalyzer.economics.CashDealEconomics.Result;
import com.dealanalyzer.economics.CashEconomicsService;
import com.dealanalyzer.market.MarketAssessmentService;
import com.dealanalyzer.verification.persistence.DealerOfferPersistence;
import com.dealanalyzer.verification.persistence.AuditEventEntity;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dealanalyzer.verification.persistence.AuditEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricePlanService {
  private final EphemeralBuyingCaseService cases;
  private final EphemeralCasePersistence locks;
  private final CashEconomicsService economics;
  private final MarketAssessmentService market;
  private final DealerOfferPersistence offers;
  private final VehicleEvidenceService vehicles;
  private final PlanSnapshotRepository snapshots;
  private final AuditEventRepository auditEvents;
  private final ObjectMapper json;

  public PricePlanService(EphemeralBuyingCaseService cases, EphemeralCasePersistence locks,
      CashEconomicsService economics, MarketAssessmentService market, DealerOfferPersistence offers,
      VehicleEvidenceService vehicles, PlanSnapshotRepository snapshots, AuditEventRepository auditEvents, ObjectMapper json) {
    this.cases = cases; this.locks = locks; this.economics = economics; this.market = market;
    this.offers = offers; this.vehicles = vehicles; this.snapshots = snapshots;
    this.auditEvents = auditEvents; this.json = json;
  }

  @Transactional
  public Snapshot propose(String caseId, String token, String key, Limits request) {
    locks.lock(caseId);
    var buyingCase = cases.reopen(caseId, token);
    Limits limits = request == null ? new Limits(null, null) : request;
    String fingerprint = offers.fingerprint(limits);
    var existing = existing(caseId, "PLAN", key, fingerprint);
    if (existing.isPresent()) return existing.get();
    requireReady(caseId, token);
    var cash = economics.latest(caseId, token);
    var offer = offers.latestOffer(caseId).orElseThrow();
    if (!cash.dealerOfferId().equals(offer.id()))
      throw new IllegalArgumentException("Recalculate economics for the current Dealer Offer");
    var assessment = market.latest(caseId, token);
    if (assessment.createdAt().isBefore(vehicles.get(caseId, token).vehicle().confirmedAt()))
      throw new IllegalArgumentException("Refresh market evidence for the confirmed Vehicle");
    Long selling = limits.maxSellingPriceMinor();
    Long otd = limits.maxOutTheDoorPriceMinor();
    if (buyingCase.buyerLimits() != null) {
      if (selling == null) selling = buyingCase.buyerLimits().maxSellingPriceMinor();
      if (otd == null) otd = buyingCase.buyerLimits().maxOutTheDoorPriceMinor();
    }
    var plan = new PriceBoundaryPolicy().calculate(assessment,
        json.convertValue(cash.result(), Result.class), selling, otd);
    return append(caseId, "PLAN", key, fingerprint, json.valueToTree(Map.of(
        "plan", plan, "dealerOffer", offer, "cashEconomics", cash, "marketAssessment", assessment,
        "vehicleEvidence", vehicles.get(caseId, token))));
  }

  @Transactional
  public Snapshot approve(String caseId, String token, String key, Approval request) {
    locks.lock(caseId);
    cases.reopen(caseId, token);
    if (request == null || !request.approved())
      throw new IllegalArgumentException("Explicit Buyer approval is required");
    String fingerprint = offers.fingerprint(request);
    var existing = existing(caseId, "BASELINE", key, fingerprint);
    if (existing.isPresent()) return existing.get();
    requireReady(caseId, token);
    Snapshot proposal = version(caseId, "PLAN", request.planVersion());
    if (!proposal.payload().at("/dealerOffer/id").asText()
        .equals(offers.latestOffer(caseId).orElseThrow().id())
        || !json.convertValue(proposal.payload().get("vehicleEvidence"), VehicleEvidenceService.VehicleEvidenceView.class)
            .equals(vehicles.get(caseId, token)))
      throw new IllegalArgumentException("Deal evidence changed; review a new price plan before approving");
    var payload = proposal.payload().deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("approvedPlanId", proposal.id());
    Snapshot baseline = append(caseId, "BASELINE", key, fingerprint, payload);
    auditEvents.save(new AuditEventEntity(UUID.randomUUID().toString(), caseId,
        "BASELINE_APPROVED", "APPROVED_BASELINE", baseline.id(), baseline.version(), key,
        write(Map.of("planId", proposal.id(), "policyVersion", PriceBoundaryPolicy.VERSION)), baseline.createdAt()));
    return baseline;
  }

  public Snapshot get(String caseId, String token, String kind, Integer version) {
    cases.reopen(caseId, token);
    return version == null ? latest(caseId, kind).orElseThrow(() -> new ResourceNotFoundException("Price plan not found"))
        : version(caseId, kind, version);
  }

  public Optional<Snapshot> latest(String caseId, String kind) {
    return snapshots.findTopByCaseIdAndKindOrderByVersionDesc(caseId, kind).map(this::view);
  }

  private void requireReady(String caseId, String token) {
    if (!java.util.List.of("READY_TO_ANALYZE", "COMPLETE", "PARTIAL").contains(cases.reopen(caseId, token).status())
        || locks.requiresEvidenceReview(caseId)
        || !vehicles.get(caseId, token).reliableForAnalysis())
      throw new IllegalArgumentException("Current verified Deal Terms and resolved Vehicle identity are required");
  }

  private Snapshot version(String caseId, String kind, int version) {
    return snapshots.findByCaseIdAndKindAndVersion(caseId, kind, version).map(this::view)
        .orElseThrow(() -> new ResourceNotFoundException("Price plan version not found"));
  }

  private Optional<Snapshot> existing(String caseId, String kind, String key, String fingerprint) {
    return snapshots.findByCaseIdAndKindAndIdempotencyKey(caseId, kind, key).map(entity -> {
      offers.requireMatchingFingerprint(entity.requestFingerprint, fingerprint);
      return view(entity);
    });
  }

  private Snapshot append(String caseId, String kind, String key, String fingerprint, JsonNode payload) {
    int version = latest(caseId, kind).map(value -> value.version() + 1).orElse(1);
    var entity = new PlanSnapshotEntity(UUID.randomUUID().toString(), caseId, kind, version,
        key, fingerprint, write(payload), Instant.now());
    return view(snapshots.save(entity));
  }

  private Snapshot view(PlanSnapshotEntity entity) {
    try { return new Snapshot(entity.id, entity.caseId, entity.kind, entity.version,
        json.readTree(entity.payloadJson), entity.createdAt); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }

  private String write(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }

  public record Limits(Long maxSellingPriceMinor, Long maxOutTheDoorPriceMinor) {}
  public record Approval(int planVersion, boolean approved) {}
  public record Snapshot(String id, String caseId, String kind, int version, JsonNode payload, Instant createdAt) {}
}
