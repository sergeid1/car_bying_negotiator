package com.dealanalyzer.journey;

import com.dealanalyzer.account.AccountJourneyAccess;
import com.dealanalyzer.buyingcase.CaseAccessService;
import com.dealanalyzer.journey.vin.VehicleAttribute;
import com.dealanalyzer.journey.vin.VinDecoder;
import com.dealanalyzer.journey.vin.VinDecoderUnavailableException;
import com.dealanalyzer.journey.vin.VinDecoding;
import com.dealanalyzer.vehicle.InvalidVehicleEvidenceException;
import com.dealanalyzer.vehicle.Vin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Buying Journeys and their Shopping Brief versions. Every operation is scoped to the signed-in
 * Buyer Account; another account's journey is indistinguishable from one that does not exist.
 * Writes hold the account lock first, then the journey lock, matching account deletion.
 */
@Service
public class JourneyService {
  static final String CURRENCY = "USD";
  public static final int MAX_REFERENCE_VEHICLES = 5;

  public record VersionSummary(int version, Instant createdAt) {}
  public record JourneySummary(String id, JourneyState state, Instant createdAt, Instant updatedAt, int currentVersion,
      int yearFrom, int yearTo, String make, String model) {}
  public record JourneyView(String id, JourneyState state, Instant createdAt, Instant updatedAt, ShoppingBrief brief,
      List<VersionSummary> versions, List<ReferenceVehicle> referenceVehicles) {}
  public record Deletion(String journeyId, String state, String verification) {}

  private static final TypeReference<List<String>> IDS = new TypeReference<>() {};
  private static final TypeReference<List<StoredAttribute>> ATTRIBUTES = new TypeReference<>() {};

  /** One decoded attribute as stored in {@code reference_vehicles.attributes}. */
  record StoredAttribute(VehicleAttribute attribute, String value, boolean relevant) {}

  private final AccountJourneyAccess accounts;
  private final BuyingJourneyRepository journeys;
  private final ShoppingBriefVersionRepository briefs;
  private final ReferenceVehicleRepository vehicles;
  private final VinDecoder decoder;
  private final TransactionTemplate reads;
  private final TransactionTemplate writes;
  private final ShoppingBriefValidator validator;
  private final VehicleReference reference;
  private final CaseAccessService secrets;
  private final ObjectMapper json;
  private final EntityManager manager;

  JourneyService(AccountJourneyAccess accounts, BuyingJourneyRepository journeys, ShoppingBriefVersionRepository briefs,
      ShoppingBriefValidator validator, VehicleReference reference, CaseAccessService secrets, ObjectMapper json,
      EntityManager manager, ReferenceVehicleRepository vehicles, VinDecoder decoder, PlatformTransactionManager transactions) {
    this.accounts = accounts; this.journeys = journeys; this.briefs = briefs; this.validator = validator;
    this.reference = reference; this.secrets = secrets; this.json = json; this.manager = manager;
    this.vehicles = vehicles; this.decoder = decoder;
    this.reads = new TransactionTemplate(transactions); this.reads.setReadOnly(true);
    this.writes = new TransactionTemplate(transactions);
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
    copyVehicles(journey.id, current.version, current.version + 1, null, null);
    journey.updatedAt = now;
    return view(journey);
  }

