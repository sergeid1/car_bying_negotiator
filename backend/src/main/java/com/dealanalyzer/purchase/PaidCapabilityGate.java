package com.dealanalyzer.purchase;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Entire report and review route families are gated, including future children. */
@Configuration
public class PaidCapabilityGate implements WebMvcConfigurer, HandlerInterceptor {
  private final PurchaseService purchases;
  public PaidCapabilityGate(PurchaseService purchases) { this.purchases = purchases; }
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/v1/cases/*/reports/**", "/api/v1/cases/*/deal-reviews/**",
        "/api/v1/cases/*/price-plans/**", "/api/v1/cases/*/approved-baselines/**",
        "/api/v1/cases/*/feedback/report-total/**", "/api/v1/cases/*/analyses",
        "/api/v1/cases/*/market-assessments");
  }
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getServletPath().endsWith("/market-assessments") && !request.getMethod().equals("POST")) return true;
    @SuppressWarnings("unchecked") var variables = (java.util.Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    purchases.requireReport(variables.get("caseId"), request.getHeader("Case-Access-Token"));
    return true;
  }
}
