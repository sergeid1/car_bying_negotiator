package com.dealanalyzer.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class CasePortabilityPrivacyFilter extends OncePerRequestFilter {
  @org.springframework.beans.factory.annotation.Value("${recovery.restore-mode:false}")
  private boolean restoreMode;
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String path=request.getRequestURI();
    if(path.startsWith("/api/")) {
      response.setHeader("X-Correlation-Id", java.util.UUID.randomUUID().toString());
      response.setHeader("Cache-Control","no-store");
      response.setHeader("Referrer-Policy","no-referrer");
      response.setHeader("X-Content-Type-Options", "nosniff");
      response.setHeader("X-Frame-Options", "DENY");
      if (restoreMode) {
        response.setStatus(503);
        response.setHeader("Retry-After", "300");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":503,\"title\":\"Restore in progress\"}");
        return;
      }
    }
    chain.doFilter(request,response);
  }
}
