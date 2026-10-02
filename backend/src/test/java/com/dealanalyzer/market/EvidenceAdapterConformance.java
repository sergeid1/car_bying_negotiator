package com.dealanalyzer.market;

import static org.assertj.core.api.Assertions.assertThat;
import com.dealanalyzer.listing.ListingEvidenceProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Inherit this suite for every adapter, supplying recorded available and unavailable scenarios. */
public abstract class EvidenceAdapterConformance {
  protected abstract MarketDataProvider market();
  protected abstract ListingEvidenceProvider listing();
  protected abstract MarketDataProvider.ProviderResult unavailableMarket();
  protected abstract String supportedListingUrl();
  protected abstract String unavailableListingUrl();

  @Test void marketSnapshotAmountsAndObservationTimesAreNormalized() {
    var result = market().retrieve(new MarketDataProvider.MarketVehicle(
        null, 2021, "Honda", "Accord", "EX-L", 38_000L, "27604"));
    assertThat(MarketProviderContract.failure(result, Instant.now())).isNull();
    assertThat(result.availability()).isEqualTo(MarketDataProvider.Availability.AVAILABLE);
    assertThat(result.observations()).isNotEmpty();
    for (var observation : result.observations()) {
      assertThat(observation.priceMinor()).isNotNegative();
      assertThat(observation.currency()).isEqualTo("USD");
      assertThat(observation.observedAt()).isBeforeOrEqualTo(result.snapshot().retrievedAt().plusSeconds(60));
    }
  }

  @Test void listingSnapshotUsesMinorUnitsAndAnObservationAtRetrieval() {
    var result = listing().retrieve(supportedListingUrl());
    assertThat(com.dealanalyzer.listing.ListingProviderContract.valid(result, Instant.now())).isTrue();
    assertThat(result.status()).isEqualTo(ListingEvidenceProvider.RetrievalStatus.SUPPORTED);
    assertThat(result.listing().currency()).isEqualTo("USD");
    assertThat(result.listing().advertisedPriceMinor()).isNotNegative();
    assertThat(result.listing().retrievedAt()).isNotNull();
  }

  @Test void unavailableResultsNeverSubstitutePrices() {
    var market = unavailableMarket();
    assertThat(MarketProviderContract.failure(market, Instant.now())).isNull();
    assertThat(market.availability()).isEqualTo(MarketDataProvider.Availability.UNAVAILABLE);
    assertThat(market.observations()).isEmpty();
    var result = listing().retrieve(unavailableListingUrl());
    assertThat(com.dealanalyzer.listing.ListingProviderContract.valid(result, Instant.now())).isTrue();
    assertThat(result.status()).isNotEqualTo(ListingEvidenceProvider.RetrievalStatus.SUPPORTED);
    assertThat(result.listing()).isNull();
  }

  @Test void domainContractsContainNoVendorTypes() {
    for (var type : List.of(MarketDataProvider.class, ListingEvidenceProvider.class)) {
      for (var method : type.getDeclaredMethods()) {
        assertDomainType(method.getGenericReturnType().getTypeName());
        for (var parameter : method.getGenericParameterTypes()) assertDomainType(parameter.getTypeName());
      }
      for (var nested : type.getDeclaredClasses()) if (nested.isRecord())
        for (var component : nested.getRecordComponents()) assertDomainType(component.getGenericType().getTypeName());
    }
  }

  private static void assertDomainType(String name) {
    assertThat(name).matches("(?:java\\.(?:lang|time|util)\\..*|com\\.dealanalyzer\\..*)");
    if (name.contains("<")) for (String argument : name.substring(name.indexOf('<') + 1, name.lastIndexOf('>')).split(","))
      assertDomainType(argument.strip());
  }
}
