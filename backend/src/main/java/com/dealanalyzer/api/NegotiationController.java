package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.persistence.NegotiationService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/reports/{version}/negotiation")
public class NegotiationController {
  private final NegotiationService service;
  public NegotiationController(NegotiationService service) { this.service = service; }
  @GetMapping
  public JsonNode get(@PathVariable String caseId, @PathVariable int version,
      @RequestHeader("Case-Access-Token") String token) {
    return service.get(caseId, version, token);
  }
}
