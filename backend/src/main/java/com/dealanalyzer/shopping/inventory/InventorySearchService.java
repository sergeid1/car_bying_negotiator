package com.dealanalyzer.shopping.inventory;

import com.dealanalyzer.journey.JourneyService;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class InventorySearchService {
  private final JourneyService journeys;
  private final InventorySearchAdapter adapter;
  private final ProviderMeter meter;
  private final ObjectMapper json;
  public InventorySearchService(JourneyService journeys, InventorySearchAdapter adapter, ProviderMeter meter, ObjectMapper json) {
    this.journeys = journeys; this.adapter = adapter; this.meter = meter; this.json = json;
  }
  public InventorySearchAdapter.SearchResult search(String accountId, String journeyId) {
    var brief = journeys.currentBrief(accountId, journeyId);
    var criteria = InventorySearchCriteria.from(brief);
    try {
      String query = json.writeValueAsString(new Query(criteria, brief.radiusMiles()));
      return meter.callInventory(accountId, journeyId, adapter.provider(), query,
          () -> adapter.search(criteria, brief.radiusMiles()));
    } catch (JsonProcessingException failure) { throw new IllegalStateException("Inventory criteria unwritable"); }
  }
  public record Query(InventorySearchCriteria criteria, int radiusMiles) {}
}
