package com.dealanalyzer.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import com.dealanalyzer.account.AccountContactRelease.Field;
import com.dealanalyzer.outbound.OutboundGate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestDriveCalendarTest {
  private static final Instant START = Instant.parse("2026-10-10T15:00:00Z");

  @Test void rendersAStandardsCompliantEvent() {
    String ics = TestDriveCalendar.render(new TestDriveCalendar.Event("a1", Instant.parse("2026-10-04T12:00:00Z"), START,
        START.plusSeconds(3600), false, "Dealer; North, Branch",
        "Agreed Price: verified offer 3, out-the-door total 26800.00. Bring the one-page Agreed Price summary and check that the paperwork matches it."));
    assertThat(ics).endsWith("END:VCALENDAR\r\n").startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:");
    assertThat(ics.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    for (String line : ics.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    String unfolded = ics.replace("\r\n ", "");
    assertThat(unfolded).contains("UID:a1@test-drive.deal-xray\r\n", "DTSTAMP:20261004T120000Z\r\n", "DTSTART:20261010T150000Z\r\n",
        "DTEND:20261010T160000Z\r\n", "STATUS:CONFIRMED\r\n", "LOCATION:Dealer\\; North\\, Branch\r\n", "METHOD:PUBLISH\r\n",
        "BEGIN:VALARM\r\n", "TRIGGER:-PT2H\r\n", "out-the-door total 26800.00", "verified offer 3\\,");
    assertThat(unfolded.indexOf("BEGIN:VEVENT")).isLessThan(unfolded.indexOf("END:VEVENT"));
  }

  @Test void aCancelledEventCarriesCancelStatusAndNoAlarm() {
    String ics = TestDriveCalendar.render(new TestDriveCalendar.Event("a1", START, START, START.plusSeconds(3600), true, null, "x"));
    assertThat(ics).contains("METHOD:CANCEL\r\n", "STATUS:CANCELLED\r\n", "SEQUENCE:1\r\n").doesNotContain("VALARM", "LOCATION");
  }

  @Test void foldingNeverSplitsMultibyteCharacters() {
    String folded = TestDriveCalendar.fold("DESCRIPTION:" + "é".repeat(80));
    for (String line : folded.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    assertThat(folded.replace("\r\n ", "")).isEqualTo("DESCRIPTION:" + "é".repeat(80));
  }

  @Test void proposesHourLongTimesFromAvailabilityInTheBuyersZone() {
    var zone = ZoneId.of("America/Chicago");
    var slots = TestDriveText.slots(List.of(new TestDriveText.Window(LocalDateTime.parse("2026-10-10T10:00"), LocalDateTime.parse("2026-10-10T12:30")),
        new TestDriveText.Window(LocalDateTime.parse("2026-10-11T09:00"), LocalDateTime.parse("2026-10-11T10:00"))), zone, Instant.parse("2026-10-04T12:00:00Z"));
    assertThat(slots).extracting(TestDriveText.Slot::option).containsExactly(1, 2, 3);
    assertThat(slots.getFirst().startsAt()).isEqualTo(Instant.parse("2026-10-10T15:00:00Z"));
    assertThat(slots.getFirst().label()).isEqualTo("Saturday, October 10, 2026, 10:00 to 11:00 (America/Chicago)");
    assertThat(TestDriveText.proposal(slots)).contains("Option 3: Sunday, October 11, 2026, 09:00 to 10:00 (America/Chicago)");
  }

  @Test void dealerWordingCommitsTheBuyerToNothingAndPassesTheGateOnlyWhenGrounded() {
    var slots = TestDriveText.slots(List.of(new TestDriveText.Window(LocalDateTime.parse("2026-10-10T10:00"), LocalDateTime.parse("2026-10-10T11:00"))),
        ZoneId.of("UTC"), Instant.parse("2026-10-04T12:00:00Z"));
    var texts = List.of(TestDriveText.proposal(slots), TestDriveText.reschedule(slots.getFirst().label(), slots),
        TestDriveText.booking(slots.getFirst().label(), EnumSet.of(Field.EMAIL, Field.PHONE)),
        TestDriveText.booking(slots.getFirst().label(), EnumSet.noneOf(Field.class)), TestDriveText.cancellation(slots.getFirst().label()));
    var gate = new OutboundGate();
    for (String text : texts) {
      // Sent from the Buyer's own mailbox: first person, with no sign of an intermediary.
      assertThat(text).doesNotContain("The Buyer", "Buyer's", "otdog", "this conversation", "relay");
      assertThat(text).doesNotContainIgnoringCase("will buy").doesNotContainIgnoringCase("purchase").doesNotContainIgnoringCase("deposit")
          .doesNotContain("@");
      var ungrounded = gate.check(new OutboundGate.Draft("d", text), new OutboundGate.GateContext(null, java.util.Set.of(), java.util.Set.of(),
          java.util.Set.of(), java.util.Set.of(), null));
      assertThat(ungrounded.pass()).isFalse();
    }
    assertThat(TestDriveText.booking("x", EnumSet.of(Field.EMAIL, Field.PHONE)))
        .contains("For this appointment, here are my email and telephone number.", "My acceptance of your quote remains subject to this test drive");
    assertThat(TestDriveText.booking("x", EnumSet.noneOf(Field.class))).endsWith("Please reply to this email with any changes.");
    assertThat(TestDriveText.releasedBlock(Map.of(Field.EMAIL, "b@example.com"))).isEqualTo("Email: b@example.com");
  }

  @Test void spanishWordingCarriesTheSameConditionsAndPassesTheGateOnlyForItsOwnGroundedText() {
    var zone = ZoneId.of("America/Chicago");
    var slots = TestDriveText.slots(List.of(new TestDriveText.Window(LocalDateTime.parse("2026-10-10T10:00"), LocalDateTime.parse("2026-10-10T12:30"))),
        zone, Instant.parse("2026-10-04T12:00:00Z"), "es");
    String label = slots.getFirst().label();
    assertThat(label).isEqualTo("sábado 10 de octubre de 2026, de 10:00 a 11:00 (America/Chicago)");
    var texts = List.of(TestDriveText.proposal(slots, "es"), TestDriveText.reschedule(label, slots, "es"),
        TestDriveText.booking(label, EnumSet.of(Field.EMAIL, Field.PHONE), "es"),
        TestDriveText.booking(label, EnumSet.noneOf(Field.class), "es"), TestDriveText.cancellation(label, "es"));
    assertThat(texts.getFirst()).contains("Opción 1: " + label, "Opción 2: ").endsWith("o sugiera otros horarios.");
    assertThat(texts.get(2)).contains("Para esta cita, le comparto mis datos: correo electrónico y número telefónico.",
        "Mi aceptación de su cotización sigue sujeta a esta prueba de manejo y a que los documentos finales coincidan");
    assertThat(texts.get(3)).endsWith("Por favor, responda a este mensaje con cualquier cambio.");
    var gate = new OutboundGate();
    var mandate = new com.dealanalyzer.mandate.MandateService.Mandate("m", "c", 1, com.dealanalyzer.mandate.MandateService.Status.ACTIVE, 1,
        "USD", 1, 2, 1, 2, List.of(), Instant.now().plusSeconds(3600), "BUYER", Instant.now(), null, null);
    for (String text : texts) {
      assertThat(text).doesNotContain("comprador", "otdog", "@").doesNotContainIgnoringCase("compr").doesNotContainIgnoringCase("depósito");
      var paragraphs = java.util.Set.copyOf(List.of(text.split("\\n\\n", -1)));
      var spanish = new OutboundGate.GateContext(mandate, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
          null, paragraphs, null, "es");
      assertThat(gate.check(new OutboundGate.Draft("d", text), spanish).pass()).as(text).isTrue();
      assertThat(gate.check(new OutboundGate.Draft("d", text + "\n\nFirmo ese mismo día."), spanish).pass()).isFalse();
    }
    assertThat(TestDriveText.releasedBlock(Map.of(Field.PHONE, "+1 312 555 0199"), "es")).isEqualTo("Número telefónico: +1 312 555 0199");
    assertThat(TestDriveText.subject("PROPOSAL", "es")).isEqualTo("Horarios para una prueba de manejo");
  }
}
