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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jdesktop.lg3d.apps.mail.MailAccount;
import org.jdesktop.lg3d.apps.mail.MailAddress;
import org.jdesktop.lg3d.apps.mail.MailAttachment;
import org.jdesktop.lg3d.apps.mail.MailMessage;

/**
 * Builds a real <b>RFC 5545 iCalendar meeting request</b> for an
 * {@link Appointment}: the {@code text/calendar} payload with
 * {@code METHOD:REQUEST} (so the recipient's mail client offers
 * accept / tentative / decline), the human-readable invitation draft around
 * it, and the {@code invite.ics} attachment that carries the payload.
 *
 * <p>The class is deliberately pure and AWT-free — every method is a static
 * function of its arguments (the "now" instants are passed in) — so the whole
 * wire format is unit-testable headlessly. Sending is
 * {@link InvitationSender}'s job; this class only composes.</p>
 *
 * <p>Times are emitted as UTC ({@code ...Z}) from the desktop's local zone:
 * an appointment's concrete date is the displayed week's date for its
 * day-of-week, its start hour is on the hour, and its duration is whole
 * hours, so the conversion is exact and unambiguous on the wire.</p>
 */
public final class InvitationBuilder {

    /** PRODID identifying this desktop as the organizer software. */
    static final String PRODID = "-//Project Looking Glass//LG3D Agenda//EN";

    /** Domain suffix of the per-event UID (the appointment id is the local part). */
    static final String UID_DOMAIN = "lg3d";

    /** File name of the attached iCalendar object. */
    static final String ICS_FILE_NAME = "invite.ics";

    /**
     * MIME type of the attachment. The {@code method=REQUEST} parameter is what
     * upgrades a plain .ics from "here is a calendar file" to "here is a
     * meeting invitation you can answer".
     */
    static final String ICS_MIME_TYPE = "text/calendar; method=REQUEST";

