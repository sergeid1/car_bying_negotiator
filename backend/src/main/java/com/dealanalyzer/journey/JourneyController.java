package com.dealanalyzer.journey;

import com.dealanalyzer.shopping.matching.CandidateMatchingService;
import com.dealanalyzer.buyingcase.CreateBuyingCaseRequest;
import com.dealanalyzer.buyingcase.EphemeralBuyingCaseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * Buying Journey API. Every endpoint is authorized by the Buyer Account session in the
 * Account-Session-Token header; journeys never accept a case token.
 */
@RestController @RequestMapping("/api/v1/journeys")
public class JourneyController {
  private static final String SESSION = "Account-Session-Token";
  private final JourneyService journeys;
  private final VehicleReference reference;
  private final JourneyShortlistService shortlist;
  private final JourneyQuoteComparisonService comparison;

  JourneyController(JourneyService journeys, VehicleReference reference, JourneyShortlistService shortlist,
      JourneyQuoteComparisonService comparison) {
    this.journeys = journeys; this.reference = reference; this.shortlist = shortlist;
    this.comparison = comparison;
  }

  @GetMapping("/{journeyId}/quote-comparison")
  public JourneyQuoteComparisonService.Comparison comparison(
      @RequestHeader(value = SESSION, required = false) String session, @PathVariable String journeyId) {
    return comparison.view(session, journeyId);
  }

  /** The brief as edited, plus the version it was edited from. */
  public record Revision(Integer basedOnVersion, BriefInput brief) {}

  /** A VIN to decode and add, plus the brief version it was added to. */
  public record ReferenceVehicleInput(Integer basedOnVersion, String vin) {}
  /** The decoded attributes of one Reference Vehicle that matter, by attribute name. */
  public record Relevance(Integer basedOnVersion, List<String> relevantAttributes) {}

  public record BriefOptions(VehicleReference.ReferenceList reference, Bounds bounds) {}
  public record Bounds(int earliestYear, int latestYear, int maxMileage, int minPriceCeiling, int maxPriceCeiling,
      int minRadiusMiles, int maxRadiusMiles, int maxChoices, int maxReferenceVehicles) {}

  @GetMapping("/brief-options")
  public BriefOptions options(@RequestHeader(value = SESSION, required = false) String session) {
    journeys.authorize(session);
    return new BriefOptions(reference.list(), new Bounds(ShoppingBriefValidator.EARLIEST_YEAR, journeys.latestYear(),
        ShoppingBriefValidator.MAX_MILEAGE, ShoppingBriefValidator.MIN_PRICE, ShoppingBriefValidator.MAX_PRICE,
        ShoppingBriefValidator.MIN_RADIUS, ShoppingBriefValidator.MAX_RADIUS, ShoppingBriefValidator.MAX_CHOICES,
        JourneyService.MAX_REFERENCE_VEHICLES));
  }

  @GetMapping
  public List<JourneyService.JourneySummary> list(@RequestHeader(value = SESSION, required = false) String session) {
    return journeys.list(session);
  }

  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public JourneyService.JourneyView create(@RequestHeader(value = SESSION, required = false) String session,
      @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody BriefInput brief) {
    return journeys.create(session, key, brief);
  }

  @GetMapping("/{journeyId}")
  public JourneyService.JourneyView open(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId) {
    return journeys.open(session, journeyId);
  }

  @GetMapping("/{journeyId}/briefs/{version}")
  public ShoppingBrief version(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @PathVariable int version) {
    return journeys.version(session, journeyId, version);
  }

  @PostMapping("/{journeyId}/briefs")
  public JourneyService.JourneyView revise(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @RequestBody Revision revision) {
    return journeys.revise(session, journeyId, revision.basedOnVersion(), revision.brief());
  }

  @GetMapping("/{journeyId}/briefs/{version}/reference-vehicles")
  public List<ReferenceVehicle> referenceVehicles(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @PathVariable int version) {
    return journeys.referenceVehicles(session, journeyId, version);
  }

  @PostMapping("/{journeyId}/reference-vehicles")
  public JourneyService.JourneyView addReferenceVehicle(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @RequestBody ReferenceVehicleInput input) {
    return journeys.addReferenceVehicle(session, journeyId, input.basedOnVersion(), input.vin());
  }

  @PutMapping("/{journeyId}/reference-vehicles/{referenceId}/relevance")
  public JourneyService.JourneyView markRelevant(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @PathVariable String referenceId, @RequestBody Relevance relevance) {
    return journeys.markRelevant(session, journeyId, referenceId, relevance.basedOnVersion(), relevance.relevantAttributes());
  }

  @DeleteMapping("/{journeyId}/reference-vehicles/{referenceId}")
  public JourneyService.JourneyView removeReferenceVehicle(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @PathVariable String referenceId,
      @RequestParam(required = false) Integer basedOnVersion) {
    return journeys.removeReferenceVehicle(session, journeyId, referenceId, basedOnVersion);
  }

  /** Captures candidates and market evidence for the current brief; the Buyer chooses the shortlist order. */
  @PostMapping("/{journeyId}/candidates")
  public JourneyShortlistService.Results candidates(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId) {
    return shortlist.search(session, journeyId);
  }

  public record ShortlistInput(List<String> candidateIds) {}

  @GetMapping("/{journeyId}/shortlist")
  public List<JourneyShortlistService.Entry> shortlist(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId) {
    return shortlist.shortlist(session, journeyId);
  }

  @PutMapping("/{journeyId}/shortlist")
  public List<JourneyShortlistService.Entry> shortlist(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId, @RequestBody ShortlistInput input) {
    return shortlist.replace(session, journeyId, input.candidateIds());
  }

  @PostMapping("/{journeyId}/candidates/{candidateId}/case")
  @ResponseStatus(HttpStatus.CREATED)
  public EphemeralBuyingCaseService.CreatedBuyingCase openCandidateCase(
      @RequestHeader(value = SESSION, required = false) String session, @PathVariable String journeyId,
      @PathVariable String candidateId, @Valid @RequestBody CreateBuyingCaseRequest input) {
    return shortlist.openCase(session, journeyId, candidateId, input);
  }

  @GetMapping("/{journeyId}/candidates/{candidateId}/case")
  public EphemeralBuyingCaseService.CreatedBuyingCase linkedCandidateCase(
      @RequestHeader(value = SESSION, required = false) String session, @PathVariable String journeyId,
      @PathVariable String candidateId) {
    return shortlist.linkedCase(session, journeyId, candidateId);
  }

  @DeleteMapping("/{journeyId}")
  public JourneyService.Deletion delete(@RequestHeader(value = SESSION, required = false) String session,
      @PathVariable String journeyId) {
    return journeys.delete(session, journeyId);
  }

  /** Journey outcomes keep their code, fixed detail and field errors; nothing echoes Buyer input. */
  @RestControllerAdvice(assignableTypes = JourneyController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(JourneyProblem.class)
    ResponseEntity<ProblemDetail> problem(JourneyProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("errors", problem.errors);
      return ResponseEntity.status(problem.status).body(body);
    }

    @ExceptionHandler(CandidateMatchingService.BriefChangedDuringSearch.class)
    ResponseEntity<ProblemDetail> briefChanged(CandidateMatchingService.BriefChangedDuringSearch ignored) {
      return problem(new JourneyProblem(HttpStatus.CONFLICT, "BRIEF_CHANGED",
          "Your Shopping Brief changed while inventory was searched. Search again for the latest version.", List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The Shopping Brief could not be read. Years, mileage, price and radius must be whole numbers.");
      body.setProperty("code", "BRIEF_UNREADABLE");
      body.setProperty("errors", List.of());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}
