package com.dealanalyzer.relay;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The Buyer sends approved dealer messages from their own email app (ticket 82): read the exact approved text,
 * say it was sent or discard it, and add the dealer's reply to the Dealer Conversation.
 */
@RestController
@Validated
@RequestMapping("/api/v1/cases/{caseId}")
public class DealerMessageHandoffController {
  private final DealerMessageHandoff handoffs;
  public DealerMessageHandoffController(DealerMessageHandoff handoffs) { this.handoffs = handoffs; }

  @GetMapping("/handoffs/{messageId}")
  public DealerMessageHandoff.Handoff get(@PathVariable String caseId, @PathVariable String messageId,
      @RequestHeader("Case-Access-Token") String token) {
    return handoffs.get(caseId, token, messageId);
  }

  @PostMapping("/handoffs/{messageId}/sent")
  public DealerMessageHandoff.Handoff sent(@PathVariable String caseId, @PathVariable String messageId,
      @RequestHeader("Case-Access-Token") String token) {
    return handoffs.markSent(caseId, token, messageId);
  }

  @PostMapping("/handoffs/{messageId}/discard")
  public DealerMessageHandoff.Handoff discard(@PathVariable String caseId, @PathVariable String messageId,
      @RequestHeader("Case-Access-Token") String token) {
    return handoffs.discard(caseId, token, messageId);
  }

  @PostMapping(path = "/quote-requests/{id}/replies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public RelayService.InboundResult reply(@PathVariable String caseId, @PathVariable String id,
      @RequestHeader("Case-Access-Token") String token,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) String key,
      @RequestParam(name = "subject", required = false) String subject,
      @RequestParam(name = "text", required = false) String text,
      @RequestPart(name = "file", required = false) List<MultipartFile> files) {
    var attachments = new ArrayList<DealerMailRelay.Attachment>();
    if (files != null) for (MultipartFile file : files) {
      try { attachments.add(new DealerMailRelay.Attachment(file.getOriginalFilename(), file.getBytes())); }
      catch (IOException unreadable) { throw new IllegalArgumentException("An attachment could not be read."); }
    }
    return handoffs.addReply(caseId, token, id, key, subject, text, attachments);
  }

  @ExceptionHandler(DealerMessageHandoff.HandoffRefusedException.class)
  ProblemDetail refused(DealerMessageHandoff.HandoffRefusedException error) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, error.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalid(IllegalArgumentException failure) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, failure.getMessage());
  }
}