  /**
   * Adds a Reference Vehicle as the next brief version. The VIN is checksum-validated before any
   * decoder call, and the decoder is called outside any transaction so a slow decoder holds no lock.
   * A VIN the brief already holds returns the journey unchanged, so a retried add is safe. An
   * unavailable decoder saves nothing; an undecodable VIN is kept and shown without attributes.
   */
  public JourneyView addReferenceVehicle(String session, String journeyId, Integer basedOnVersion, String vinInput) {
    String accountId = reads.execute(status -> accounts.accountId(session));
    String vin = validVin(vinInput);
    JourneyView settled = reads.execute(status -> {
      var journey = owned(accountId, journeyId);
      return addSettled(journey, latest(journey.id), basedOnVersion, vin);
    });
    if (settled != null) return settled;
    VinDecoding decoding;
    try { decoding = decoder.decode(vin); }
    catch (VinDecoderUnavailableException unavailable) {
      throw new JourneyProblem(HttpStatus.SERVICE_UNAVAILABLE, "VIN_DECODER_UNAVAILABLE", "The VIN decoder is not "
          + "answering right now. Nothing was saved and nothing was guessed. Try adding this VIN again in a few minutes.", List.of());
    }
    return writes.execute(status -> {
      accounts.lockActiveAccount(accountId);
      var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
      var current = latest(journey.id);
      var again = addSettled(journey, current, basedOnVersion, vin);
      if (again != null) return again;
      Instant now = Instant.now();
      int next = nextVersion(current, now);
      int position = copyVehicles(journey.id, current.version, next, null, null) + 1;
      var row = new ReferenceVehicleEntity();
      row.journeyId = journey.id; row.briefVersion = next; row.position = position;
      row.referenceId = UUID.randomUUID().toString(); row.accountId = journey.accountId; row.vin = vin;
      row.decodeOutcome = decoding.outcome().name(); row.source = decoding.source(); row.decoderVersion = decoding.decoderVersion();
      row.decodedAt = now;
      // A new Reference Vehicle starts with nothing marked: the Buyer chooses which details matter.
      row.attributes = writeAttributes(decoding.attributes().entrySet().stream()
          .map(entry -> new StoredAttribute(entry.getKey(), entry.getValue(), false)).toList());
      manager.persist(row);
      manager.flush();
      journey.updatedAt = now;
      return view(journey);
    });
  }

  /** Removes a Reference Vehicle as the next brief version. A retried removal returns the version it created. */
  @Transactional
  public JourneyView removeReferenceVehicle(String session, String journeyId, String referenceId, Integer basedOnVersion) {
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
    var current = latest(journey.id);
    boolean held = vehicleRows(journey.id, current.version).stream().anyMatch(v -> v.referenceId.equals(referenceId));
    if (!held) {
      boolean retried = basedOnVersion != null && basedOnVersion == current.version - 1
          && vehicleRows(journey.id, basedOnVersion).stream().anyMatch(v -> v.referenceId.equals(referenceId));
      if (retried) return view(journey);
      throw referenceNotFound();
    }
    requireCurrent(basedOnVersion, current);
    Instant now = Instant.now();
    copyVehicles(journey.id, current.version, nextVersion(current, now), referenceId, null);
    journey.updatedAt = now;
    return view(journey);
  }

  /**
   * Records which decoded attributes of one Reference Vehicle matter, as the next brief version.
   * Saving the same choice again returns the journey unchanged.
   */
  @Transactional
  public JourneyView markRelevant(String session, String journeyId, String referenceId, Integer basedOnVersion,
      List<String> relevantAttributes) {
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
    var current = latest(journey.id);
    var target = vehicleRows(journey.id, current.version).stream().filter(v -> v.referenceId.equals(referenceId))
        .findFirst().orElseThrow(JourneyService::referenceNotFound);
    var stored = readAttributes(target.attributes);
    var decoded = stored.stream().map(a -> a.attribute().name()).collect(Collectors.toSet());
    if (relevantAttributes == null || relevantAttributes.size() > VehicleAttribute.values().length
        || !decoded.containsAll(relevantAttributes)) throw new JourneyProblem(HttpStatus.BAD_REQUEST, "RELEVANCE_INVALID",
        "Choose only details that were decoded for this Reference Vehicle.",
        List.of(new JourneyProblem.FieldError("relevantAttributes", "Choose only details that were decoded for this Reference Vehicle.")));
    Set<VehicleAttribute> wanted = EnumSet.noneOf(VehicleAttribute.class);
    relevantAttributes.forEach(name -> wanted.add(VehicleAttribute.valueOf(name)));
    Set<VehicleAttribute> marked = EnumSet.noneOf(VehicleAttribute.class);
    stored.stream().filter(StoredAttribute::relevant).forEach(a -> marked.add(a.attribute()));
    if (wanted.equals(marked)) return view(journey);
    requireCurrent(basedOnVersion, current);
    Instant now = Instant.now();
    String changed = writeAttributes(stored.stream()
        .map(a -> new StoredAttribute(a.attribute(), a.value(), wanted.contains(a.attribute()))).toList());
    copyVehicles(journey.id, current.version, nextVersion(current, now), null, Map.of(referenceId, changed));
    journey.updatedAt = now;
    return view(journey);
  }

