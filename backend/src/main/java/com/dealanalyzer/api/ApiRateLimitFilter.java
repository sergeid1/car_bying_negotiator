package com.dealanalyzer.api;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-process backstop. An ingress must also enforce a shared limit across replicas. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class ApiRateLimitFilter extends OncePerRequestFilter {
  private final PeerQuota api;
  private final PeerQuota email;

  ApiRateLimitFilter(@Value("${security.rate-limit.requests:600}") int requests,
      @Value("${security.rate-limit.window:PT1M}") Duration window,
      @Value("${security.rate-limit.max-clients:10000}") int maxClients,
      @Value("${security.request-email.peer-requests:5}") int emailRequests,
      @Value("${security.request-email.peer-window:PT15M}") Duration emailWindow,
      @Value("${security.request-email.max-peers:10000}") int emailMaxPeers) {
    api = new PeerQuota(requests, window, maxClients);
    email = new PeerQuota(emailRequests, emailWindow, emailMaxPeers);
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (request.getServletPath().startsWith("/api/")) {
      // Never trust client-supplied forwarding headers or use attacker-selected tokens as keys.
      long now = System.nanoTime();
      long retry = api.acquire(request.getRemoteAddr(), now);
      if (retry == 0 && isPeerLimitedRequest(request)) retry = email.acquire(request.getRemoteAddr(), now);
      if (retry > 0) {
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":429,\"title\":\"Too many requests\",\"detail\":\"Wait before retrying this request.\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }

  /** Unauthenticated routes that send email, and the Google and Apple sign-in start and callback routes. */
  private static boolean isPeerLimitedRequest(HttpServletRequest request) {
    if ("GET".equals(request.getMethod())) return request.getServletPath().equals("/api/v1/account/social-sign-in/google/callback");
    if (!"POST".equals(request.getMethod())) return false;
    // The container decodes the servlet path and removes path parameters before routing.
    String path = request.getServletPath();
    return path.equals("/api/v1/account/sign-in-requests") || path.equals("/api/v1/recovery/requests")
        || path.matches("/api/v1/cases/[^/]+/recovery-email") || path.equals("/api/v1/account/social-sign-in")
        || path.equals("/api/v1/account/social-sign-in/apple/callback");
  }

  static final class PeerQuota {
    private final Map<String, Bucket> clients = new HashMap<>();
    private final int requests;
    private final int maxClients;
    private final long windowNanos;
    private long nextCleanup;

    PeerQuota(int requests, Duration window, int maxClients) {
      if (requests < 1 || maxClients < 1 || window.isNegative() || window.isZero())
        throw new IllegalArgumentException("Rate limits must be positive");
      this.requests = requests; this.maxClients = maxClients; this.windowNanos = window.toNanos();
    }

    synchronized long acquire(String client, long now) {
      if (nextCleanup == 0 || now - nextCleanup >= 0) {
        clients.values().removeIf(bucket -> now - bucket.started >= windowNanos);
        nextCleanup = now + windowNanos;
      }
      Bucket bucket = clients.get(client);
      if (bucket != null && now - bucket.started >= windowNanos) {
        clients.remove(client); bucket = null;
      }
      if (bucket == null) {
        if (clients.size() >= maxClients) return seconds(windowNanos);
        bucket = new Bucket(now); clients.put(client, bucket);
      }
      if (bucket.count >= requests) return seconds(windowNanos - (now - bucket.started));
      bucket.count++;
      return 0;
    }
  }

  private static long seconds(long nanos) { return Math.max(1, (nanos + 999_999_999) / 1_000_000_000); }
  private static final class Bucket {
    final long started;
    int count;
    Bucket(long started) { this.started = started; }
  }
}
