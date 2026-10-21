package com.dealanalyzer.api;
import com.dealanalyzer.buyingcase.persistence.CaseRecoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.Map;
@RestController @RequestMapping("/api/v1") @Validated
public class CaseRecoveryController {
  private final CaseRecoveryService service;
  public CaseRecoveryController(CaseRecoveryService service) { this.service=service; }
  public record EmailRequest(@NotBlank @Email @Size(max=254) String email, @Pattern(regexp="en|es") String language) {
    public EmailRequest(String email) { this(email, "en"); }
  }
  public record RecoverRequest(@NotBlank @Size(max=36) String caseId, @NotBlank @Email @Size(max=254) String email,
      @Pattern(regexp="en|es") String language) {
    public RecoverRequest(String caseId, String email) { this(caseId, email, "en"); }
  }
  public record VerifyRequest(@NotBlank @Size(max=128) String token) {}
  @PostMapping("/cases/{id}/recovery-email")
  public Map<String,String> enroll(@PathVariable String id, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key, @Valid @RequestBody EmailRequest request) {
    service.enroll(id, token, request.email(), key); return accepted();
  }
  @PostMapping("/recovery/requests")
  public Map<String,String> request(@RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key,
      @Valid @RequestBody RecoverRequest request) {
    // Public acknowledgements must not reveal whether an address matched, even if SMTP fails.
    try { service.request(request.caseId(), request.email(), key); }
    catch (RuntimeException unavailable) { /* The transaction rolls back; the same request can retry. */ }
    return accepted();
  }
  @PostMapping("/recovery/verify")
  public CaseRecoveryService.Recovered verify(@Valid @RequestBody VerifyRequest request) { return service.verify(request.token()); }
  private Map<String,String> accepted() { return Map.of("status", "If recovery is available, an access link will be emailed."); }
}
