package com.dealanalyzer.relay;

import java.util.List;

/**
 * Replaceable email relay provider boundary (AGT-03). The real provider is chosen under specification
 * section 18 prerequisite 10 and is not part of this build. Implementations must never log addresses,
 * subjects, bodies or provider payloads. Provider callbacks enter through {@link RelayService#receive} and
 * {@link RelayService#recordDelivery}.
 */
public interface DealerMailRelay {
  /** Hands one rendered message to the provider and returns its message id; throws when not accepted. */
  String send(OutboundMail mail);

  /** The domain relay addresses are issued under. */
  String domain();

  record OutboundMail(String from, String to, String subject, String body, String listUnsubscribe) {
    @Override public String toString() { return "OutboundMail[redacted]"; }
  }

  record InboundMail(String providerMessageId, String to, String from, String subject, String body,
      List<Attachment> attachments) {
    public InboundMail { attachments = attachments == null ? List.of() : List.copyOf(attachments); }
    @Override public String toString() { return "InboundMail[redacted]"; }
  }

  record Attachment(String filename, byte[] content) {
    @Override public String toString() { return "Attachment[redacted]"; }
  }

  /** Thrown when the provider did not accept the message. Carries no provider detail. */
  final class RelayUnavailableException extends RuntimeException {
    public RelayUnavailableException() { super("Dealer email relay is unavailable"); }
  }
}
