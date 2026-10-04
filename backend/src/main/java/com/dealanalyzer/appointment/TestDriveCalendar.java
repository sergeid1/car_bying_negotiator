package com.dealanalyzer.appointment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** An RFC 5545 calendar file for one test drive: CRLF lines folded at 75 octets, escaped text, UTC times. */
final class TestDriveCalendar {
  private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  record Event(String uid, Instant stamp, Instant start, Instant end, boolean cancelled, String location, String description) {}

  static String render(Event event) {
    var lines = new ArrayList<String>(List.of(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Deal X-Ray//Test drive//EN", "CALSCALE:GREGORIAN",
        "METHOD:" + (event.cancelled() ? "CANCEL" : "PUBLISH"),
        "BEGIN:VEVENT", "UID:" + event.uid() + "@test-drive.deal-xray", "DTSTAMP:" + UTC.format(event.stamp()),
        "DTSTART:" + UTC.format(event.start()), "DTEND:" + UTC.format(event.end()),
        "SEQUENCE:" + (event.cancelled() ? 1 : 0), "STATUS:" + (event.cancelled() ? "CANCELLED" : "CONFIRMED"),
        "SUMMARY:" + text("Test drive")));
    if (event.location() != null && !event.location().isBlank()) lines.add("LOCATION:" + text(event.location()));
    lines.add("DESCRIPTION:" + text(event.description()));
    if (!event.cancelled())
      lines.addAll(List.of("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:" + text("Test drive reminder"), "TRIGGER:-PT2H", "END:VALARM"));
    lines.addAll(List.of("END:VEVENT", "END:VCALENDAR"));
    var out = new StringBuilder();
    for (String line : lines) out.append(fold(line)).append("\r\n");
    return out.toString();
  }

  /** TEXT escaping (RFC 5545 section 3.3.11). */
  static String text(String value) {
    return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
  }

  /** Folds a content line so no physical line exceeds 75 octets, never splitting a UTF-8 character. */
  static String fold(String line) {
    var out = new StringBuilder();
    int octets = 0;
    for (int i = 0; i < line.length(); ) {
      int codePoint = line.codePointAt(i);
      String character = new String(Character.toChars(codePoint));
      int size = character.getBytes(StandardCharsets.UTF_8).length;
      if (octets + size > 75) { out.append("\r\n "); octets = 1; }
      out.append(character); octets += size;
      i += Character.charCount(codePoint);
    }
    return out.toString();
  }

  private TestDriveCalendar() {}
}
