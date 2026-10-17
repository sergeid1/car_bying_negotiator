package com.dealanalyzer.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one per-client quota key (ticket 136), shared by the API, request-email, password and Toto limits so they
 * cannot disagree. Behind the Cloudflare Worker and the pilot proxy the network peer is the proxy, so a request that
 * carries the origin verification secret is keyed by the visitor address the Worker set from Cloudflare's own
 * connecting address. Every other request is keyed by its network peer. Forwarding headers are never read. IPv6
 * addresses are keyed by their /64 prefix so one host cannot rotate through its own block. The key lives only in the
 * in-memory quotas: it is never stored, logged or exported.
 */
@Component
public class ClientAddress {
  /** Set by the Worker after deleting any client copy; passed by the pilot proxy only on verified requests. */
  public static final String HEADER = "X-Otdog-Client-Address";
  static final String VERIFY = "X-Origin-Verify";
  private static final Pattern IPV4 = Pattern.compile(
      "(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
  // A leading hex digit or colon and an embedded colon keep InetAddress on its literal parser, never DNS.
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]{1,44}");
  private final byte[] secret;

  ClientAddress(@Value("${security.client-address.origin-secret:}") String secret) {
    if (!secret.isEmpty() && !secret.matches("[a-f0-9]{64}"))
      throw new IllegalStateException("security.client-address.origin-secret must be 32 random bytes encoded as hex");
    this.secret = secret.isEmpty() ? null : secret.getBytes(StandardCharsets.US_ASCII);
  }

  /** The quota key for this request: the verified visitor address, else the network peer. */
  public String of(HttpServletRequest request) {
    if (secret != null) {
      String verify = single(request.getHeaders(VERIFY));
      if (verify != null && MessageDigest.isEqual(secret, verify.getBytes(StandardCharsets.UTF_8))) {
        String visitor = key(single(request.getHeaders(HEADER)));
        if (visitor != null) return visitor;
      }
    }
    String peer = request.getRemoteAddr();
    String key = key(peer);
    return key == null ? peer : key;
  }

  /** A normalized literal address, an IPv6 address's /64 prefix, or null for anything that is not one address. */
  static String key(String literal) {
    if (literal == null) return null;
    boolean v4 = IPV4.matcher(literal).matches();
    if (!v4 && !(literal.indexOf(':') >= 0 && IPV6.matcher(literal).matches())) return null;
    InetAddress address;
    try { address = InetAddress.getByName(literal); } catch (Exception invalid) { return null; }
    if (!(address instanceof Inet6Address)) return address.getHostAddress();
    byte[] bytes = address.getAddress();
    var prefix = new StringBuilder();
    for (int i = 0; i < 8; i += 2) prefix.append(Integer.toHexString((bytes[i] & 0xff) << 8 | bytes[i + 1] & 0xff)).append(':');
    return prefix.append(":/64").toString();
  }

  private static String single(Enumeration<String> values) {
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    return values.hasMoreElements() ? null : value;
  }
}
