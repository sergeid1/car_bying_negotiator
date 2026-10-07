package com.dealanalyzer.mailbox;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A plain-text RFC 5322 message for Gmail's {@code users.messages.send}: the approved subject and body exactly,
 * UTF-8, with the subject as an RFC 2047 encoded word and the body base64 encoded, so no header can be injected
 * and no character is altered in transit. The provider adds Date and Message-ID.
 */
final class Rfc5322Message {
  private Rfc5322Message() {}

  static String render(String from, MailboxSender.OutgoingMessage message) {
    requireHeaderSafe(from); requireHeaderSafe(message.to()); requireHeaderSafe(message.subject());
    String body = message.body().replaceAll("\r?\n", "\r\n");
    String encoded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
        .encodeToString(body.getBytes(StandardCharsets.UTF_8));
    return "From: " + from + "\r\n"
        + "To: " + message.to() + "\r\n"
        + "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(message.subject().getBytes(StandardCharsets.UTF_8)) + "?=\r\n"
        + "MIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n"
        + "Content-Transfer-Encoding: base64\r\n"
        + "\r\n"
        + encoded + "\r\n";
  }

  private static void requireHeaderSafe(String value) {
    if (value == null || value.isBlank() || value.chars().anyMatch(c -> c == '\r' || c == '\n'))
      throw new IllegalArgumentException("A message header is invalid.");
  }
}
