package com.dealanalyzer.relay;

import java.util.List;
import java.util.Optional;

/**
 * Replaceable email relay provider boundary (AGT-03). The real provider is chosen under specification
 * section 18 prerequisite 10 and is not part of this build. Implementations must never log addresses,
 * subjects, bodies or provider payloads. Provider callbacks enter through {@link RelayService#receive} and
 * {@link RelayService#recordDelivery}.
 */
public interface DealerMailRelay {
  /**
   * Hands one rendered message to the provider and returns its message id; throws when not accepted. The
   * idempotency key is fixed when the message is first recorded: a provider that already accepted a message
   * under the key returns the original message id and does not send it again.
   */
  String send(String idempotencyKey, OutboundMail mail);

  /**
   * The provider message id when the provider has a message under this idempotency key, empty when it does
   * not. Never sends. Throws {@link RelayUnavailableException} when the provider cannot answer.
   */
  Optional<String> lookup(String idempotencyKey);

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
