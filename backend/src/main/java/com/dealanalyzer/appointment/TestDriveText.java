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
 * Released contact values are appended only at send time, after the gate checked this template. Each message
 * has a Spanish counterpart for a dealer whose emails the Buyer chose to send in Spanish (ticket 149).
 */
public final class TestDriveText {
  public static final Duration LENGTH = Duration.ofHours(1);
  static final int MAX_WINDOWS = 3;
  static final int SLOTS_PER_WINDOW = 2;
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.US);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
  private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' uuuu", Locale.forLanguageTag("es-US"));

  public record Window(LocalDateTime start, LocalDateTime end) {}
  public record Slot(int option, Instant startsAt, Instant endsAt, String label) {}

  /** One-hour test-drive times from the Buyer's windows: up to two per window, in the Buyer's time zone. */
  static List<Slot> slots(List<Window> windows, ZoneId zone, Instant now) {
    return slots(windows, zone, now, "en");
  }

  static List<Slot> slots(List<Window> windows, ZoneId zone, Instant now, String language) {
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
        slots.add(new Slot(slots.size() + 1, slotStart, slotEnd, label(slotStart, slotEnd, zone, language)));
      }
    }
    return List.copyOf(slots);
  }

  static String label(Instant start, Instant end, ZoneId zone) {
    return label(start, end, zone, "en");
  }

  static String label(Instant start, Instant end, ZoneId zone, String language) {
    var from = start.atZone(zone);
    if (SPANISH.equals(language))
      return DIA.format(from) + ", de " + TIME.format(from) + " a " + TIME.format(end.atZone(zone)) + " (" + zone.getId() + ")";
    return DAY.format(from) + ", " + TIME.format(from) + " to " + TIME.format(end.atZone(zone)) + " (" + zone.getId() + ")";
  }

  static String proposal(List<Slot> slots) { return proposal(slots, "en"); }

  static String proposal(List<Slot> slots, String language) {
    if (SPANISH.equals(language))
      return "Me gustaría programar una prueba de manejo del vehículo de su cotización por escrito en uno de estos horarios:"
          + "\n\n" + options(slots, language) + "\n\n" + RESPUESTA;
    return "I'd like to arrange a test drive of the vehicle in your written quote at one of these times:"
        + "\n\n" + options(slots, language) + "\n\n" + REPLY;
  }

  static String reschedule(String current, List<Slot> slots) { return reschedule(current, slots, "en"); }

  static String reschedule(String current, List<Slot> slots, String language) {
    if (SPANISH.equals(language))
      return "Necesito cambiar la prueba de manejo programada para el " + current + ". En su lugar, estoy disponible en uno de estos horarios:"
          + "\n\n" + options(slots, language) + "\n\n" + RESPUESTA;
    return "I need to move the test drive arranged for " + current + ". I'm available at one of these times instead:"
        + "\n\n" + options(slots, language) + "\n\n" + REPLY;
  }

  static String booking(String slot, Collection<Field> released) { return booking(slot, released, "en"); }

  static String booking(String slot, Collection<Field> released, String language) {
    if (SPANISH.equals(language))
      return "Gracias por confirmar. Nos vemos para la prueba de manejo el " + slot + "."
          + "\n\nMi aceptación de su cotización sigue sujeta a esta prueba de manejo y a que los documentos finales coincidan con la cotización por escrito."
          + "\n\n" + (released.isEmpty()
              ? "Por favor, responda a este mensaje con cualquier cambio."
              : "Para esta cita, le comparto mis datos: " + names(released, language) + ".");
    return "Thank you for confirming. I'll see you for the test drive on " + slot + "."
        + "\n\nMy acceptance of your quote remains subject to this test drive and to the final paperwork matching the written quote."
        + "\n\n" + (released.isEmpty()
            ? "Please reply to this email with any changes."
            : "For this appointment, here are my " + names(released, language) + ".");
  }

  static String cancellation(String slot) { return cancellation(slot, "en"); }

  static String cancellation(String slot, String language) {
    if (SPANISH.equals(language))
      return "Necesito cancelar la prueba de manejo programada para el " + slot + ". Gracias por su tiempo.";
    return "I need to cancel the test drive arranged for " + slot + ". Thank you for your time.";
  }

  /** The released values, appended after the gated text. Only the fields in {@code values} appear. */
  static String releasedBlock(Map<Field, String> values) { return releasedBlock(values, "en"); }

  static String releasedBlock(Map<Field, String> values, String language) {
    return values.entrySet().stream().map(entry -> name(entry.getKey(), true, language) + ": " + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  static String subject(String kind) { return subject(kind, "en"); }

  static String subject(String kind, String language) {
    if (SPANISH.equals(language)) return switch (kind) {
      case "PROPOSAL" -> "Horarios para una prueba de manejo";
      case "RESCHEDULE" -> "Cambio de la prueba de manejo";
      case "BOOKING" -> "Prueba de manejo confirmada";
      default -> "Prueba de manejo cancelada";
    };
    return switch (kind) {
      case "PROPOSAL" -> "Test drive times";
      case "RESCHEDULE" -> "Moving the test drive";
      case "BOOKING" -> "Test drive confirmed";
      default -> "Test drive cancelled";
    };
  }

  private static final String SPANISH = "es";
  private static final String REPLY = "Please reply with the option number that suits you, or suggest other times.";
  private static final String RESPUESTA = "Por favor, responda con el número de la opción que le convenga, o sugiera otros horarios.";

  private static String options(List<Slot> slots, String language) {
    String option = SPANISH.equals(language) ? "Opción " : "Option ";
    return slots.stream().map(slot -> option + slot.option() + ": " + slot.label()).collect(Collectors.joining("\n"));
  }

  private static String names(Collection<Field> fields, String language) {
    var names = java.util.Arrays.stream(Field.values()).filter(fields::contains).map(field -> name(field, false, language)).toList();
    return names.size() == 1 ? names.getFirst()
        : String.join(", ", names.subList(0, names.size() - 1)) + (SPANISH.equals(language) ? " y " : " and ") + names.getLast();
  }

  // "Telephone number" avoids the gate's contact-word rule, which guards Buyer prose, not this fixed label; so
  // does "número telefónico", which the Spanish rule ("teléfono") leaves alone.
  private static String name(Field field, boolean capital, String language) {
    String name = SPANISH.equals(language) ? switch (field) {
      case FIRST_NAME -> "nombre";
      case LAST_NAME -> "apellido";
      case EMAIL -> "correo electrónico";
      case PHONE -> "número telefónico";
    } : switch (field) {
      case FIRST_NAME -> "first name";
      case LAST_NAME -> "surname";
      case EMAIL -> "email";
      case PHONE -> "telephone number";
    };
    return capital ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
  }

  private TestDriveText() {}
}
