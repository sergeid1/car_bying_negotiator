package com.dealanalyzer.relay;

import com.dealanalyzer.buyingcase.persistence.CaseExportContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Adds the case's retained relay messages, decrypted, to the case export (and so the account export). */
@Component
class RelayExport implements CaseExportContent {
  private final RelayService relay;
  private final ObjectMapper json;
  RelayExport(RelayService relay, ObjectMapper json) { this.relay = relay; this.json = json; }

  @Override public List<File> files(String caseId) {
    var messages = relay.retainedContent(caseId);
    if (messages.isEmpty()) return List.of();
    try {
      return List.of(new File("relay/messages.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(messages)));
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Relay export unavailable");
    }
  }
}
