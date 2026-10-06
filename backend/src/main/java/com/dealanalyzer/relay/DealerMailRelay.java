package com.dealanalyzer.relay;

import java.util.List;

/**
 * Replaceable provider boundary for each case's private reply-intake address (spec 2.1 section 2.2). It only
 * receives: the platform never sends email to a dealer (ticket 84). A real inbound provider waits on ticket 78's
 * inbound mail authentication prerequisite. Implementations must never log addresses, subjects, bodies or
 * provider payloads. Received mail enters through {@link RelayService#receive}.
 */
public interface DealerMailRelay {
  /** The domain reply-intake addresses are issued under. */
  String domain();

  /**
   * True when mail sent to a reply-intake address can actually arrive: an authenticated inbound provider is
   * enabled. Until then the Buyer is told to paste replies instead of forwarding them.
   */
  default boolean receivesMail() { return false; }

  record InboundMail(String providerMessageId, String to, String from, String subject, String body,
      List<Attachment> attachments) {
    public InboundMail { attachments = attachments == null ? List.of() : List.copyOf(attachments); }
    @Override public String toString() { return "InboundMail[redacted]"; }
  }

  record Attachment(String filename, byte[] content) {
    @Override public String toString() { return "Attachment[redacted]"; }
  }
}
