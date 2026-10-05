package com.dealanalyzer.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account/notifications")
public class NotificationController {
  private final BuyerNotifications notifications;
  NotificationController(BuyerNotifications notifications) { this.notifications = notifications; }
  public record Change(@NotNull NotificationType type, @NotNull Boolean enabled) {}

  @GetMapping
  public List<BuyerNotifications.Preference> get(@com.dealanalyzer.account.AccountSession String session) {
    return notifications.preferences(session);
  }

  @PutMapping
  public List<BuyerNotifications.Preference> update(@com.dealanalyzer.account.AccountSession String session,
      @Valid @RequestBody Change change) {
    return notifications.update(session, change.type(), change.enabled());
  }
}
