package com.dealanalyzer.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {
  private static final String SECRET = "b".repeat(64);
  private final ClientAddress clients = new ClientAddress(SECRET);

  private static MockHttpServletRequest request(String peer, String verify, String... visitors) {
    var request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    if (verify != null) request.addHeader(ClientAddress.VERIFY, verify);
    for (String visitor : visitors) request.addHeader(ClientAddress.HEADER, visitor);
    request.addHeader("X-Forwarded-For", "198.51.100.200");
    request.addHeader("Forwarded", "for=198.51.100.200");
    return request;
  }

  @Test void onlyAVerifiedRequestIsKeyedByTheVisitorAddress() {
    assertThat(clients.of(request("127.0.0.1", SECRET, "203.0.113.5"))).isEqualTo("203.0.113.5");
    assertThat(clients.of(request("127.0.0.1", null, "203.0.113.5"))).isEqualTo("127.0.0.1");
    assertThat(clients.of(request("127.0.0.1", "c".repeat(64), "203.0.113.5"))).isEqualTo("127.0.0.1");
    assertThat(clients.of(request("127.0.0.1", SECRET.toUpperCase(), "203.0.113.5"))).isEqualTo("127.0.0.1");
    assertThat(new ClientAddress("").of(request("127.0.0.1", SECRET, "203.0.113.5"))).isEqualTo("127.0.0.1");
    // Forwarding headers never select the key, verified or not.
    assertThat(clients.of(request("127.0.0.1", SECRET))).isEqualTo("127.0.0.1");
  }

  @Test void aDuplicatedOrMalformedVisitorAddressFallsBackToThePeer() {
    for (String visitor : new String[] {"", "203.0.113.5, 192.0.2.1", "203.0.113.256", "01.2.3.4", "example.com",
        "localhost", ".:1", "fe80::1%en0", "[2001:db8::1]", "2001:db8::1::2", "1".repeat(200)})
      assertThat(clients.of(request("127.0.0.1", SECRET, visitor))).as(visitor).isEqualTo("127.0.0.1");
    assertThat(clients.of(request("127.0.0.1", SECRET, "203.0.113.5", "192.0.2.1"))).isEqualTo("127.0.0.1");
    var twice = request("127.0.0.1", SECRET, "203.0.113.5");
    twice.addHeader(ClientAddress.VERIFY, SECRET);
    assertThat(clients.of(twice)).isEqualTo("127.0.0.1");
  }

  @Test void ipv6AddressesShareTheirSlash64AndMappedAddressesAreIpv4() {
    String host = clients.of(request("127.0.0.1", SECRET, "2001:db8:1:2::1"));
    assertThat(host).isEqualTo("2001:db8:1:2::/64");
    assertThat(clients.of(request("127.0.0.1", SECRET, "2001:DB8:1:2:ffff:ffff:ffff:ffff"))).isEqualTo(host);
    assertThat(clients.of(request("127.0.0.1", SECRET, "2001:db8:1:3::1"))).isNotEqualTo(host);
    assertThat(clients.of(request("127.0.0.1", SECRET, "::ffff:203.0.113.5"))).isEqualTo("203.0.113.5");
    // A direct IPv6 peer is bucketed the same way.
    assertThat(clients.of(request("2001:db8:1:2:0:0:0:9", null))).isEqualTo(host);
  }

  @Test void aMalformedSecretRefusesStartup() {
    for (String secret : new String[] {"weak", "B".repeat(64), "b".repeat(63)})
      assertThatThrownBy(() -> new ClientAddress(secret)).hasMessageNotContaining(secret)
          .hasMessage("security.client-address.origin-secret must be 32 random bytes encoded as hex");
  }
}
