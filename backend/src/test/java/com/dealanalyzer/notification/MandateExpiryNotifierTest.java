package com.dealanalyzer.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dealanalyzer.mandate.MandateService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MandateExpiryNotifierTest {
  private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

  @Test void warnsTheAttachedAccountOncePerMandateWithoutSensitiveContent() {
    var mandates = mock(MandateService.class);
    var notifications = mock(BuyerNotifications.class);
    var jdbc = mock(JdbcTemplate.class);
    var attached = mandate("m-attached", "case-attached", NOW.minus(Duration.ofDays(13)), NOW.plus(Duration.ofHours(6)));
    var unattached = mandate("m-unattached", "case-unattached", NOW.minus(Duration.ofDays(13)), NOW.plus(Duration.ofHours(6)));
    var shortLived = mandate("m-short", "case-attached", NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)));
    when(mandates.expiringBetween(NOW, NOW.plus(Duration.ofHours(24)))).thenReturn(List.of(attached, unattached, shortLived));
    when(jdbc.queryForList(anyString(), eq(String.class), eq("case-attached"))).thenReturn(List.of("account-1"));
    when(jdbc.queryForList(anyString(), eq(String.class), eq("case-unattached"))).thenReturn(List.of());

    int attempted = new MandateExpiryNotifier(mandates, notifications, jdbc, Duration.ofHours(24)).notifyExpiring(NOW);

    assertThat(attempted).isEqualTo(1);
    verify(notifications).notify("account-1", NotificationType.MANDATE_EXPIRING, "mandate-expiring:m-attached");
    verifyNoMoreInteractions(notifications);
  }

  @Test void refusesANonPositiveNoticeWindow() {
    assertThatThrownBy(() -> new MandateExpiryNotifier(mock(MandateService.class), mock(BuyerNotifications.class),
        mock(JdbcTemplate.class), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
  }

  private static MandateService.Mandate mandate(String id, String caseId, Instant approvedAt, Instant expiresAt) {
    return new MandateService.Mandate(id, caseId, 1, MandateService.Status.ACTIVE, 1, "USD", 2_000_000, 2_100_000,
        2_000_000, 2_100_000, List.of(), expiresAt, "BUYER", approvedAt, null, null);
  }
}
