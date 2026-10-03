/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jdesktop.lg3d.apps.mail.MailAccount;
import org.jdesktop.lg3d.apps.mail.MailAttachment;
import org.jdesktop.lg3d.apps.mail.MailMessage;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link InvitationBuilder}, the pure RFC 5545 composer:
 * the {@code METHOD:REQUEST} payload (UTC times, escaping, folding, organizer
 * and RSVP attendees), the human-readable draft around it, and the
 * {@code invite.ics} attachment. Every "now" is injected, so the wire format
 * is asserted byte-for-byte.
 */
class InvitationBuilderTest {

    /** 2026-03-18 is a Wednesday; America/New_York is on EDT (UTC-4) then. */
    private static final LocalDate DATE = LocalDate.of(2026, 3, 18);
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Instant DTSTAMP = Instant.parse("2026-03-01T08:30:00Z");

    private static Appointment appointment() {
        // Wednesday (day=2), 10:00 for 2h; the title exercises TEXT escaping.
        return new Appointment("apt1", "Review; sync, pls", 2, 10, 2);
    }

    private static MailAccount organizer() {
        MailAccount org = new MailAccount("org1");
        org.setDisplayName("Big Boss");
        org.setEmailAddress("boss@example.com");
        return org;
    }

    private static List<ContactDirectory.ContactInfo> invitees() {
        return Arrays.asList(
                new ContactDirectory.ContactInfo("u1", "Alice Example", "alice@example.org"),
                new ContactDirectory.ContactInfo("u2", "No Mail", ""),
                null);
    }

    private static String ics() {
        return InvitationBuilder.buildICalendar(
                appointment(), DATE, ZONE, DTSTAMP, organizer(), invitees());
    }

    @Test
    void uidIsTheAppointmentIdInTheLg3dDomain() {
        assertEquals("apt1@lg3d", InvitationBuilder.uid(appointment()));
    }

    @Test
    void icsCarriesTheRequestEnvelopeAndUtcTimes() {
        String ics = ics();
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertTrue(ics.contains("VERSION:2.0\r\n"));
        assertTrue(ics.contains("PRODID:" + InvitationBuilder.PRODID + "\r\n"));
        assertTrue(ics.contains("METHOD:REQUEST\r\n"));
        assertTrue(ics.contains("UID:apt1@lg3d\r\n"));
        assertTrue(ics.contains("DTSTAMP:20260301T083000Z\r\n"));
        // 10:00 EDT == 14:00 UTC; two hours later == 16:00 UTC.
        assertTrue(ics.contains("DTSTART:20260318T140000Z\r\n"));
        assertTrue(ics.contains("DTEND:20260318T160000Z\r\n"));
        assertTrue(ics.contains("STATUS:CONFIRMED\r\n"));
        // Every line ends with CRLF and none is bare-LF terminated.
        for (String line : ics.split("\r\n", -1)) {
            assertFalse(line.contains("\n"));
        }
    }

    @Test
    void icsEscapesTheSummary() {
        assertTrue(ics().contains("SUMMARY:Review\\; sync\\, pls\r\n"));
    }

    @Test
    void icsListsOrganizerAndOnlyMailableAttendees() {
        String ics = ics();
        assertTrue(ics.contains(
                "ORGANIZER;CN=\"Big Boss\":mailto:boss@example.com\r\n"));
        assertTrue(ics.contains(
                "ATTENDEE;CN=\"Alice Example\";RSVP=TRUE:mailto:alice@example.org\r\n"));
        assertFalse(ics.contains("No Mail"), "e-mail-less invitees are skipped");
        assertEquals(1, countLines(ics, "ATTENDEE"));
    }

    @Test
    void organizerAndAttendeeCnParamsDropDoubleQuotes() {
        MailAccount org = organizer();
        org.setDisplayName("The \"Big\" Boss");
        ContactDirectory.ContactInfo quotey = new ContactDirectory.ContactInfo(
                "u3", "Ann \"Quote\" Smith", "ann@example.org");
        String ics = InvitationBuilder.buildICalendar(appointment(), DATE, ZONE,
                DTSTAMP, org, Arrays.asList(quotey));
        assertTrue(ics.contains("ORGANIZER;CN=\"The Big Boss\":mailto:"));
        assertTrue(ics.contains("ATTENDEE;CN=\"Ann Quote Smith\";RSVP=TRUE:"));
    }

    @Test
    void aNamelessOrganizerGetsNoCnParam() {
        MailAccount org = new MailAccount("org2");
        org.setEmailAddress("plain@example.com");
        String ics = InvitationBuilder.buildICalendar(appointment(), DATE, ZONE,
                DTSTAMP, org, invitees());
        assertTrue(ics.contains("ORGANIZER:mailto:plain@example.com\r\n"));
    }

