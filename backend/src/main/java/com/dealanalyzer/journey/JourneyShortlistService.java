package com.dealanalyzer.journey;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.account.BuyerAccountService;
import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedDealer;
import com.dealanalyzer.listing.ListingEvidenceProvider.NormalizedListing;
import com.dealanalyzer.listing.persistence.ListingEvidencePersistence;
import com.dealanalyzer.market.MarketAssessment;
import com.dealanalyzer.market.MarketAssessmentService;
import com.dealanalyzer.market.MarketDataProvider.MarketVehicle;
import com.dealanalyzer.shopping.inventory.InventoryListing;
import com.dealanalyzer.shopping.matching.CandidateMatchingService;
import com.dealanalyzer.shopping.matching.CandidateVehicle;
import com.dealanalyzer.vehicle.persistence.VehicleEvidencePersistence;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Account-owned shortlist and one standard Buying Case per captured candidate. */
@Service
public class JourneyShortlistService {
  private final AccountJourneyAccess accounts;
  private final BuyerAccountService buyerAccounts;
  private final JourneyService journeyService;
  private final BuyingJourneyRepository journeys;
  private final JourneyCandidateRepository candidates;
  private final CandidateMatchingService matching;
  private final MarketAssessmentService market;
  private final EphemeralBuyingCaseService cases;
  private final ListingEvidencePersistence listings;
  private final VehicleEvidencePersistence vehicles;
  private final JourneyCaseCredentials credentials;
  private final EntityManager manager;
  private final ObjectMapper json;
  private final TransactionTemplate writes;

  JourneyShortlistService(AccountJourneyAccess accounts, BuyerAccountService buyerAccounts, JourneyService journeyService,
      BuyingJourneyRepository journeys, JourneyCandidateRepository candidates, CandidateMatchingService matching,
      MarketAssessmentService market, EphemeralBuyingCaseService cases, ListingEvidencePersistence listings,
      VehicleEvidencePersistence vehicles, JourneyCaseCredentials credentials, EntityManager manager,
      ObjectMapper json, PlatformTransactionManager transactions) {
    this.accounts = accounts; this.buyerAccounts = buyerAccounts; this.journeyService = journeyService;
    this.journeys = journeys; this.candidates = candidates; this.matching = matching; this.market = market;
    this.cases = cases; this.listings = listings; this.vehicles = vehicles; this.credentials = credentials;
    this.manager = manager; this.json = json; this.writes = new TransactionTemplate(transactions);
  }

  public record CheckedCandidate(@JsonUnwrapped CandidateVehicle candidate, String candidateId, MarketAssessment marketCheck) {}
  public record Results(String matcherVersion, int briefVersion, String referenceListVersion,
      com.dealanalyzer.shopping.inventory.InventorySearchAdapter.Availability availability, String notice,
      CandidateMatchingService.Provider provider, Instant retrievedAt, String ordering,
      List<CheckedCandidate> candidates, List<CandidateVehicle.Excluded> excluded) {}
  public record Entry(String candidateId, CandidateVehicle candidate, MarketAssessment marketCheck, String caseId) {}

  /** Provider calls finish before account/journey locks, then the brief is checked again before capture. */
  public Results search(String session, String journeyId) {
    String accountId = accounts.accountId(session);
    var search = matching.search(accountId, journeyId);
    var results = search.results();
    var brief = journeyService.currentBrief(accountId, journeyId);
    var captured = new ArrayList<JourneyCandidateEntity>();
    String searchRunId = UUID.randomUUID().toString();
    for (var candidate : results.candidates()) {
      var listing = search.listings().stream().filter(item -> item.listingId().equals(candidate.listingId())
          && item.source().equals(candidate.snapshot().source())).findFirst().orElseThrow();
      var check = market.advertised(accountId, journeyId, new MarketVehicle(candidate.vin(), candidate.year(),
          candidate.make(), candidate.model(), candidate.trim(), candidate.mileage(), brief.postalCode()));
      var row = new JourneyCandidateEntity();
      row.id = UUID.randomUUID().toString(); row.journeyId = journeyId; row.accountId = accountId;
      row.searchRunId = searchRunId; row.matcherVersion = results.matcherVersion();
      row.briefVersion = results.briefVersion(); row.candidateJson = encode(candidate); row.listingJson = encode(listing);
      row.marketJson = encode(check); row.providerVersion = results.provider().version(); row.createdAt = Instant.now();
      captured.add(row);
    }
    writes.executeWithoutResult(status -> {
      lock(session, journeyId);
      if (journeyService.currentBrief(accountId, journeyId).version() != results.briefVersion())
        throw new CandidateMatchingService.BriefChangedDuringSearch();
      candidates.saveAll(captured);
    });
    return new Results(results.matcherVersion(), results.briefVersion(), results.referenceListVersion(), results.availability(),
        results.notice(), results.provider(), results.retrievedAt(), results.ordering(), captured.stream()
        .map(row -> new CheckedCandidate(decode(row.candidateJson, CandidateVehicle.class), row.id,
            decode(row.marketJson, MarketAssessment.class))).toList(), results.excluded());
  }

  @Transactional(readOnly = true)
  public List<Entry> shortlist(String session, String journeyId) {
    owned(session, journeyId);
    return entries(journeyId);
  }

