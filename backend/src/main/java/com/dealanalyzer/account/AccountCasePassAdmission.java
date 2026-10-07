package com.dealanalyzer.account;

import com.dealanalyzer.buyingcase.CaseAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Admits an account case pass only on a request that carries the session cookie it was issued to,
 * while that session is active and the case is still attached to the session's account. Outside a
 * request, or without the cookie, a pass opens nothing.
 */
@Component
class AccountCasePassAdmission implements CaseAccessService.AccountPassAdmission {
  private final BuyerAccountService accounts;

  AccountCasePassAdmission(BuyerAccountService accounts) { this.accounts = accounts; }

  @Override
  public String admittedSessionHash(String caseId) {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
    String session = sessionCookie(attributes.getRequest());
    return session == null ? null : accounts.admittedSessionHash(session, caseId);
  }

  private static String sessionCookie(HttpServletRequest request) {
    if (request.getCookies() != null) for (var cookie : request.getCookies())
      if (AccountSessionCookie.NAME.equals(cookie.getName())) return cookie.getValue();
    return null;
  }
}
