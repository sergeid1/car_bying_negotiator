package com.dealanalyzer.recovery;

import com.dealanalyzer.deployment.DeploymentStage;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Local mail sinks must never accept a live deployment's account links. */
@Component
public class RecoveryEmailProductionGuard {
  public RecoveryEmailProductionGuard(Environment environment) {
    String from = environment.getProperty("case.recovery.from", "").trim();
    String host = environment.getProperty("spring.mail.host", "").trim().toLowerCase(Locale.ROOT);
    if (DeploymentStage.from(environment).productionLike() && !from.isEmpty() && localHost(host))
      throw new IllegalStateException("Pilot/production recovery email requires a real SMTP host.");
    if (DeploymentStage.from(environment).productionLike() && !from.isEmpty()) {
      String topics = environment.getProperty("email.ses.topic-arns", "");
      if (topics.isBlank() || java.util.Arrays.stream(topics.split(",", -1))
          .anyMatch(topic -> !com.dealanalyzer.email.SnsVerifier.validTopic(topic.trim())))
        throw new IllegalStateException("Pilot/production recovery email requires SES bounce/complaint topic configuration.");
    }
  }

  private static boolean localHost(String host) {
    String normalized = host.replace("[", "").replace("]", "").replaceAll("\\.$", "");
    return normalized.isEmpty() || normalized.equals("localhost") || normalized.endsWith(".localhost")
        || normalized.startsWith("127.") || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1")
        || normalized.equals("0.0.0.0") || normalized.equals("::") || normalized.startsWith("::ffff:127.")
        || normalized.contains("mailpit") || normalized.contains("mailhog") || normalized.contains("mailcatcher")
        || normalized.equals("host.docker.internal") || normalized.equals("gateway.docker.internal");
  }
}
