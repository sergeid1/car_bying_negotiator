package com.dealanalyzer.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow account boundary: notifications use the encrypted, verified contact under the account lock. */
@Service
public class AccountNotificationAccess {
  private final BuyerAccountService service;
  private final BuyerAccountRepository accounts;
  private final ContactProtection protection;

  AccountNotificationAccess(BuyerAccountService service, BuyerAccountRepository accounts, ContactProtection protection) {
    this.service = service; this.accounts = accounts; this.protection = protection;
  }

  @Transactional(readOnly = true)
  public String accountId(String session) { return service.requireSession(session).accountId; }

  @Transactional(propagation = Propagation.MANDATORY)
  public String lockRecipient(String accountId) {
    var account = accounts.findLockedById(accountId).orElse(null);
    if (account == null || !"ACTIVE".equals(account.lifecycle) || account.verifiedAt == null) return null;
    return protection.decrypt(account.id, account.contactCiphertext).email();
  }

  /** The language the Buyer saved for the screens (ticket 146), used for emails not tied to a case; English if none. */
  @Transactional(readOnly = true)
  public String preferredLanguage(String accountId) {
    return accounts.findById(accountId).map(account -> account.preferredLanguage)
        .filter(BuyerAccountService.LANGUAGES::contains).orElse("en");
  }
}
