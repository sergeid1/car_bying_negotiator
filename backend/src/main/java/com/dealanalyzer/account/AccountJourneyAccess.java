package com.dealanalyzer.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow account boundary for Buying Journeys: a session resolves to its account, and journey
 * writes hold the account lock so they cannot race an account deletion request.
 */
@Service
public class AccountJourneyAccess {
  private final BuyerAccountService service;
  private final BuyerAccountRepository accounts;

  AccountJourneyAccess(BuyerAccountService service, BuyerAccountRepository accounts) {
    this.service = service; this.accounts = accounts;
  }

  /** The signed-in account. An absent, revoked or expired session is a 401 SESSION_ENDED outcome. */
  @Transactional(readOnly = true)
  public String accountId(String session) { return service.requireSession(session).accountId; }

  /** Locks the account for a journey write; an account pending deletion ends the sign-in. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockActiveAccount(String accountId) {
    var account = accounts.findLockedById(accountId).orElseThrow(BuyerAccountService::sessionEnded);
    if (!"ACTIVE".equals(account.lifecycle)) throw BuyerAccountService.sessionEnded();
  }
}
