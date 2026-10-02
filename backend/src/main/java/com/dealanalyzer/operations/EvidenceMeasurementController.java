package com.dealanalyzer.operations;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/operator/evidence")
class EvidenceMeasurementController {
  private final EvidenceMeasurementService measurements;

  EvidenceMeasurementController(EvidenceMeasurementService measurements) {
    this.measurements = measurements;
  }

  @GetMapping("/metrics")
  Object metrics(@RequestAttribute("operator") OperatorAccounts.Principal actor,
      @RequestParam Instant from, @RequestParam Instant to) {
    if (actor.role() != OperatorAccounts.Role.SUPPORT) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    if (!from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Window must have from before to");
    }
    return measurements.snapshot(from, to);
  }
}
