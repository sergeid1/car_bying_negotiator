package com.dealanalyzer.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
@Component
class CasePortabilityPrivacyFilter extends OncePerRequestFilter {
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    String path=request.getRequestURI();
    if(path.startsWith("/api/v1/recovery/") || path.startsWith("/api/v1/cases/")
        && (path.contains("/exports") || path.endsWith("/recovery-email"))) {
      response.setHeader("Cache-Control","no-store");
      response.setHeader("Referrer-Policy","no-referrer");
    }
    chain.doFilter(request,response);
  }
}
