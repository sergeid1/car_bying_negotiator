package com.dealanalyzer.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** One cookie and request-header boundary shared by all account-authenticated endpoints. */
@Component
public class AccountSessionResolver implements HandlerMethodArgumentResolver, HandlerInterceptor, WebMvcConfigurer {
  private final BuyerAccountService accounts;
  private final AccountDeletionService deletion;
  private final AccountSessionCookie cookies;

  AccountSessionResolver(BuyerAccountService accounts, AccountDeletionService deletion, AccountSessionCookie cookies) {
    this.accounts = accounts; this.deletion = deletion; this.cookies = cookies;
  }

  @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) { resolvers.add(this); }
  @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this); }
  @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)
        || !java.util.Arrays.stream(method.getMethodParameters()).anyMatch(this::supportsParameter)) return true;
    String token = token(request);
    if (token == null) return true;
    boolean receiptRead = request.getMethod().equals("GET") && request.getRequestURI().equals("/api/v1/account/deletion");
    if (!receiptRead && !accounts.sessionActive(token) && !deletion.hasPendingReceipt(token)) cookies.clear(response);
    if (Set.of("POST", "PUT", "DELETE").contains(request.getMethod()) && !"1".equals(request.getHeader("Account-Request")))
      throw new AccountProblem(HttpStatus.FORBIDDEN, "ACCOUNT_REQUEST_REQUIRED",
          "This account request needs the Account-Request header. Reload the page and retry.");
    return true;
  }
  @Override public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(AccountSession.class) && parameter.getParameterType() == String.class;
  }
  @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
      NativeWebRequest webRequest, WebDataBinderFactory binder) {
    return token(webRequest.getNativeRequest(HttpServletRequest.class));
  }

  private String token(HttpServletRequest request) {
    if (request.getCookies() != null) for (var cookie : request.getCookies())
      if (AccountSessionCookie.NAME.equals(cookie.getName())) return cookie.getValue();
    return null;
  }
}
