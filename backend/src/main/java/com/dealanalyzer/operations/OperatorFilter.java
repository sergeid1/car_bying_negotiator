package com.dealanalyzer.operations;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class OperatorFilter extends OncePerRequestFilter {
  private final OperatorAccounts accounts;
  OperatorFilter(OperatorAccounts accounts) { this.accounts = accounts; }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getServletPath().startsWith("/api/v1/operator")) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
      var principal = accounts.authenticate(request.getHeader("Authorization"));
      if (principal == null) { response.setStatus(401); return; }
      request.setAttribute("operator", principal);
    }
    chain.doFilter(request, response);
  }
}
