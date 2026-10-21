package com.dealanalyzer.negotiation;

import com.dealanalyzer.ai.ModelHarness;
import com.dealanalyzer.ai.ModelProvenance;
import com.dealanalyzer.ai.ModelTask;
import com.dealanalyzer.ai.ReplyDraftTask;
import com.dealanalyzer.ai.UntrustedInput;
import com.dealanalyzer.operations.ProviderMeter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds a reply suggestion for one Dealer answer against one finalized report. The model path runs
 * only through {@link ModelHarness}, which is off until the rollout gate's obligations are met; any
 * unavailability, failure or rejected output falls back to the template, and the result says which
 * producer made it. Nothing here writes anything: calculations, approvals, limits and review state
 * are read from the report and cannot be changed by generated text.
 */
@Service
public class ReplyAssistant {
  public static final int MAX_DEALER_RESPONSE = 4000;

  /** A Dealer answer the Buyer must correct; the message never quotes it. */
  public static final class InvalidDealerResponse extends RuntimeException {
    InvalidDealerResponse(String message) { super(message); }
  }

  private final ModelHarness harness;
  private final ModelTask<ReplyDraftTask.Output> task;
  private final ObjectMapper json;

  public ReplyAssistant(ModelHarness harness, ObjectMapper json) {
    this.harness = harness;
    this.task = ReplyDraftTask.task();
    this.json = json;
  }

  public ReplyDraft draft(String caseId, JsonNode report, String dealerResponse) {
    return draft(caseId, report, dealerResponse, "en");
  }

  public ReplyDraft draft(String caseId, JsonNode report, String dealerResponse, String language) {
    if (dealerResponse == null || dealerResponse.isBlank())
      throw new InvalidDealerResponse("Paste the Dealer's response to draft a reply.");
    if (dealerResponse.length() > MAX_DEALER_RESPONSE)
      throw new InvalidDealerResponse("The Dealer's response is limited to " + MAX_DEALER_RESPONSE + " characters.");
    ReplyGrounds grounds = ReplyGrounds.from(report);
    // Spanish readers receive the bilingual template bank's exact English draft and its complete reading copy.
    if ("es".equals(language) || !harness.available()) return template(grounds, "MODEL_PATH_OFF");
    try {
      var result = harness.run(caseId, task, List.of(
          new UntrustedInput.Text("dealer response", dealerResponse),
          new UntrustedInput.Text("recorded facts", facts(grounds))), 1);
      var output = result.value();
      var checked = ReplyCheck.check(grounds, new ReplyCheck.Candidate(output.opening(), output.factIds(),
          output.questions(), output.proposedSellingPriceMinor(), output.closing()));
      return draft(grounds, ReplyDraft.Path.MODEL, null, checked, result.provenance());
    } catch (ProviderMeter.Failure failure) {
      return template(grounds, failure.status().name());
    } catch (ReplyCheck.Rejected rejected) {
      return template(grounds, rejected.reason());
    }
  }

  ReplyDraft template(ReplyGrounds grounds, String reason) {
    // The template is checked by the same gate; a failure here is a product defect, not a fallback.
    return draft(grounds, ReplyDraft.Path.TEMPLATE, reason,
        ReplyCheck.check(grounds, GroundedReply.template(grounds)), null);
  }

  private static ReplyDraft draft(ReplyGrounds grounds, ReplyDraft.Path path, String reason,
      ReplyCheck.Checked checked, ModelProvenance provenance) {
    return new ReplyDraft(GroundedReply.VERSION, path, reason, grounds.reportVersion(), grounds.dealerOfferId(),
        grounds.dealerOfferVersion(), checked.statements(), GroundedReply.text(checked.statements()),
        checked.omitted(), checked.proposal(), provenance, GroundedReply.SCOPE);
  }

  /** The citable facts and the approved counter. The ceiling itself is enforced in code, not sent. */
  private String facts(ReplyGrounds grounds) {
    ObjectNode root = json.createObjectNode();
    var facts = root.putArray("facts");
    grounds.facts().forEach(fact -> facts.addObject().put("id", fact.id()).put("statement", fact.statement()));
    Long counter = GroundedReply.templateCounter(grounds);
    if (counter == null) root.putNull("counter_selling_price_minor");
    else root.put("counter_selling_price_minor", counter);
    return root.toString();
  }
}
