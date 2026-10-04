package com.dealanalyzer.quote;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/cases/{caseId}/quote-acceptance")
public class AgreedPriceController {
  private final AgreedPriceService prices;
  private final AgreedPriceSummary summary;
  public AgreedPriceController(AgreedPriceService prices, AgreedPriceSummary summary) { this.prices = prices; this.summary = summary; }
  @GetMapping(value = "/summary", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> summary(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=agreed-price.html")
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(summary.download(caseId, token));
  }
  @GetMapping
  public AgreedPriceService.View view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return prices.view(caseId, token);
  }
  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  public AgreedPriceService.Request prepare(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @RequestBody AgreedPriceService.Prepare request) {
    return prices.prepare(caseId, token, key, request);
  }
  @PostMapping("/{id}/approval")
  public AgreedPriceService.Request approve(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token, @RequestBody AgreedPriceService.Approval approval) {
    return prices.approve(caseId, token, id, approval);
  }
  @PostMapping("/other-negotiations/{otherCaseId}")
  public JourneyAgreement.Result chooseOther(@PathVariable String caseId, @PathVariable String otherCaseId,
      @RequestHeader("Case-Access-Token") String token, @RequestBody AgreedPriceService.OtherChoice choice) {
    return prices.chooseOther(caseId, token, otherCaseId, choice);
  }
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}
