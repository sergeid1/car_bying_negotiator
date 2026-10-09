package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Per-process guessing defense, in addition to the shared API rate limiter. Keys are keyed hashes. */
@Component
class PasswordAttempts {
  private static final long WINDOW = Duration.ofHours(1).toNanos();
  private final Map<String, Counter> counters = new HashMap<>();
  private final CaseAccessService secrets;
  private static class Counter { long started, next; int attempts; Counter(long now) { started = now; } }
  PasswordAttempts(CaseAccessService secrets) { this.secrets = secrets; }

  boolean acquire(String email, String address) { return acquire(email, address, System.nanoTime()); }

  synchronized boolean acquire(String email, String address, long now) {
    counters.entrySet().removeIf(entry -> now - entry.getValue().started >= WINDOW);
    String account = secrets.protectAccountSecret("password-account-limit", email);
    String client = secrets.protectAccountSecret("password-client-limit", address);
    // Never evict live counters: exhausting capacity fails closed instead of bypassing backoff.
    if (counters.size() >= 20000 && (!counters.containsKey(account) || !counters.containsKey(client))) return false;
    Counter a = counters.computeIfAbsent(account, ignored -> new Counter(now));
    Counter c = counters.computeIfAbsent(client, ignored -> new Counter(now));
    if (now < a.next || now < c.next) return false;
    reserve(a, now, 5); reserve(c, now, 20);
    return true;
  }
  private static void reserve(Counter counter, long now, int free) {
    counter.attempts++;
    if (counter.attempts >= free) counter.next = now + Duration.ofSeconds(
        Math.min(900, 1L << Math.min(10, counter.attempts - free))).toNanos();
  }
}