  /** The Reference Vehicles of one brief version; an earlier version stays readable. */
  @Transactional(readOnly = true)
  public List<ReferenceVehicle> referenceVehicles(String session, String journeyId, int version) {
    var journey = owned(accounts.accountId(session), journeyId);
    var row = briefs.findByJourneyIdAndVersion(journey.id, version).orElseThrow(() -> new JourneyProblem(
        HttpStatus.NOT_FOUND, "BRIEF_VERSION_NOT_FOUND", "That Shopping Brief version does not exist.", List.of()));
    return vehicles(row.journeyId, row.version);
  }

  /**
   * Deletes a journey, every brief version and every Reference Vehicle under it, then verifies they are gone, as account
   * deletion does for the account graph. Access ends with the transaction; nothing is kept.
   */
  @Transactional
  public Deletion delete(String session, String journeyId) {
    String accountId = accounts.accountId(session);
    accounts.lockActiveAccount(accountId);
    var journey = journeys.findLockedByIdAndAccountId(journeyId, accountId).orElseThrow(JourneyProblem::notFound);
    String id = journey.id;
    manager.flush(); manager.detach(journey);
    for (String table : List.of("reference_vehicles", "shopping_brief_versions", "buying_journeys")) {
      String column = table.equals("buying_journeys") ? "id" : "journey_id";
      manager.createNativeQuery("delete from " + table + " where " + column + " = :id").setParameter("id", id).executeUpdate();
      long remaining = ((Number) manager.createNativeQuery("select count(*) from " + table + " where " + column + " = :id")
          .setParameter("id", id).getSingleResult()).longValue();
      if (remaining != 0) throw new IllegalStateException("Journey deletion verification failed");
    }
    return new Deletion(id, "DELETED", "JOURNEY_BRIEF_VERSIONS_AND_REFERENCE_VEHICLES_ABSENT_V1");
  }

  /** The current brief of an account's journey, for later shopping steps. */
  @Transactional(readOnly = true)
  public ShoppingBrief currentBrief(String accountId, String journeyId) {
    return brief(latest(owned(accountId, journeyId).id));
  }

  /**
   * The current brief version's Reference Vehicles of an account's journey, for later shopping
   * steps. Matching reads only {@link ReferenceVehicle#relevantAttributes()}.
   */
  @Transactional(readOnly = true)
  public List<ReferenceVehicle> referenceVehicles(String accountId, String journeyId) {
    var journey = owned(accountId, journeyId);
    return vehicles(journey.id, latest(journey.id).version);
  }

