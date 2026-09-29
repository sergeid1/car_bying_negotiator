package com.dealanalyzer.api;

import com.dealanalyzer.buyingcase.AnalysisWorkflow;
import com.dealanalyzer.buyingcase.AnalysisWorkflow.StartedAnalysis;
import com.dealanalyzer.buyingcase.SyntheticIntakeAffordance;
import com.dealanalyzer.buyingcase.SyntheticAnalysisRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixture-backed intake, kept out of the Buyer-facing path. The route is registered only when the
 * affordance is switched on, so a release that turns it off does not answer this path at all
 * rather than answering it differently.
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@ConditionalOnProperty(name = "intake.synthetic.enabled", matchIfMissing = true)
class SyntheticIntakeController {
  private final AnalysisWorkflow workflow;

  SyntheticIntakeController(AnalysisWorkflow workflow, SyntheticIntakeAffordance affordance) {
    this.workflow = workflow;
    affordance.requireEnabled();
  }

  @PostMapping("/cases/synthetic")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public StartedAnalysis start(
      @NotBlank @Size(max = 200) @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "Case-Access-Token", required = false) String accessToken,
      @Valid @RequestBody SyntheticAnalysisRequest request) {
    return workflow.start(request, idempotencyKey, accessToken);
  }
}
