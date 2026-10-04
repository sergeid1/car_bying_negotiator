package com.dealanalyzer.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountOutboundPrivacyTest {
  @Test void attachedEncryptedContactsProvideAllKnownValuesWithoutExposingCiphertext() {
    var manager = mock(EntityManager.class);
    var query = mock(Query.class);
    var protection = new ContactProtection("0123456789abcdef0123456789abcdef", new ObjectMapper());
    String encrypted = protection.encrypt("account", new Contact("alice@example.com", "Alice", "Buyer", "2125551234"));
    when(manager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter("caseId", "case")).thenReturn(query);
    when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[] {"account", encrypted}));
    assertThat(new AccountOutboundPrivacy(manager, protection).knownValues("case"))
        .containsExactlyInAnyOrder("alice@example.com", "Alice", "Buyer", "2125551234");
    verify(query).setParameter("caseId", "case");
  }
}