    /** RFC 5545 wires UTCTIME as {@code yyyyMMdd'T'HHmmss'Z'}. */
    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter HUMAN_DATE =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);

    /** RFC 5545 caps a content line at 75 octets; fold a little earlier. */
    private static final int FOLD_OCTETS = 74;

    private InvitationBuilder() {
        // no instances
    }

    /** The stable UID of an appointment's calendar event. */
    public static String uid(final Appointment a) {
        return a.getId() + "@" + UID_DOMAIN;
    }

    /**
     * Renders the full {@code VCALENDAR} object: a single {@code VEVENT} with
     * {@code METHOD:REQUEST}, the organizer and one {@code RSVP=TRUE} attendee
     * line per invitee, folded to RFC 5545 line length and terminated with
     * CRLF line breaks.
     *
     * @param a         the appointment to invite to
     * @param date      the concrete calendar date of the occurrence
     * @param zone      the desktop's local zone the hours are read in
     * @param dtstamp   the "sent at" instant (passed in so tests are exact)
     * @param organizer the sending mail account (the event's organizer)
     * @param invitees  the resolved contacts to invite (blank e-mails ignored)
     * @return the iCalendar payload as text
     */
    public static String buildICalendar(final Appointment a, final LocalDate date,
            final ZoneId zone, final Instant dtstamp, final MailAccount organizer,
            final List<ContactDirectory.ContactInfo> invitees) {
        ZonedDateTime start = date.atTime(a.getStartHour(), 0).atZone(zone);
        ZonedDateTime end = start.plusHours(a.getDuration());

        List<String> lines = new ArrayList<String>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:" + PRODID);
        lines.add("CALSCALE:GREGORIAN");
        lines.add("METHOD:REQUEST");
        lines.add("BEGIN:VEVENT");
        lines.add("UID:" + uid(a));
        lines.add("DTSTAMP:" + ICS_UTC.format(dtstamp));
        lines.add("DTSTART:" + ICS_UTC.format(start));
        lines.add("DTEND:" + ICS_UTC.format(end));
        lines.add("SUMMARY:" + escapeText(a.getTitle()));
        lines.add("STATUS:CONFIRMED");
        lines.add("TRANSP:OPAQUE");
        lines.add("ORGANIZER" + cnParam(organizer.getDisplayName())
                + ":mailto:" + organizer.getEmailAddress());
        for (ContactDirectory.ContactInfo c : invitees) {
            if (c == null || c.email == null || c.email.isBlank()) {
                continue;
            }
            lines.add("ATTENDEE" + cnParam(c.displayName)
                    + ";RSVP=TRUE:mailto:" + c.email);
        }
        lines.add("END:VEVENT");
        lines.add("END:VCALENDAR");

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(foldLine(line)).append("\r\n");
        }
        return out.toString();
    }

    /**
     * Renders the human-readable invitation e-mail around the iCalendar
     * payload: subject, recipient list and a plain-text summary of when the
     * meeting is and who organizes it.
     *
     * @param a         the appointment to invite to
     * @param date      the concrete calendar date of the occurrence
     * @param zone      the desktop's local zone the hours are read in
     * @param organizer the sending mail account
     * @param invitees  the resolved contacts to invite (blank e-mails ignored)
     * @return the draft, ready for {@code MailService.send}
     */
    public static MailMessage buildDraft(final Appointment a, final LocalDate date,
            final ZoneId zone, final MailAccount organizer,
            final List<ContactDirectory.ContactInfo> invitees) {
        MailMessage draft = new MailMessage();
        draft.setFrom(organizer.fromAddress());
        List<MailAddress> to = new ArrayList<MailAddress>();
        for (ContactDirectory.ContactInfo c : invitees) {
            if (c != null && c.email != null && !c.email.isBlank()) {
                to.add(new MailAddress(c.displayName, c.email));
            }
        }
        draft.setTo(to);
        draft.setSubject("Invitation: " + a.getTitle()
                + " (" + HUMAN_DATE.format(date) + ")");
        draft.setTextBody(buildBody(a, date, organizer));
        return draft;
    }

    /** The invitation body text: what, when, who, and how to answer. */
    static String buildBody(final Appointment a, final LocalDate date,
            final MailAccount organizer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are invited to \"").append(a.getTitle()).append("\".\n\n");
        sb.append("When:  ").append(HUMAN_DATE.format(date)).append(", ")
                .append(hour(a.getStartHour())).append(" - ")
                .append(hour(a.getStartHour() + a.getDuration())).append('\n');
        sb.append("Organizer: ").append(organizer.getDisplayLabel()).append("\n\n");
        sb.append("Open the attached invite.ics to add this event to your ")
                .append("calendar and to accept, tentatively accept or decline ")
                .append("it.\n\n");
        sb.append("Sent from the Project Looking Glass agenda.\n");
        return sb.toString();
    }

    /** Wraps the iCalendar payload into the outgoing {@code invite.ics}. */
    public static MailAttachment buildAttachment(final String ics) {
        return MailAttachment.fromBytes(ICS_FILE_NAME, ICS_MIME_TYPE,
                ics.getBytes(StandardCharsets.UTF_8));
    }

    private static String hour(final int h) {
        return String.format(Locale.ROOT, "%02d:00", h);
    }

    private static String cnParam(final String cn) {
        if (cn == null || cn.isBlank()) {
            return "";
        }
        // A quoted param value may not contain a DQUOTE; drop any from the name.
        return ";CN=\"" + cn.replace("\"", "") + "\"";
    }

    /** RFC 5545 TEXT escaping: backslash, semicolon, comma and newlines. */
    static String escapeText(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /**
     * Folds one content line to the RFC 5545 75-octet limit: continuation
     * chunks are prefixed with a single space. Folding counts UTF-8 octets and
     * never splits a multi-byte character, so non-ASCII names stay intact.
     */
    static String foldLine(final String line) {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= FOLD_OCTETS) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int start = 0;
        boolean first = true;
        while (start < bytes.length) {
            // Reserve one octet for the continuation space on every line but the first.
            int budget = first ? FOLD_OCTETS : FOLD_OCTETS - 1;
            int end = Math.min(start + budget, bytes.length);
            // Back off a UTF-8 continuation byte (10xxxxxx) so a character is never split.
            while (end < bytes.length && (bytes[end] & 0xC0) == 0x80) {
                end--;
            }
            if (!first) {
                out.append(' ');
            }
            out.append(new String(bytes, start, end - start, StandardCharsets.UTF_8));
            out.append("\r\n");
            start = end;
            first = false;
        }
        // Trim the trailing CRLF; buildICalendar adds the line terminator itself.
        out.setLength(out.length() - 2);
        return out.toString();
    }
}
