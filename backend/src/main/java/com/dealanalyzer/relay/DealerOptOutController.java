package com.dealanalyzer.relay;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The dealer-facing opt-out link in every relay footer. Opening the link only shows a confirmation form,
 * so mail scanners that prefetch links cannot opt a dealer out; the POST (also RFC 8058 one-click) does.
 */
@RestController
@RequestMapping("/api/v1/relay/opt-out/{token}")
public class DealerOptOutController {
  private static final String PAGE = """
      <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Stop messages</title></head><body style="font-family:system-ui,sans-serif;max-width:40rem;margin:2rem auto;padding:0 1rem">
      <h1>Stop messages from this Deal X-Ray address</h1>%s</body></html>""";
  private final RelayService relay;
  public DealerOptOutController(RelayService relay) { this.relay = relay; }

  @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
  public String confirm() {
    return PAGE.formatted("<p>A Buyer is using Deal X-Ray to contact you. Confirm to stop every future message "
        + "from this relay address. This cannot be undone.</p><form method=\"post\"><button type=\"submit\">Stop messages</button></form>");
  }

  @PostMapping(produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<String> optOut(@PathVariable String token) {
    if (!relay.optOutByLink(token)) return ResponseEntity.status(404).body(PAGE.formatted("<p>This link is not recognized.</p>"));
    return ResponseEntity.ok(PAGE.formatted("<p>Done. This relay address will not send you any more messages.</p>"));
  }
}
