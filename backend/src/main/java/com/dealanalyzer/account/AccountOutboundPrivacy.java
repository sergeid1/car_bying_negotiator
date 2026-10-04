package com.dealanalyzer.account;

import jakarta.persistence.EntityManager;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only privacy boundary. Contact values stay in memory, never in draft context snapshots. */
@Service
public class AccountOutboundPrivacy {
  private final EntityManager manager;
  private final ContactProtection protection;
  public AccountOutboundPrivacy(EntityManager manager, ContactProtection protection) {
    this.manager = manager; this.protection = protection;
  }
  @Transactional(readOnly = true)
  public Set<String> knownValues(String caseId) {
    var values = new HashSet<String>();
    for (Object item : manager.createNativeQuery("""
        select a.id, a.contact_ciphertext from buyer_accounts a
        join buyer_account_cases c on c.account_id = a.id where c.case_id = :caseId
        """).setParameter("caseId", caseId).getResultList()) {
      Object[] row = (Object[]) item;
      var contact = protection.decrypt(row[0].toString(), row[1].toString());
      for (String value : new String[] {contact.email(), contact.firstName(), contact.lastName(), contact.phone()})
        if (value != null && !value.isBlank()) values.add(value);
    }
    return Set.copyOf(values);
  }
}
