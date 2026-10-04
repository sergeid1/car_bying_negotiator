package com.dealanalyzer.appointment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated
@RequestMapping("/api/v1/cases/{caseId}/appointment")
public class TestDriveController {
  private static final String SESSION = "Account-Session-Token";
  private final TestDriveService testDrives;
  public TestDriveController(TestDriveService testDrives) { this.testDrives = testDrives; }

  @GetMapping
  public TestDriveService.View view(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token) {
    return testDrives.view(caseId, token);
  }
  @GetMapping(value = "/appointments/{appointmentId}/calendar", produces = "text/calendar")
  public ResponseEntity<String> calendar(@PathVariable String caseId, @PathVariable String appointmentId,
      @RequestHeader("Case-Access-Token") String token) {
    return ResponseEntity.ok().contentType(new MediaType("text", "calendar", java.nio.charset.StandardCharsets.UTF_8))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=test-drive.ics")
        .header(HttpHeaders.CACHE_CONTROL, "no-store").body(testDrives.calendar(caseId, token, appointmentId));
  }
  @PostMapping("/proposals") @ResponseStatus(HttpStatus.CREATED)
  public TestDriveService.Message propose(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @RequestBody TestDriveService.Availability request) {
    return testDrives.propose(caseId, token, key, request);
  }
  @PostMapping("/bookings") @ResponseStatus(HttpStatus.CREATED)
  public TestDriveService.Message book(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader(value = SESSION, required = false) String session,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key, @RequestBody TestDriveService.Booking request) {
    return testDrives.book(caseId, token, session, key, request);
  }
  @PostMapping("/appointments/{appointmentId}/cancellation") @ResponseStatus(HttpStatus.CREATED)
  public TestDriveService.Message cancel(@PathVariable String caseId, @PathVariable String appointmentId,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key) {
    return testDrives.cancel(caseId, token, key, appointmentId);
  }
  @PostMapping("/messages/{id}/approval")
  public TestDriveService.Message approve(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token, @RequestHeader(value = SESSION, required = false) String session,
      @RequestBody TestDriveService.Approval approval) {
    return testDrives.approve(caseId, token, session, id, approval);
  }
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}
