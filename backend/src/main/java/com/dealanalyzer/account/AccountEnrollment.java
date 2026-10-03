package com.dealanalyzer.account;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a new account in its own transaction. A concurrent first request for the same address
 * fails here on the unique email lookup without aborting the caller's transaction, which then
 * reads the account the other request created.
 */
@Component
class AccountEnrollment {
  private final BuyerAccountRepository accounts;
  private final ContactProtection protection;

  AccountEnrollment(BuyerAccountRepository accounts, ContactProtection protection) {
    this.accounts = accounts; this.protection = protection;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void create(String lookup, String normalizedEmail) {
    Instant now = Instant.now();
    var account = new BuyerAccountEntity(UUID.randomUUID().toString());
    account.emailLookup = lookup; account.lifecycle = "ACTIVE"; account.createdAt = now; account.updatedAt = now;
    account.contactCiphertext = protection.encrypt(account.id, new Contact(normalizedEmail, null, null, null));
    accounts.saveAndFlush(account);
  }
}
