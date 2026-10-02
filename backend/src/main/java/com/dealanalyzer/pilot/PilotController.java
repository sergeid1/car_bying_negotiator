package com.dealanalyzer.pilot;

import com.dealanalyzer.operations.OperatorAccounts;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/operator/pilot")
class PilotController {
  private final PilotService pilot;
  private final PilotMetrics metrics;
  private final AcquisitionChannels channels;
  PilotController(PilotService pilot, PilotMetrics metrics, AcquisitionChannels channels) {
    this.pilot = pilot; this.metrics = metrics; this.channels = channels;
  }
  @PostMapping("/freeze")
  Object freeze(@RequestAttribute("operator") OperatorAccounts.Principal actor, @Valid @RequestBody PilotService.Protocol request) {
    require(actor); return pilot.freeze(request, actor.id());
  }
  @GetMapping("/protocol")
  Object protocol(@RequestAttribute("operator") OperatorAccounts.Principal actor) { require(actor); return pilot.protocol(); }
  @PostMapping("/cases/{caseId}/enrollment")
  Object enroll(@PathVariable String caseId, @RequestAttribute("operator") OperatorAccounts.Principal actor,
      @Valid @RequestBody PilotService.Enrollment request) { require(actor); return pilot.enroll(caseId, actor.id()); }
  @PostMapping("/observations")
  Object observe(@RequestAttribute("operator") OperatorAccounts.Principal actor, @Valid @RequestBody PilotService.Observation request) {
    require(actor); return pilot.observe(request, actor.id());
  }
  @GetMapping("/metrics")
  Object metrics(@RequestAttribute("operator") OperatorAccounts.Principal actor) {
    require(actor); pilot.markReviewed(); return metrics.snapshot();
  }
  @GetMapping("/research")
  Object research(@RequestAttribute("operator") OperatorAccounts.Principal actor) {
    require(actor); pilot.markReviewed(); return metrics.research();
  }
  @GetMapping("/channels")
  Object channels(@RequestAttribute("operator") OperatorAccounts.Principal actor) { require(actor); return channels.channels(); }
  @PutMapping("/channels/{code}")
  Object configureChannel(@PathVariable String code, @RequestAttribute("operator") OperatorAccounts.Principal actor,
      @Valid @RequestBody AcquisitionChannels.Channel request) { require(actor); return channels.configure(code, request, actor.id()); }
  @PostMapping("/channels/{code}/spend")
  Object recordSpend(@PathVariable String code, @RequestAttribute("operator") OperatorAccounts.Principal actor,
      @Valid @RequestBody AcquisitionChannels.Spend request) { require(actor); return channels.recordSpend(code, request, actor.id()); }
  @GetMapping("/channels/metrics")
  Object channelMetrics(@RequestAttribute("operator") OperatorAccounts.Principal actor) {
    require(actor); pilot.markReviewed(); return metrics.channels();
  }
  private void require(OperatorAccounts.Principal actor) {
    if (actor.role() != OperatorAccounts.Role.SUPPORT) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }
}
