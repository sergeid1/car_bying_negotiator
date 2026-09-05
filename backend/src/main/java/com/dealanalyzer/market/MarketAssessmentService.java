package com.dealanalyzer.market;

import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.buyingcase.ResourceNotFoundException;
import com.dealanalyzer.market.MarketDataProvider.Availability;
import com.dealanalyzer.market.MarketDataProvider.MarketVehicle;
import com.dealanalyzer.market.MarketDataProvider.ProviderResult;
import com.dealanalyzer.market.MarketDataProvider.ProviderSnapshot;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence;
import com.dealanalyzer.market.persistence.MarketAssessmentPersistence.MarketAssessmentView;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.VehicleEvidenceService;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class MarketAssessmentService {
  private final EphemeralBuyingCaseService cases;
  private final VehicleEvidenceService vehicles;
  private final MarketDataProvider provider;
  private final MarketAssessmentPersistence persistence;
  private final MarketAssessmentPolicy policy = new MarketAssessmentPolicy();

  public MarketAssessmentService(
      EphemeralBuyingCaseService cases,
      VehicleEvidenceService vehicles,
      MarketDataProvider provider,
      MarketAssessmentPersistence persistence) {
    this.cases = cases;
    this.vehicles = vehicles;
    this.provider = provider;
    this.persistence = persistence;
  }

  public MarketAssessmentView create(String caseId, String accessToken) {
    var buyingCase = cases.reopen(caseId, accessToken);
    var evidence = vehicles.get(caseId, accessToken);
    if (!evidence.reliableForAnalysis()) {
      throw new InvalidVehicleEvidenceException(
          "Resolve vehicle identity conflicts before requesting market analysis.");
    }
    var vehicle = evidence.vehicle();
    MarketVehicle query = new MarketVehicle(
        vehicle.vin(), vehicle.year(), vehicle.make(), vehicle.model(), vehicle.trim(),
        vehicle.mileage(), buyingCase.dealerLocation());
    Instant now = Instant.now();
    ProviderResult result;
    try {
      result = provider.retrieve(query);
      if (result.snapshot() == null) {
        result = unavailable("Provider response did not include snapshot metadata.", now);
      } else if (!"market-provider.v1".equals(result.snapshot().schemaVersion())) {
        result = new ProviderResult(
            Availability.UNAVAILABLE,
            "Provider schema drift; market analysis is unavailable.",
            result.snapshot(),
            java.util.List.of());
      }
    } catch (RuntimeException failure) {
      result = unavailable("Market provider failed; market analysis is unavailable.", now);
    }
    return persistence.append(caseId, result, policy.assess(query, result), now);
  }

  public MarketAssessmentView latest(String caseId, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.latest(caseId)
        .orElseThrow(() -> new ResourceNotFoundException("Market Assessment not found"));
  }

  public MarketAssessmentView version(String caseId, int version, String accessToken) {
    cases.reopen(caseId, accessToken);
    return persistence.version(caseId, version)
        .orElseThrow(() -> new ResourceNotFoundException("Market Assessment not found"));
  }

  private static ProviderResult unavailable(String reason, Instant now) {
    return new ProviderResult(
        Availability.UNAVAILABLE,
        reason,
        new ProviderSnapshot(
            "MARKET_PROVIDER", "unavailable", "market-provider.v1", "not available",
            null, now),
        java.util.List.of());
  }
}
