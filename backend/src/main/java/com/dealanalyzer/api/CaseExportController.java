package com.dealanalyzer.api;
import com.dealanalyzer.buyingcase.persistence.CaseExportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.ResponseEntity;
@RestController @RequestMapping("/api/v1/cases/{caseId}/exports") @Validated
public class CaseExportController {
  private final CaseExportService service;
  public CaseExportController(CaseExportService service) { this.service=service; }
  public record Request(@NotNull CaseExportService.Format format, @Min(0) int reportVersion) {}
  @PostMapping
  public CaseExportService.Job create(@PathVariable String caseId, @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key, @Valid @RequestBody Request request) {
    return service.create(caseId,token,key,request.format(),request.reportVersion());
  }
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> download(@PathVariable String caseId,@PathVariable String id,@RequestHeader("Case-Access-Token") String token) {
    var result=service.download(caseId,token,id);
    String type=switch(result.format()) { case ZIP -> "application/zip"; case HTML -> "text/html;charset=UTF-8"; case PDF -> "application/pdf"; };
    return ResponseEntity.ok().header("Content-Type",type).header("Cache-Control","no-store")
        .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; sandbox")
        .header("Content-Disposition","attachment; filename=buying-case-" + id + "." + result.format().name().toLowerCase(java.util.Locale.ROOT))
        .body(result.bytes());
  }
}
