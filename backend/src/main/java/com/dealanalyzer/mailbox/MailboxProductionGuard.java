package com.dealanalyzer.mailbox;

import com.dealanalyzer.deployment.DeploymentStage;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The fake mailbox sender records messages instead of sending them, so production-like stages refuse it, like
 * the relay and evidence guards. A live sender there also needs the dedicated token key. Production runs with
 * {@code mailbox.sender=none} until the operator's Google OAuth verification and Microsoft publisher
 * verification are complete.
 */
@Component
class MailboxProductionGuard implements SmartInitializingSingleton {
  private final DeploymentStage stage;
  private final List<MailboxSender> senders;
  private final MailboxTokenProtection protection;

  MailboxProductionGuard(Environment environment, List<MailboxSender> senders, MailboxTokenProtection protection) {
    this.stage = DeploymentStage.from(environment); this.senders = senders; this.protection = protection;
  }

  @Override public void afterSingletonsInstantiated() {
    if (!stage.productionLike()) return;
    if (senders.stream().anyMatch(FakeMailboxSender.class::isInstance))
      throw new IllegalStateException("The fake mailbox sender cannot serve production; set mailbox.sender");
    if (!senders.isEmpty() && !protection.configured())
      throw new IllegalStateException("MAILBOX_TOKEN_ENCRYPTION_KEY must contain at least 32 bytes when mailbox.sender=live");
  }
}
