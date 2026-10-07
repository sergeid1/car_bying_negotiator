package com.dealanalyzer.mailbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;

/** Pauses after token preparation without exposing tokens or adding a production hook. */
@TestConfiguration
public class MailboxLifecycleTestProbe {
  private volatile Runnable prepared = () -> {};

  public void afterPreparation(Runnable action) { prepared = action; }

  @Bean static BeanPostProcessor mailboxPreparationProbe() {
    return new BeanPostProcessor() {
      @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (!(bean instanceof MailboxConnections)) return bean;
        var instrumented = spy((MailboxConnections) bean);
        doAnswer(invocation -> {
          Object result = invocation.callRealMethod();
          // The hook belongs to this context, and is resolved only when a send runs.
          probe.prepared.run();
          return result;
        }).when(instrumented).sendable(any());
        return instrumented;
      }
      private MailboxLifecycleTestProbe probe;
      @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof MailboxLifecycleTestProbe configured) probe = configured;
        return bean;
      }
    };
  }
}
