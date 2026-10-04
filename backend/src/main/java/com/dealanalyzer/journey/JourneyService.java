package com.dealanalyzer.journey;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.CaseAccessService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Buying Journeys and their Shopping Brief versions. Every operation is scoped to the signed-in
 * Buyer Account; another account's journey is indistinguishable from one that does not exist.
 * Writes hold the account lock first, then the journey lock, matching account deletion.
 */
@Service
public class JourneyService {
  static final String CURRENCY = "USD";

  public record VersionSummary(int version, Instant createdAt) {}
  public record JourneySummary(String id, JourneyState state, Instant createdAt, Instant updatedAt, int currentVersion,
      int yearFrom, int yearTo, String make, String model) {}
  public record JourneyView(String id, JourneyState state, Instant createdAt, Instant updatedAt, ShoppingBrief brief,
      List<VersionSummary> versions) {}
  public record Deletion(String journeyId, String state, String verification) {}

  private static final TypeReference<List<String>> IDS = new TypeReference<>() {};

  private final AccountJourneyAccess accounts;
  private final BuyingJourneyRepository journeys;
  private final ShoppingBriefVersionRepository briefs;
  private final ShoppingBriefValidator validator;
  private final VehicleReference reference;
  private final CaseAccessService secrets;
  private final ObjectMapper json;
  private final EntityManager manager;

  JourneyService(AccountJourneyAccess accounts, BuyingJourneyRepository journeys, ShoppingBriefVersionRepository briefs,
      ShoppingBriefValidator validator, VehicleReference reference, CaseAccessService secrets, ObjectMapper json,
      EntityManager manager) {
    this.accounts = accounts; this.journeys = journeys; this.briefs = briefs; this.validator = validator;
    this.reference = reference; this.secrets = secrets; this.json = json; this.manager = manager;
  }

  /** Refuses a request without a live account session. */
  public void authorize(String session) { accounts.accountId(session); }

  int latestYear() { return validator.latestYear(); }

  @Transactional(readOnly = true)
  public List<JourneySummary> list(String session) {
    String accountId = accounts.accountId(session);
    return journeys.findByAccountIdOrderByCreatedAtDesc(accountId).stream().map(journey -> {
      var brief = latest(journey.id);
      return new JourneySummary(journey.id, journey.state, journey.createdAt, journey.updatedAt, brief.version,
          brief.yearFrom, brief.yearTo, makeLabel(brief.makeId), modelLabel(brief.modelId));
    }).toList();
  }

