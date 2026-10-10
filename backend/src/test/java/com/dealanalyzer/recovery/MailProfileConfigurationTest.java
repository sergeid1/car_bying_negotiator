package com.dealanalyzer.recovery;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailProfileConfigurationTest {
  private ApplicationContextRunner runner(String profile, Map<String, Object> variables) {
    return new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=" + profile)
        .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("testMailEnvironment", variables)))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class));
  }

  @Test void developmentProfileConfiguresTheMailpitSenderWithoutAuthOrTls() {
    runner("development", Map.of()).run(context -> {
      assertThat(context).hasNotFailed();
      var mail = context.getBean(JavaMailSenderImpl.class);
      assertThat(mail.getHost()).isEqualTo("127.0.0.1");
      assertThat(mail.getPort()).isEqualTo(1025);
      for (String property : new String[]{"mail.smtp.auth", "mail.smtp.starttls.enable", "mail.smtp.starttls.required"})
        assertThat(mail.getJavaMailProperties().getProperty(property)).as(property).isEqualTo("false");
      assertThat(context.getEnvironment().getProperty("case.recovery.from")).isEqualTo("deal-xray@localhost.test");
    });
  }

  @Test void pilotAndProductionRetainSesAuthAndRequiredStarttlsDefaults() {
    for (String profile : new String[]{"pilot", "production"}) {
      runner(profile, Map.of("SES_SMTP_HOST", "email-smtp.us-east-1.amazonaws.com")).run(context -> {
        assertThat(context).hasNotFailed();
        var mail = context.getBean(JavaMailSenderImpl.class);
        assertThat(mail.getHost()).isEqualTo("email-smtp.us-east-1.amazonaws.com");
        assertThat(mail.getPort()).isEqualTo(587);
        for (String property : new String[]{"mail.smtp.auth", "mail.smtp.starttls.enable", "mail.smtp.starttls.required"})
          assertThat(mail.getJavaMailProperties().getProperty(property)).as(property).isEqualTo("true");
      });
    }
  }

  @Test void directSpringMailEnvironmentOverridesSesAliases() {
    runner("production", Map.of("SES_SMTP_HOST", "ses.example", "SPRING_MAIL_HOST", "direct.example",
        "SES_SMTP_PORT", "587", "SPRING_MAIL_PORT", "2587",
        "SES_SMTP_USERNAME", "ses-user", "SPRING_MAIL_USERNAME", "direct-user",
        "SES_SMTP_PASSWORD", "synthetic-ses-password", "SPRING_MAIL_PASSWORD", "synthetic-direct-password"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var mail = context.getBean(JavaMailSenderImpl.class);
          assertThat(mail.getHost()).isEqualTo("direct.example");
          assertThat(mail.getPort()).isEqualTo(2587);
          assertThat(mail.getUsername()).isEqualTo("direct-user");
          assertThat(mail.getPassword()).isEqualTo("synthetic-direct-password");
        });
  }
}
