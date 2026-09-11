package com.dealanalyzer.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Credentials are deployment secrets, never case credentials or caller-supplied actor names. */
@Component
@ConfigurationProperties(prefix = "operations")
public class OperatorAccounts {
  private Map<String, Account> accounts = new HashMap<>();
  public Map<String, Account> getAccounts() { return accounts; }
  public void setAccounts(Map<String, Account> accounts) { this.accounts = accounts; }
  public static class Account {
    private String token = "";
    private Role role;
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
  }
  public enum Role { SUPPORT, REVIEWER }
  public record Principal(String id, Role role) {}
  public Principal authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) return null;
    byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
    for (var entry : accounts.entrySet()) {
      var account = entry.getValue();
      if (account.token.length() >= 32 && account.role != null && entry.getKey().matches("[a-zA-Z0-9_-]{1,64}")
          && MessageDigest.isEqual(account.token.getBytes(StandardCharsets.UTF_8), supplied)) {
        return new Principal(entry.getKey(), account.role);
      }
    }
    return null;
  }
}
