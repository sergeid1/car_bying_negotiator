package com.dealanalyzer.desk;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * "I'm at the dealer": routing for photographed desk paperwork and the first result once the Buyer
 * confirms which car it is for. Both read; neither stores anything.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/desk-start")
public class DeskStartController {
  private final DeskStartService desk;

  DeskStartController(DeskStartService desk) { this.desk = desk; }

  @GetMapping
  public DeskStartService.Routing routing(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestParam(required = false) String documentId) {
    return desk.routing(caseId, token, documentId);
  }

  /** A comparison, not a change: typed four-square numbers are used for this answer and not kept. */
  @PostMapping("/result")
  public DeskStartService.FirstResult result(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) DeskStartService.ResultRequest request) {
    return desk.result(caseId, token, request);
  }

  @RestControllerAdvice(assignableTypes = DeskStartController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(DeskStartProblem.class)
    ResponseEntity<ProblemDetail> problem(DeskStartProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("field", problem.field);
      return ResponseEntity.status(problem.status).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The numbers could not be read. Amounts are whole cents and the term is whole months.");
      body.setProperty("code", "DESK_START_UNREADABLE");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}