  /** Starts a journey in SHOPPING with brief version 1. A replayed request key returns the same journey. */
  @Transactional
  public JourneyView create(String session, String requestKey, BriefInput input) {
    if (requestKey == null || requestKey.isBlank() || requestKey.length() > 200) throw new JourneyProblem(
        HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Retry the request; it was missing its request key.", List.of());
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    String keyHash = secrets.protectAccountSecret("journey-request", requestKey);
    var replay = journeys.findByAccountIdAndRequestKeyHash(accountId, keyHash);
    if (replay.isPresent()) return view(replay.get());
    var criteria = validator.validate(input);
    Instant now = Instant.now();
    var journey = new BuyingJourneyEntity(UUID.randomUUID().toString());
    journey.accountId = accountId; journey.requestKeyHash = keyHash; journey.state = JourneyState.SHOPPING;
    journey.createdAt = now; journey.updatedAt = now;
    journeys.saveAndFlush(journey);
    insertVersion(journey, 1, criteria, now);
    return view(journey);
  }

  @Transactional(readOnly = true)
  public JourneyView open(String session, String journeyId) {
    return view(owned(accounts.accountId(session), journeyId));
  }

  /** An earlier version stays readable after later edits. */
  @Transactional(readOnly = true)
  public ShoppingBrief version(String session, String journeyId, int version) {
    var journey = owned(accounts.accountId(session), journeyId);
    return briefs.findByJourneyIdAndVersion(journey.id, version).map(this::brief).orElseThrow(() -> new JourneyProblem(
        HttpStatus.NOT_FOUND, "BRIEF_VERSION_NOT_FOUND", "That Shopping Brief version does not exist.", List.of()));
  }

  /**
   * Saves an edited brief as the next immutable version. {@code basedOnVersion} is the version the
   * Buyer edited: a stale edit is refused rather than silently replacing a newer version, and a
   * retried save of the same edit returns the version it already created.
   */
  @Transactional
  public JourneyView revise(String session, String journeyId, Integer basedOnVersion, BriefInput input) {
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
    var current = latest(journey.id);
    var criteria = validator.validate(input);
    boolean unchanged = criteria.equals(criteria(current));
    if (basedOnVersion != null && unchanged && (basedOnVersion == current.version || basedOnVersion == current.version - 1))
      return view(journey);
    if (basedOnVersion == null || basedOnVersion != current.version) throw new JourneyProblem(HttpStatus.CONFLICT,
        "BRIEF_CHANGED", "This Shopping Brief changed since you opened it. Reload it, review the latest version and "
            + "save your edit again.", List.of());
    Instant now = Instant.now();
    insertVersion(journey, current.version + 1, criteria, now);
    journey.updatedAt = now;
    return view(journey);
  }

  /**
   * Deletes a journey and every brief version under it, then verifies they are gone, as account
   * deletion does for the account graph. Access ends with the transaction; nothing is kept.
   */
  @Transactional
  public Deletion delete(String session, String journeyId) {
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
    String id = journey.id;
    manager.flush(); manager.detach(journey);
    for (String table : List.of("shopping_brief_versions", "buying_journeys")) {
      String column = table.equals("buying_journeys") ? "id" : "journey_id";
      manager.createNativeQuery("delete from " + table + " where " + column + " = :id").setParameter("id", id).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
          .setParameter("id", id).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Journey deletion verification failed");
    }
    return new Deletion(id, "DELETED", "JOURNEY_AND_BRIEF_VERSIONS_ABSENT_V1");
  }

  /** The current brief of an account's journey, for later shopping steps. */
  @Transactional(readOnly = true)
  public ShoppingBrief currentBrief(String accountId, String journeyId) {
    return brief(latest(owned(accountId, journeyId).id));
  }

  /** Every journey and every brief version of an account, for the account export. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> exportForAccount(String accountId) {
    return journeys.findByAccountIdOrderByCreatedAtDesc(accountId).stream().map(journey -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("journeyId", journey.id); row.put("state", journey.state);
      row.put("createdAt", journey.createdAt); row.put("updatedAt", journey.updatedAt);
      row.put("shoppingBriefVersions", briefs.findByJourneyIdOrderByVersion(journey.id).stream().map(this::brief).toList());
      return (Map<String, Object>) row;
    }).toList();
  }

  private BuyingJourneyEntity owned(String accountId, String journeyId) {
    if (journeyId == null || journeyId.length() > 36) throw JourneyProblem.notFound();
    return journeys.findByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
  }

  private ShoppingBriefVersionEntity latest(String journeyId) {
    return briefs.findFirstByJourneyIdOrderByVersionDesc(journeyId)
        .orElseThrow(() -> new IllegalStateException("A journey has no Shopping Brief"));
  }

  private void insertVersion(BuyingJourneyEntity journey, int version, ShoppingBriefValidator.Criteria criteria, Instant now) {
    var row = new ShoppingBriefVersionEntity();
    row.journeyId = journey.id; row.version = version; row.accountId = journey.accountId;
    row.referenceListVersion = reference.version();
    row.yearFrom = criteria.yearFrom(); row.yearTo = criteria.yearTo();
    row.makeId = criteria.makeId(); row.modelId = criteria.modelId(); row.trimIds = write(criteria.trimIds());
    row.maxMileage = criteria.maxMileage(); row.priceCeilingMinor = criteria.priceCeilingMinor(); row.currency = CURRENCY;
    row.postalCode = criteria.postalCode(); row.jurisdiction = criteria.jurisdiction(); row.radiusMiles = criteria.radiusMiles();
    row.mustHaveFeatures = write(criteria.mustHaveFeatures()); row.niceToHaveFeatures = write(criteria.niceToHaveFeatures());
    row.createdAt = now;
    // persist, not merge: an existing (journey, version) key fails instead of overwriting a version.
    manager.persist(row);
    manager.flush();
  }

  private JourneyView view(BuyingJourneyEntity journey) {
    var versions = briefs.findByJourneyIdOrderByVersion(journey.id);
    return new JourneyView(journey.id, journey.state, journey.createdAt, journey.updatedAt, brief(versions.getLast()),
        versions.stream().map(v -> new VersionSummary(v.version, v.createdAt)).toList());
  }

  private ShoppingBriefValidator.Criteria criteria(ShoppingBriefVersionEntity row) {
    return new ShoppingBriefValidator.Criteria(row.yearFrom, row.yearTo, row.makeId, row.modelId, read(row.trimIds),
        row.maxMileage, row.priceCeilingMinor, row.postalCode, row.jurisdiction, row.radiusMiles,
        read(row.mustHaveFeatures), read(row.niceToHaveFeatures));
  }

  private ShoppingBrief brief(ShoppingBriefVersionEntity row) {
    var model = reference.model(row.makeId, row.modelId);
    return new ShoppingBrief(row.journeyId, row.version, row.referenceListVersion, row.yearFrom, row.yearTo,
        new ShoppingBrief.Choice(row.makeId, makeLabel(row.makeId)), new ShoppingBrief.Choice(row.modelId, modelLabel(row.modelId)),
        read(row.trimIds).stream().map(id -> new ShoppingBrief.Choice(id,
            model.flatMap(m -> reference.trim(m, id)).map(VehicleReference.Option::label).orElse(id))).toList(),
        row.maxMileage, row.priceCeilingMinor, row.currency, row.postalCode, row.jurisdiction, row.radiusMiles,
        features(row.mustHaveFeatures), features(row.niceToHaveFeatures), row.createdAt);
  }

  private List<ShoppingBrief.Choice> features(String ids) {
    return read(ids).stream().map(id -> new ShoppingBrief.Choice(id,
        reference.feature(id).map(VehicleReference.Option::label).orElse(id))).toList();
  }

  // A brief recorded under another list version keeps its identifiers when a label is unknown.
  private String makeLabel(String id) { return reference.make(id).map(VehicleReference.Make::label).orElse(id); }

  private String modelLabel(String id) {
    return reference.list().makes().stream().flatMap(m -> m.models().stream()).filter(m -> m.id().equals(id))
        .map(VehicleReference.Model::label).findFirst().orElse(id);
  }

  private String write(List<String> ids) {
    try { return json.writeValueAsString(ids); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Brief identifiers unwritable", failure); }
  }

  private List<String> read(String ids) {
    try { return json.readValue(ids, IDS); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Brief identifiers unreadable", failure); }
  }
}