    @Test
    void everyPhysicalLineStaysWithinThe75OctetLimit() {
        // A deliberately long, partly non-ASCII summary forces folding.
        Appointment longOne = new Appointment("apt2",
                "R\u00e9union de planification du projet tr\u00e8s importante "
                        + "avec tous les participants et un ordre du jour assez "
                        + "long pour d\u00e9border la limite de la ligne",
                2, 10, 2);
        String ics = InvitationBuilder.buildICalendar(longOne, DATE, ZONE,
                DTSTAMP, organizer(), invitees());
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75,
                    "line exceeds 75 octets: " + line);
        }
        // The SUMMARY still unfolds losslessly to the escaped title.
        String summary = unfold(ics, "SUMMARY:");
        assertTrue(summary.contains("R\u00e9union de planification"));
        assertTrue(summary.endsWith("limite de la ligne"));
    }

    /** Extracts one (possibly folded) content line and unfolds it. */
    private static String unfold(String ics, String prefix) {
        StringBuilder sb = new StringBuilder();
        boolean inLine = false;
        for (String physical : ics.split("\r\n")) {
            if (physical.startsWith(prefix)) {
                sb.append(physical);
                inLine = true;
            } else if (inLine && physical.startsWith(" ")) {
                sb.append(physical.substring(1));
            } else if (inLine) {
                break;
            }
        }
        return sb.toString();
    }

    @Test
    void foldLineIsLosslessAndKeepsShortLinesIntact() {
        String shortLine = "SUMMARY:short";
        assertEquals(shortLine, InvitationBuilder.foldLine(shortLine));

        StringBuilder sb = new StringBuilder("DESCRIPTION:");
        for (int i = 0; i < 40; i++) {
            sb.append("word").append(i).append(' ');
        }
        String longLine = sb.toString().trim();
        String folded = InvitationBuilder.foldLine(longLine);
        // Unfolding (drop CRLF + single leading space) restores the original.
        assertEquals(longLine, folded.replace("\r\n ", ""));
        for (String physical : folded.split("\r\n")) {
            assertTrue(physical.getBytes(StandardCharsets.UTF_8).length <= 75);
        }
    }

    @Test
    void foldLineNeverSplitsAMultiByteCharacter() {
        // 73 ASCII octets so the 74-octet cut lands inside the first 2-byte
        // character (lead byte at index 73, continuation byte at 74).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append("abc");
        }
        sb.append('a');
        sb.append("\u00e9\u00e9\u00e9");
        String folded = InvitationBuilder.foldLine(sb.toString());
        // Re-decoding every physical line as UTF-8 must not produce U+FFFD.
        for (String physical : folded.split("\r\n")) {
            assertFalse(physical.contains("\uFFFD"));
        }
        assertEquals(sb.toString(), folded.replace("\r\n ", ""));
    }

    @Test
    void escapeTextCoversTheRfc5545Specials() {
        assertEquals("a\\\\b", InvitationBuilder.escapeText("a\\b"));
        assertEquals("a\\;b", InvitationBuilder.escapeText("a;b"));
        assertEquals("a\\,b", InvitationBuilder.escapeText("a,b"));
        assertEquals("a\\nb", InvitationBuilder.escapeText("a\r\nb"));
        assertEquals("a\\nb", InvitationBuilder.escapeText("a\nb"));
        assertEquals("", InvitationBuilder.escapeText(null));
    }

    @Test
    void draftCarriesSubjectRecipientsAndBody() {
        MailMessage draft = InvitationBuilder.buildDraft(
                appointment(), DATE, ZONE, organizer(), invitees());
        assertEquals("boss@example.com", draft.getFrom().getEmail());
        assertEquals(1, draft.getTo().size());
        assertEquals("alice@example.org", draft.getTo().get(0).getEmail());
        assertEquals("Alice Example", draft.getTo().get(0).getName());
        assertEquals("Invitation: Review; sync, pls (Wednesday, March 18, 2026)",
                draft.getSubject());

        String body = draft.getTextBody();
        assertTrue(body.contains("Review; sync, pls"));
        assertTrue(body.contains("Wednesday, March 18, 2026"));
        assertTrue(body.contains("10:00 - 12:00"));
        assertTrue(body.contains("boss@example.com"));
        assertTrue(body.contains("invite.ics"));
    }

    @Test
    void attachmentWrapsTheIcsWithTheRequestMethodMimeType() {
        String ics = ics();
        MailAttachment att = InvitationBuilder.buildAttachment(ics);
        assertEquals(InvitationBuilder.ICS_FILE_NAME, att.getFileName());
        assertEquals("text/calendar; method=REQUEST", att.getMimeType());
        assertArrayEquals(ics.getBytes(StandardCharsets.UTF_8), att.getData());
    }

    @Test
    void icsWithNoMailableInviteeStillRendersTheEvent() {
        List<ContactDirectory.ContactInfo> none = new ArrayList<ContactDirectory.ContactInfo>();
        none.add(new ContactDirectory.ContactInfo("u2", "No Mail", null));
        String ics = InvitationBuilder.buildICalendar(appointment(), DATE, ZONE,
                DTSTAMP, organizer(), none);
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"));
        assertEquals(0, countLines(ics, "ATTENDEE"));
    }

    private static int countLines(String ics, String prefix) {
        int n = 0;
        for (String line : ics.split("\r\n")) {
            if (line.startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }
}
