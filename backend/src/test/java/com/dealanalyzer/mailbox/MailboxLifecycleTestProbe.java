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
  private volatile MailboxSendService.Attempt firstAttempt;
  private MailboxSendService service;

  public void afterPreparation(Runnable action) { prepared = action; }
  public void resetAttempt() { firstAttempt = null; }
  public void staleCompletion(boolean sent) {
    service.finish(firstAttempt, sent ? "SENT" : "REJECTED", sent
        ? com.dealanalyzer.relay.DealerMessageHandoff.MailboxOutcome.SENT
        : com.dealanalyzer.relay.DealerMessageHandoff.MailboxOutcome.NOT_SENT);
  }

  @Bean static BeanPostProcessor mailboxPreparationProbe() {
    return new BeanPostProcessor() {
      @Override public Object postProcessBeforeInitialization(Object bean, String name) {
        if (bean instanceof com.dealanalyzer.relay.DealerMessageHandoff handoffs) {
          var instrumented = spy(handoffs);
          doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (result instanceof MailboxSendService.Attempt attempt && probe.firstAttempt == null) probe.firstAttempt = attempt;
            return result;
          }).when(instrumented).claimForMailbox(any(), any(), any(), any());
          return instrumented;
        }
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
      private MailboxSendService configuredService;
      @Override public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof MailboxLifecycleTestProbe configured) { probe = configured; probe.service = configuredService; }
        if (bean instanceof MailboxSendService configured) {
          configuredService = configured;
          if (probe != null) probe.service = configured;
        }
        return bean;
      }
    };
  }
}