  /** Every journey and every brief version of an account, for the account export. */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> exportForAccount(String accountId) {
    return journeys.findByAccountIdOrderByCreatedAtDesc(accountId).stream().map(journey -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("journeyId", journey.id); row.put("state", journey.state);
      row.put("createdAt", journey.createdAt); row.put("updatedAt", journey.updatedAt);
      var versions = briefs.findByJourneyIdOrderByVersion(journey.id);
      row.put("shoppingBriefVersions", versions.stream().map(this::brief).toList());
      row.put("referenceVehicles", versions.stream().map(v -> {
        var held = new LinkedHashMap<String, Object>();
        held.put("briefVersion", v.version); held.put("vehicles", vehicles(v.journeyId, v.version));
        return held;
      }).filter(held -> !((List<?>) held.get("vehicles")).isEmpty()).toList());
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

  private static String validVin(String input) {
    try { return Vin.validated(input); }
    catch (InvalidVehicleEvidenceException invalid) {
      throw new JourneyProblem(HttpStatus.BAD_REQUEST, "VIN_INVALID", "That VIN was not decoded because it is not a valid VIN.",
          List.of(new JourneyProblem.FieldError("vin", "This VIN fails the VIN checksum. Enter all 17 characters exactly as "
              + "shown on the car or its paperwork; VINs never use the letters I, O or Q.")));
    }
  }

  /** A VIN the brief already holds returns the view (a retry); a stale or full brief is refused; otherwise null. */
  private JourneyView addSettled(BuyingJourneyEntity journey, ShoppingBriefVersionEntity current, Integer basedOnVersion,
      String vin) {
    var held = vehicleRows(journey.id, current.version);
    if (held.stream().anyMatch(v -> v.vin.equals(vin))) return view(journey);
    requireCurrent(basedOnVersion, current);
    if (held.size() >= MAX_REFERENCE_VEHICLES) throw new JourneyProblem(HttpStatus.UNPROCESSABLE_ENTITY,
        "REFERENCE_VEHICLE_LIMIT", "A Shopping Brief holds up to five Reference Vehicles. Remove one before adding another.",
        List.of(new JourneyProblem.FieldError("vin", "Remove a Reference Vehicle before adding another; a brief holds up to five.")));
    return null;
  }

  private static void requireCurrent(Integer basedOnVersion, ShoppingBriefVersionEntity current) {
    if (basedOnVersion == null || basedOnVersion != current.version) throw new JourneyProblem(HttpStatus.CONFLICT,
        "BRIEF_CHANGED", "This Shopping Brief changed since you opened it. Reload it, review the latest version and "
            + "save your edit again.", List.of());
  }

  private static JourneyProblem referenceNotFound() {
    return new JourneyProblem(HttpStatus.NOT_FOUND, "REFERENCE_VEHICLE_NOT_FOUND",
        "That Reference Vehicle is not in the current Shopping Brief. Reload the journey to see its Reference Vehicles.", List.of());
  }

  /** Inserts the next brief version with the same criteria and reference list as {@code current}. */
  private int nextVersion(ShoppingBriefVersionEntity current, Instant now) {
    var row = new ShoppingBriefVersionEntity();
    row.journeyId = current.journeyId; row.version = current.version + 1; row.accountId = current.accountId;
    row.referenceListVersion = current.referenceListVersion;
    row.yearFrom = current.yearFrom; row.yearTo = current.yearTo; row.makeId = current.makeId; row.modelId = current.modelId;
    row.trimIds = current.trimIds; row.maxMileage = current.maxMileage; row.priceCeilingMinor = current.priceCeilingMinor;
    row.currency = current.currency; row.postalCode = current.postalCode; row.jurisdiction = current.jurisdiction;
    row.radiusMiles = current.radiusMiles; row.mustHaveFeatures = current.mustHaveFeatures;
    row.niceToHaveFeatures = current.niceToHaveFeatures; row.createdAt = now;
    manager.persist(row);
    manager.flush();
    return row.version;
  }

  /**
   * Copies one version's Reference Vehicles into another, leaving out {@code omitted} and replacing
   * the attributes named in {@code changedAttributes}. Returns how many were copied.
   */
  private int copyVehicles(String journeyId, int from, int to, String omitted, Map<String, String> changedAttributes) {
    int position = 0;
    for (var source : vehicleRows(journeyId, from)) {
      if (source.referenceId.equals(omitted)) continue;
      var row = new ReferenceVehicleEntity();
      row.journeyId = journeyId; row.briefVersion = to; row.position = ++position; row.referenceId = source.referenceId;
      row.accountId = source.accountId; row.vin = source.vin; row.decodeOutcome = source.decodeOutcome;
      row.source = source.source; row.decoderVersion = source.decoderVersion; row.decodedAt = source.decodedAt;
      row.attributes = changedAttributes == null ? source.attributes
          : changedAttributes.getOrDefault(source.referenceId, source.attributes);
      manager.persist(row);
    }
    manager.flush();
    return position;
  }

  private List<ReferenceVehicleEntity> vehicleRows(String journeyId, int version) {
    return vehicles.findByJourneyIdAndBriefVersionOrderByPosition(journeyId, version);
  }

  private List<ReferenceVehicle> vehicles(String journeyId, int version) {
    return vehicleRows(journeyId, version).stream().map(row -> new ReferenceVehicle(row.referenceId, row.vin,
        VinDecoding.Outcome.valueOf(row.decodeOutcome), row.source, row.decoderVersion, row.decodedAt,
        readAttributes(row.attributes).stream().sorted(java.util.Comparator.comparing(StoredAttribute::attribute))
            .map(a -> new ReferenceVehicle.Attribute(a.attribute(), a.attribute().label(), a.value(), row.source,
                row.decoderVersion, a.relevant())).toList())).toList();
  }

  private String writeAttributes(List<StoredAttribute> attributes) {
    try { return json.writeValueAsString(attributes); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Reference attributes unwritable", failure); }
  }

  private List<StoredAttribute> readAttributes(String attributes) {
    try { return json.readValue(attributes, ATTRIBUTES); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Reference attributes unreadable", failure); }
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
        versions.stream().map(v -> new VersionSummary(v.version, v.createdAt)).toList(),
        vehicles(journey.id, versions.getLast().version));
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
