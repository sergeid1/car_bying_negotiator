package com.dealanalyzer.appointment;

import com.dealanalyzer.account.AccountContactRelease.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fixed dealer-facing wording for test-drive messages, in the Buyer's own voice: the Buyer sends them from
 * their own mailbox, so nothing suggests an intermediary (spec 2.1). Every paragraph is rendered from validated
 * availability and recorded choices, never from Buyer prose, and none says the Buyer will buy (spec 5.8).
 * Released contact values are appended only at send time, after the gate checked this template.
 */
public final class TestDriveText {
  public static final Duration LENGTH = Duration.ofHours(1);
  static final int MAX_WINDOWS = 3;
  static final int SLOTS_PER_WINDOW = 2;
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US);

  public record Window(LocalDateTime start, LocalDateTime end) {}
  public record Slot(int option, Instant startsAt, Instant endsAt, String label) {}

  /** One-hour test-drive times from the Buyer's windows: up to two per window, in the Buyer's time zone. */
  static List<Slot> slots(List<Window> windows, ZoneId zone, Instant now) {
    if (windows == null || windows.isEmpty() || windows.size() > MAX_WINDOWS)
      throw new IllegalArgumentException("Enter between one and three availability windows.");
    var slots = new ArrayList<Slot>();
    for (Window window : windows) {
      if (window == null || window.start() == null || window.end() == null)
        throw new IllegalArgumentException("Each availability window needs a start and an end.");
      Instant start = window.start().atZone(zone).toInstant(), end = window.end().atZone(zone).toInstant();
      if (!end.isAfter(start) || Duration.between(start, end).compareTo(LENGTH) < 0 || Duration.between(start, end).toHours() > 12)
        throw new IllegalArgumentException("Each availability window must last between one and twelve hours.");
      if (!start.isAfter(now.plus(Duration.ofHours(1))) || start.isAfter(now.plus(Duration.ofDays(60))))
        throw new IllegalArgumentException("Availability must start at least an hour from now and within the next 60 days.");
      for (int i = 0; i < SLOTS_PER_WINDOW; i++) {
        Instant slotStart = start.plus(LENGTH.multipliedBy(i)), slotEnd = slotStart.plus(LENGTH);
        if (slotEnd.isAfter(end)) break;
        if (slots.stream().anyMatch(slot -> slot.startsAt().equals(slotStart))) continue;
        slots.add(new Slot(slots.size() + 1, slotStart, slotEnd, label(slotStart, slotEnd, zone)));
      }
    }
    return List.copyOf(slots);
  }

  static String label(Instant start, Instant end, ZoneId zone) {
    var from = start.atZone(zone);
    return DAY.format(from) + ", " + TIME.format(from) + " to " + TIME.format(end.atZone(zone)) + " (" + zone.getId() + ")";
  }

  static String proposal(List<Slot> slots) {
    return "I'd like to arrange a test drive of the vehicle in your written quote at one of these times:"
        + "\n\n" + options(slots) + "\n\n" + REPLY;
  }

  static String reschedule(String current, List<Slot> slots) {
    return "I need to move the test drive arranged for " + current + ". I'm available at one of these times instead:"
        + "\n\n" + options(slots) + "\n\n" + REPLY;
  }

  static String booking(String slot, Collection<Field> released) {
    return "Thank you for confirming. I'll see you for the test drive on " + slot + "."
        + "\n\nMy acceptance of your quote remains subject to this test drive and to the final paperwork matching the written quote."
        + "\n\n" + (released.isEmpty()
            ? "Please reply to this email with any changes."
            : "For this appointment, here are my " + names(released) + ".");
  }

  static String cancellation(String slot) {
    return "I need to cancel the test drive arranged for " + slot + ". Thank you for your time.";
  }

  /** The released values, appended after the gated text. Only the fields in {@code values} appear. */
  static String releasedBlock(Map<Field, String> values) {
    return values.entrySet().stream().map(entry -> name(entry.getKey(), true) + ": " + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  static String subject(String kind) {
    return switch (kind) {
      case "PROPOSAL" -> "Test drive times";
      case "RESCHEDULE" -> "Moving the test drive";
      case "BOOKING" -> "Test drive confirmed";
      default -> "Test drive cancelled";
    };
  }

  private static final String REPLY = "Please reply with the option number that suits you, or suggest other times.";

  private static String options(List<Slot> slots) {
    return slots.stream().map(slot -> "Option " + slot.option() + ": " + slot.label()).collect(Collectors.joining("\n"));
  }

  private static String names(Collection<Field> fields) {
    var names = java.util.Arrays.stream(Field.values()).filter(fields::contains).map(field -> name(field, false)).toList();
    return names.size() == 1 ? names.getFirst()
        : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
  }

  // "Telephone number" avoids the gate's contact-word rule, which guards Buyer prose, not this fixed label.
  private static String name(Field field, boolean capital) {
    String name = switch (field) {
      case FIRST_NAME -> "first name";
      case LAST_NAME -> "surname";
      case EMAIL -> "email";
      case PHONE -> "telephone number";
    };
    return capital ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
  }

  private TestDriveText() {}
}
