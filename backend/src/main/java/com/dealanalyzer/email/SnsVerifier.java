package com.dealanalyzer.email;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnsVerifier {
  private final Set<String> topics;
  private final SnsTransport transport;
  public SnsVerifier(@Value("${email.ses.topic-arns:}") String topics, SnsTransport transport) {
    this.topics = Arrays.stream(topics.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    if (this.topics.stream().anyMatch(t -> !validTopic(t))) throw new IllegalArgumentException("Invalid SES SNS topic configuration");
    this.transport = transport;
  }
  public static boolean validTopic(String topic) {
    return topic.matches("arn:aws:sns:[a-z]{2}-[a-z]+-[0-9]+:[0-9]{12}:[A-Za-z0-9_-]{1,256}");
  }
  public void verify(JsonNode envelope) throws Exception {
    String topic = required(envelope, "TopicArn");
    if (!topics.contains(topic)) throw new IllegalArgumentException("Topic refused");
    String type = required(envelope, "Type");
    if (!type.equals("Notification") && !type.equals("SubscriptionConfirmation")) throw new IllegalArgumentException("Type refused");
    if (type.equals("SubscriptionConfirmation")) confirmationUrl(envelope);
    URI certificate = snsUrl(required(envelope, "SigningCertURL"), topic);
    if (!certificate.getPath().matches("/SimpleNotificationService-[A-Za-z0-9_-]+\\.pem") || certificate.getRawQuery() != null)
      throw new IllegalArgumentException("Certificate refused");
    String algorithm = switch (required(envelope, "SignatureVersion")) {
      case "1" -> "SHA1withRSA"; // AWS SNS signature version 1 protocol compatibility.
      case "2" -> "SHA256withRSA";
      default -> throw new IllegalArgumentException("Signature version refused");
    };
    byte[] content = canonical(envelope).getBytes(StandardCharsets.UTF_8);
    byte[] signature = Base64.getDecoder().decode(required(envelope, "Signature"));
    var verifier = Signature.getInstance(algorithm);
    verifier.initVerify(transport.certificate(certificate));
    verifier.update(content);
    if (!verifier.verify(signature))
      throw new IllegalArgumentException("Signature refused");
  }
  public void confirm(JsonNode envelope) throws Exception {
    // Validate the signed URL too, but construct the action ourselves from signed fields.
    URI url = confirmationUrl(envelope);
    transport.confirm(URI.create("https://" + url.getHost() + "/?Action=ConfirmSubscription&TopicArn="
        + encode(required(envelope, "TopicArn")) + "&Token=" + encode(required(envelope, "Token"))));
  }
  private static URI confirmationUrl(JsonNode envelope) {
    URI url = snsUrl(required(envelope, "SubscribeURL"), required(envelope, "TopicArn"));
    if (!url.getPath().equals("/")) throw new IllegalArgumentException("Confirmation refused");
    return url;
  }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static URI snsUrl(String value, String topic) {
    URI uri = URI.create(value);
    String host = "sns." + topic.split(":")[3] + ".amazonaws.com";
    if (!"https".equals(uri.getScheme()) || !host.equals(uri.getHost()) || uri.getUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null)
      throw new IllegalArgumentException("SNS URL refused");
    return uri;
  }
  static String canonical(JsonNode envelope) {
    var fields = required(envelope, "Type").equals("Notification")
        ? new String[]{"Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type"}
        : new String[]{"Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type"};
    var result = new StringBuilder();
    for (String field : fields) {
      if (field.equals("Subject") && !envelope.has(field)) continue;
      result.append(field).append('\n').append(required(envelope, field)).append('\n');
    }
    return result.toString();
  }
  static String required(JsonNode node, String name) {
    if (node == null) throw new IllegalArgumentException("Invalid SNS document");
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual() || value.asText().isEmpty()) throw new IllegalArgumentException("Invalid SNS field");
    return value.asText();
  }
}
