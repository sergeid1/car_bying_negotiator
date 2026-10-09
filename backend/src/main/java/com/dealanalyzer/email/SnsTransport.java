package com.dealanalyzer.email;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** URLs have already passed the verifier's exact SNS host allowlist. Never follow redirects. */
@Component
public class SnsTransport {
  public static class Unavailable extends Exception {
    Unavailable() { super("SNS transport unavailable"); }
  }
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  public PublicKey certificate(URI uri) throws Exception {
    byte[] bytes = get(uri, 16384);
    var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
        .generateCertificate(new java.io.ByteArrayInputStream(bytes));
    certificate.checkValidity();
    return certificate.getPublicKey();
  }
  public void confirm(URI uri) throws Exception { get(uri, 16384); }
  private byte[] get(URI uri, int limit) throws Exception {
    try {
      var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
      try (var body = response.body()) {
        if (response.statusCode() != 200) throw new Unavailable();
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) throw new Unavailable();
        return bytes;
      }
    } catch (java.io.IOException failure) { throw new Unavailable(); }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new Unavailable();
    }
  }
}
