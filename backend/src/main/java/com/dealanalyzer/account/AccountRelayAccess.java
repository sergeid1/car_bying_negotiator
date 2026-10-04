package com.dealanalyzer.account;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow account boundary for the dealer relay: it reads the Buyer's contact only to keep it out of
 * outbound mail, and tells the relay whether the owning account is still active.
 */
@Service
public class AccountRelayAccess {
  private final AccountCaseRepository attachments;
  private final BuyerAccountRepository accounts;
  private final ContactProtection protection;

  AccountRelayAccess(AccountCaseRepository attachments, BuyerAccountRepository accounts, ContactProtection protection) {
    this.attachments = attachments; this.accounts = accounts; this.protection = protection;
  }

  /** The account a case is attached to, if any. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<String> accountId(String caseId) {
    return attachments.findById(caseId).map(attachment -> attachment.accountId);
  }

  /** False once the owning account is being deleted; an unattached case has no account to check. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean accountActive(String caseId) {
    return accountId(caseId).flatMap(accounts::findById).map(account -> "ACTIVE".equals(account.lifecycle)).orElse(true);
  }

  /** The Buyer's stored contact for an attached case. Never log or return it. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<Contact> contact(String caseId) {
    return accountId(caseId).flatMap(accounts::findById)
        .map(account -> protection.decrypt(account.id, account.contactCiphertext));
  }
}
