package com.dealanalyzer.account;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narrow account boundary for releasing contact details to one dealer (ACCT-03, APPT-02). Only the
 * signed-in owner of a case may release, field by field. Values stay in memory and are returned only
 * for the fields the Buyer chose; callers record which fields were released, never the values.
 */
@Service
public class AccountContactRelease {
  public enum Field { FIRST_NAME, LAST_NAME, EMAIL, PHONE }

  private final BuyerAccountService service;
  private final BuyerAccountRepository accounts;
  private final AccountCaseRepository attachments;
  private final ContactProtection protection;

  AccountContactRelease(BuyerAccountService service, BuyerAccountRepository accounts,
      AccountCaseRepository attachments, ContactProtection protection) {
    this.service = service; this.accounts = accounts; this.attachments = attachments; this.protection = protection;
  }

  /**
   * Locks and returns the signed-in account when it owns the case. Call before taking the case lock,
   * matching the account-then-case order used when attaching cases.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public String requireOwner(String session, String caseId) {
    String accountId;
    try { accountId = service.requireSession(session).accountId; }
    catch (AccountProblem ended) { throw signIn(); }
    var account = accounts.findLockedById(accountId).orElseThrow(AccountContactRelease::signIn);
    if (!"ACTIVE".equals(account.lifecycle)) throw signIn();
    if (attachments.findById(caseId).filter(attached -> attached.accountId.equals(accountId)).isEmpty())
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,
          "Sign in with the Buyer Account that holds this Buying Case to choose contact details.");
    return accountId;
  }

  /** The fields the case's account holds, without their values. Empty for an unattached case. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Set<Field> available(String caseId) {
    var fields = EnumSet.noneOf(Field.class);
    contact(caseId).ifPresent(contact -> values(contact, EnumSet.allOf(Field.class)).keySet().forEach(fields::add));
    return fields;
  }

  /** The chosen values for the owning account, in field order. Never log or store the result. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<Field, String> values(String accountId, String caseId, Collection<Field> chosen) {
    if (attachments.findById(caseId).filter(attached -> attached.accountId.equals(accountId)).isEmpty())
      throw signIn();
    var account = accounts.findById(accountId).orElseThrow(AccountContactRelease::signIn);
    var released = values(protection.decrypt(account.id, account.contactCiphertext), chosen);
    if (released.size() != Set.copyOf(chosen).size())
      throw new IllegalArgumentException("A chosen contact detail is no longer on your Buyer Account. Choose again.");
    return released;
  }

  private Optional<Contact> contact(String caseId) {
    return attachments.findById(caseId).flatMap(attached -> accounts.findById(attached.accountId))
        .map(account -> protection.decrypt(account.id, account.contactCiphertext));
  }

  private static Map<Field, String> values(Contact contact, Collection<Field> chosen) {
    var values = new LinkedHashMap<Field, String>();
    for (Field field : Field.values()) {
      if (!chosen.contains(field)) continue;
      String value = switch (field) {
        case FIRST_NAME -> contact.firstName();
        case LAST_NAME -> contact.lastName();
        case EMAIL -> contact.email();
        case PHONE -> contact.phone();
      };
      if (value != null && !value.isBlank()) values.put(field, value.strip());
    }
    return values;
  }

  private static ResponseStatusException signIn() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to your Buyer Account to choose contact details.");
  }
}