  @Transactional
  public List<Entry> replace(String session, String journeyId, List<String> ids) {
    lock(session, journeyId);
    if (ids == null || ids.size() > 100 || new HashSet<>(ids).size() != ids.size())
      throw invalid("Choose up to 100 distinct candidates for this shortlist.");
    var ordered = ids.stream().map(id -> candidates.findByIdAndJourneyId(id == null ? "" : id, journeyId)
        .orElseThrow(JourneyProblem::notFound)).toList();
    for (var row : candidates.findByJourneyIdAndShortlistPositionIsNotNullOrderByShortlistPosition(journeyId))
      row.shortlistPosition = null;
    for (int index = 0; index < ordered.size(); index++) ordered.get(index).shortlistPosition = index;
    candidates.flush();
    return entries(journeyId);
  }

  @Transactional
  public EphemeralBuyingCaseService.CreatedBuyingCase openCase(String session, String journeyId, String candidateId,
      CreateBuyingCaseRequest request) {
    var journey = lock(session, journeyId);
    var row = candidates.findByIdAndJourneyId(candidateId, journeyId).orElseThrow(JourneyProblem::notFound);
    var previous = manager.createNativeQuery("select case_id, credential_ciphertext from journey_candidate_cases where candidate_id = :id")
        .setParameter("id", candidateId).getResultList();
    if (!previous.isEmpty()) {
      var link = (Object[]) previous.getFirst();
      String caseId = link[0].toString();
      String token = credentials.decrypt(journey.accountId, caseId, link[1].toString());
      return new EphemeralBuyingCaseService.CreatedBuyingCase(cases.reopen(caseId, token), token);
    }
    if (journey.state != JourneyState.SHOPPING && journey.state != JourneyState.NEGOTIATING)
      throw invalid("Open new Buying Cases while this journey is shopping or negotiating.");
    var listing = decode(row.listingJson, InventoryListing.class);
    var facts = listing.facts();
    // Unknown facts stay unknown. The usual vehicle review can fill them before analysis.
    var created = cases.create(request, "journey-candidate:" + candidateId, null);
    String caseId = created.buyingCase().id();
    var dealer = listing.dealer();
    var snapshot = listings.appendSnapshot(caseId, new NormalizedListing(listing.listingId(), null, listing.vin(),
        facts.year(), facts.make(), facts.model(), facts.trim(), facts.mileage(), listing.advertisedPriceMinor(),
        listing.currency(), new NormalizedDealer(dealer.id(), dealer.publicName(), dealer.locality(), dealer.jurisdiction()),
        listing.source(), listing.retrievedAt(), row.providerVersion, listing.licenceMetadata(), null));
    vehicles.seedListing(caseId, snapshot, Instant.now());
    buyerAccounts.attach(session, caseId, created.accessToken());
    manager.createNativeQuery("insert into journey_candidate_cases (case_id, journey_id, account_id, candidate_id, credential_ciphertext, created_at) "
        + "values (:caseId, :journey, :account, :candidate, :credential, :now)")
        .setParameter("caseId", caseId).setParameter("journey", journeyId).setParameter("account", journey.accountId)
        .setParameter("candidate", candidateId).setParameter("credential", credentials.encrypt(journey.accountId, caseId, created.accessToken()))
        .setParameter("now", Instant.now()).executeUpdate();
    journey.state = JourneyState.NEGOTIATING; journey.updatedAt = Instant.now();
    return created;
  }

  @Transactional(readOnly = true)
  public EphemeralBuyingCaseService.CreatedBuyingCase linkedCase(String session, String journeyId, String candidateId) {
    var journey = owned(session, journeyId);
    var links = manager.createNativeQuery("select l.case_id, l.credential_ciphertext from journey_candidate_cases l "
        + "join buyer_account_cases a on a.case_id = l.case_id and a.account_id = l.account_id "
        + "where l.candidate_id = :candidate and l.journey_id = :journey and l.account_id = :account")
        .setParameter("candidate", candidateId).setParameter("journey", journeyId).setParameter("account", journey.accountId).getResultList();
    if (links.isEmpty()) throw JourneyProblem.notFound();
    Object[] link = (Object[]) links.getFirst();
    String caseId = link[0].toString();
    String token = credentials.decrypt(journey.accountId, caseId, link[1].toString());
    return new EphemeralBuyingCaseService.CreatedBuyingCase(cases.reopen(caseId, token), token);
  }

  private BuyingJourneyEntity owned(String session, String journeyId) {
    return journeys.findByIdAndAccountId(journeyId, accounts.accountId(session)).orElseThrow(JourneyProblem::notFound);
  }
  private BuyingJourneyEntity lock(String session, String journeyId) {
    String accountId = accounts.accountId(session); accounts.lockActiveAccount(accountId);
    return journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
  }
  private List<Entry> entries(String journeyId) {
    return candidates.findByJourneyIdAndShortlistPositionIsNotNullOrderByShortlistPosition(journeyId).stream().map(row -> {
      var links = manager.createNativeQuery("select case_id from journey_candidate_cases where candidate_id = :id")
          .setParameter("id", row.id).getResultList();
      return new Entry(row.id, decode(row.candidateJson, CandidateVehicle.class), decode(row.marketJson, MarketAssessment.class),
          links.isEmpty() ? null : links.getFirst().toString());
    }).toList();
  }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Candidate evidence could not be stored"); }
  }
  private <T> T decode(String value, Class<T> type) {
    try { return json.readValue(value, type); }
    catch (java.io.IOException failure) { throw new IllegalStateException("Candidate evidence could not be read"); }
  }
  private static JourneyProblem invalid(String detail) {
    return new JourneyProblem(HttpStatus.BAD_REQUEST, "SHORTLIST_INVALID", detail, List.of());
  }
}
