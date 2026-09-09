package com.dealanalyzer.api;

import com.dealanalyzer.review.DealReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/deal-reviews")
public class DealReviewController {
  private final DealReviewService reviews;
  public DealReviewController(DealReviewService reviews) { this.reviews = reviews; }
  @GetMapping("/latest")
  public JsonNode latest(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return reviews.get(caseId, token, null);
  }
  @GetMapping("/{version}")
  public JsonNode version(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @PathVariable int version) {
    return reviews.get(caseId, token, version);
  }
}
