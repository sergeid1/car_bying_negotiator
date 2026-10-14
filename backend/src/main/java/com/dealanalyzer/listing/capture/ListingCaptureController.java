package com.dealanalyzer.listing.capture;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Buyer's own capture of a dealer's listing: upload a screenshot or a saved PDF of the page, or paste its
 * text, then confirm what it shows. The platform never fetches the listing itself.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/listing-captures")
public class ListingCaptureController {
  private final ListingCaptureService captures;

  ListingCaptureController(ListingCaptureService captures) { this.captures = captures; }

  @GetMapping
  public ListingCaptureService.View view(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return captures.view(caseId, token);
  }

  @PostMapping(consumes = "multipart/form-data") @ResponseStatus(HttpStatus.ACCEPTED)
  public ListingCaptureService.View upload(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestPart(value = "file", required = false) MultipartFile file) {
    return captures.upload(caseId, token, key, file);
  }

  @PostMapping("/text") @ResponseStatus(HttpStatus.ACCEPTED)
  public ListingCaptureService.View paste(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody(required = false) ListingCaptureService.TextInput input) {
    return captures.paste(caseId, token, key, input);
  }

  @PostMapping("/confirmation")
  public ListingCaptureService.View confirm(@PathVariable String caseId,
      @RequestHeader(value = "Case-Access-Token", required = false) String token,
      @RequestBody(required = false) ListingCaptureService.Confirmation input) {
    return captures.confirm(caseId, token, input);
  }

  @GetMapping("/{documentId}/pages/{pageNumber}/image")
  public ResponseEntity<byte[]> page(@PathVariable String caseId, @PathVariable String documentId,
      @PathVariable int pageNumber, @RequestHeader(value = "Case-Access-Token", required = false) String token) {
    return ResponseEntity.ok().header("Content-Type", "image/png").header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store").body(captures.page(caseId, token, documentId, pageNumber));
  }

  @RestControllerAdvice(assignableTypes = ListingCaptureController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  static class Problems {
    @ExceptionHandler(ListingCaptureProblem.class)
    ResponseEntity<ProblemDetail> problem(ListingCaptureProblem problem) {
      var body = ProblemDetail.forStatusAndDetail(problem.status, problem.getMessage());
      body.setProperty("code", problem.code);
      body.setProperty("field", problem.field);
      return ResponseEntity.status(problem.status).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ignored) {
      var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
          "The listing details could not be read. The price is whole cents and the date is YYYY-MM-DD.");
      body.setProperty("code", "LISTING_CAPTURE_UNREADABLE");
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
  }
}
