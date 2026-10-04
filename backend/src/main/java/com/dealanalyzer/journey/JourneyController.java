package com.dealanalyzer.journey;

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

  JourneyController(JourneyService journeys, VehicleReference reference) {
    this.journeys = journeys; this.reference = reference;
  }

  /** The brief as edited, plus the version it was edited from. */
  public record Revision(Integer basedOnVersion, BriefInput brief) {}

  public record BriefOptions(VehicleReference.ReferenceList reference, Bounds bounds) {}
  public record Bounds(int earliestYear, int latestYear, int maxMileage, int minPriceCeiling, int maxPriceCeiling,
      int minRadiusMiles, int maxRadiusMiles, int maxChoices) {}

  @GetMapping("/brief-options")
  public BriefOptions options(@RequestHeader(value = SESSION, required = false) String session) {
    journeys.authorize(session);
    return new BriefOptions(reference.list(), new Bounds(ShoppingBriefValidator.EARLIEST_YEAR, journeys.latestYear(),
        ShoppingBriefValidator.MAX_MILEAGE, ShoppingBriefValidator.MIN_PRICE, ShoppingBriefValidator.MAX_PRICE,
        ShoppingBriefValidator.MIN_RADIUS, ShoppingBriefValidator.MAX_RADIUS, ShoppingBriefValidator.MAX_CHOICES));
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
