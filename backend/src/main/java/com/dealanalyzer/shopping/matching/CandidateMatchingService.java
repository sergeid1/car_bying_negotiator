package com.dealanalyzer.shopping.matching;

import com.dealanalyzer.journey.JourneyService;
import com.dealanalyzer.shopping.inventory.InventorySearchAdapter;
import com.dealanalyzer.shopping.inventory.InventorySearchService;
import com.dealanalyzer.shopping.inventory.InventoryListing;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Searches inventory for a journey's current Shopping Brief and matches the results. Reads only the
 * brief, its Reference Vehicles' relevant details and the search result; nothing is persisted here.
 */
@Service
public class CandidateMatchingService {
  private final JourneyService journeys;
  private final InventorySearchService inventory;

  public CandidateMatchingService(JourneyService journeys, InventorySearchService inventory) {
    this.journeys = journeys; this.inventory = inventory;
  }

  public record Provider(String name, String version, String licence, boolean synthetic) {}
  public record CandidateResults(String matcherVersion, int briefVersion, String referenceListVersion,
      InventorySearchAdapter.Availability availability, String notice, Provider provider, Instant retrievedAt,
      String ordering, List<CandidateVehicle> candidates, List<CandidateVehicle.Excluded> excluded) {}

  /** The brief was revised while the search ran, so its results would describe an older brief. */
  public static class BriefChangedDuringSearch extends RuntimeException {
    public BriefChangedDuringSearch() { super(null, null, false, false); }
  }

  public CandidateResults candidates(String accountId, String journeyId) {
    return search(accountId, journeyId).results();
  }

  /** Retains the licensed listing evidence for journey shortlist capture without searching twice. */
  public record MatchedSearch(CandidateResults results,
      List<InventoryListing> listings) {}

  public MatchedSearch search(String accountId, String journeyId) {
    var brief = journeys.currentBrief(accountId, journeyId);
    var references = journeys.referenceVehicles(accountId, journeyId);
    var result = inventory.search(accountId, journeyId);
    // Every Reference Vehicle change saves a brief version, so one unchanged version covers both reads.
    if (journeys.currentBrief(accountId, journeyId).version() != brief.version()) throw new BriefChangedDuringSearch();
    var matched = CandidateMatcher.match(brief, references, result.listings());
    var provider = result.provider();
    return new MatchedSearch(new CandidateResults(CandidateMatcher.VERSION, brief.version(), brief.referenceListVersion(),
        result.availability(), result.notice(),
        new Provider(provider.name(), provider.version(), provider.licenceMetadata(), provider.synthetic()),
        result.retrievedAt(), CandidateMatcher.ORDERING, matched.candidates(), matched.excluded()), result.listings());
  }
}
