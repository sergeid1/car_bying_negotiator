package com.dealanalyzer.vehicle.safety;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/vehicle-safety")
public class VehicleSafetyController {
  private final VehicleSafetyService service;
  public VehicleSafetyController(VehicleSafetyService service) { this.service = service; }
  @GetMapping public VehicleSafetyService.View get(@PathVariable String caseId,
      @RequestHeader("Case-Access-Token") String token) { return service.get(caseId, token); }
}
