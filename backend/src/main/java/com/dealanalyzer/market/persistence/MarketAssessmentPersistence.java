package com.dealanalyzer.market.persistence;

import com.dealanalyzer.market.MarketAssessment;
import com.dealanalyzer.market.MarketAssessment.ComparableDecision;
import com.dealanalyzer.market.MarketAssessment.Decision;
import com.dealanalyzer.market.MarketDataProvider.ComparableObservation;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MarketAssessmentPersistence {
  private final MarketAssessmentRepository assessments;
  private final MarketProviderSnapshotRepository providerSnapshots;
  private final ComparableSnapshotRepository comparables;
  private final ObjectMapper json;

  MarketAssessmentPersistence(
      MarketAssessmentRepository assessments,
      MarketProviderSnapshotRepository providerSnapshots,
      ComparableSnapshotRepository comparables,
      ObjectMapper json) {
    this.assessments = assessments;
    this.providerSnapshots = providerSnapshots;
    this.comparables = comparables;
    this.json = json;
  }

  @Transactional
  public MarketAssessmentView append(
      String caseId, ProviderResult providerResult, MarketAssessment assessment, Instant now) {
    int version = assessments.findTopByCaseIdOrderByVersionDesc(caseId)
        .map(existing -> existing.version + 1).orElse(1);
    MarketProviderSnapshotEntity provider = providerSnapshots.save(new MarketProviderSnapshotEntity(
        UUID.randomUUID().toString(), caseId, providerResult, now));
    List<ComparableSnapshotEntity> storedComparables = new ArrayList<>();
    int ordinal = 0;
    for (ComparableDecision decision : assessment.decisions()) {
      storedComparables.add(new ComparableSnapshotEntity(
          UUID.randomUUID().toString(), provider, decision, write(decision.matchFactors()), ordinal++));
    }
    comparables.saveAll(storedComparables);
    MarketAssessmentEntity stored = assessments.save(new MarketAssessmentEntity(
        UUID.randomUUID().toString(), caseId, provider, version, assessment, now));
    return view(stored, storedComparables);
  }

  @Transactional(readOnly = true)
  public Optional<MarketAssessmentView> latest(String caseId) {
    return assessments.findTopByCaseIdOrderByVersionDesc(caseId).map(this::viewWithComparables);
  }

  @Transactional(readOnly = true)
  public Optional<MarketAssessmentView> version(String caseId, int version) {
    return assessments.findByCaseIdAndVersion(caseId, version).map(this::viewWithComparables);
  }

  private MarketAssessmentView viewWithComparables(MarketAssessmentEntity entity) {
    return view(entity,
        comparables.findByProviderSnapshotIdOrderByOrdinal(entity.providerSnapshot.id));
  }

  private MarketAssessmentView view(
      MarketAssessmentEntity value, List<ComparableSnapshotEntity> storedComparables) {
    List<ComparableView> included = new ArrayList<>();
    List<ExclusionView> exclusions = new ArrayList<>();
    for (ComparableSnapshotEntity comparable : storedComparables) {
      if (Decision.INCLUDED.name().equals(comparable.decision)) {
        included.add(comparable(comparable));
      } else {
        exclusions.add(new ExclusionView(
            comparable.id,
            comparable.providerObservationId,
            comparable.reasonCode,
            comparable.reason));
      }
    }
    RangeView range = value.rangeLowMinor == null ? null
        : new RangeView(value.rangeLowMinor, value.rangeHighMinor, value.currency);
    VintageView vintage = value.oldestObservationAt == null ? null
        : new VintageView(value.oldestObservationAt, value.newestObservationAt);
    MarketProviderSnapshotEntity provider = value.providerSnapshot;
    return new MarketAssessmentView(
        value.id,
        value.caseId,
        value.schemaVersion,
        value.version,
        value.status,
        range,
        value.geography,
        vintage,
        value.sampleSize,
        value.methodVersion,
        value.confidence,
        value.limitation,
        new ProviderView(
            provider.id,
            provider.source,
            provider.providerVersion,
            provider.schemaVersion,
            provider.licenseMetadata,
            provider.artifactReference,
            provider.availability,
            provider.unavailableReason,
            provider.retrievedAt),
        List.copyOf(included),
        List.copyOf(exclusions),
        value.createdAt);
  }

  private ComparableView comparable(ComparableSnapshotEntity value) {
    AdjustmentView adjustment = value.adjustmentMinor == null ? null
        : new AdjustmentView(
            value.adjustmentMinor, value.adjustmentPrecision, value.adjustmentExplanation);
    return new ComparableView(
        value.id,
        value.providerObservationId,
        value.listingUrl,
        value.year,
        value.make,
        value.model,
        value.trim,
        value.mileage,
        value.priceMinor,
        value.currency,
        value.geography,
        value.observedAt,
        readFactors(value.matchFactorsJson),
        adjustment,
        value.adjustedPriceMinor);
  }

  private String write(List<String> value) {
    try {
      return json.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to persist comparable match factors", exception);
    }
  }

  private List<String> readFactors(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to read comparable match factors", exception);
    }
  }

  public record MarketAssessmentView(
      String id,
      String caseId,
      String schemaVersion,
      int version,
      String status,
      RangeView range,
      String geography,
      VintageView vintage,
      int sampleSize,
      String methodVersion,
      String confidence,
      String limitation,
      ProviderView provider,
      List<ComparableView> comparables,
      List<ExclusionView> exclusions,
      Instant createdAt) {}

  public record RangeView(long lowMinor, long highMinor, String currency) {}
  public record VintageView(Instant oldestObservationAt, Instant newestObservationAt) {}
  public record ProviderView(
      String id,
      String source,
      String providerVersion,
      String schemaVersion,
      String licenseMetadata,
      String artifactReference,
      String availability,
      String unavailableReason,
      Instant retrievedAt) {}
  public record ComparableView(
      String id,
      String providerObservationId,
      String listingUrl,
      Integer year,
      String make,
      String model,
      String trim,
      Long mileage,
      Long priceMinor,
      String currency,
      String geography,
      Instant observedAt,
      List<String> matchFactors,
      AdjustmentView adjustment,
      Long adjustedPriceMinor) {}
  public record AdjustmentView(long amountMinor, String precision, String explanation) {}
  public record ExclusionView(
      String comparableSnapshotId,
      String providerObservationId,
      String reasonCode,
      String reason) {}
}
