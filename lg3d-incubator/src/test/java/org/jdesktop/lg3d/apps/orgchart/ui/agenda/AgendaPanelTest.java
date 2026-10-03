/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link AgendaPanel}, the 2D/Swing counterpart of Agenda3D.
 * They drive the panel's editing actions and assert on the shared
 * {@link AppointmentStore} / {@link Appointment} model, including the same
 * day/hour/duration clamping the 3D app applies. The agenda lives in the user
 * Preferences tree, so each test clears {@code /agenda} first and afterwards;
 * the attendee address book (the shared
 * {@code org.jdesktop.lg3d.contacts.ContactStore}) is pointed at a temp dir
 * seeded with exactly one contact so the invite flow is deterministic and the
 * developer's real address book is never touched.
 */
class AgendaPanelTest {

    @TempDir
    Path contactsDir;

    @BeforeEach
    void clear() {
        remove("/agenda");
        System.setProperty(ContactStore.DIR_PROPERTY, contactsDir.toString());
        new ContactStore().add(new Contact("Test", "Person", "test@example.org"));
    }

    @AfterEach
    void cleanup() {
        remove("/agenda");
        System.clearProperty(ContactStore.DIR_PROPERTY);
    }

    private static void remove(String path) {
        try {
            if (Preferences.userRoot().nodeExists(path)) {
                Preferences.userRoot().node(path).removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    @Test
    void startsEmpty() {
        AgendaPanel panel = new AgendaPanel();
        assertEquals(0, panel.getAppointmentCount());
        assertNull(panel.getSelected());
    }

    @Test
    void newAppointmentAtCursorIsPersisted() {
        AgendaPanel panel = new AgendaPanel();
        panel.setCursor(2, 10);
        panel.newAppointment();
        assertEquals(1, panel.getAppointmentCount());
        Appointment a = panel.getSelected();
        assertNotNull(a);
        assertEquals(2, a.getDay());
        assertEquals(10, a.getStartHour());
        assertEquals(1, a.getDuration());
        assertEquals(1, new AppointmentStore().load().size());
    }

    @Test
    void cursorIsClampedToTheGrid() {
        AgendaPanel panel = new AgendaPanel();
        panel.setCursor(99, 99);
        assertEquals(AgendaPanel.DAYS - 1, panel.getCursorDay());
        assertEquals(AgendaPanel.END_HOUR - 1, panel.getCursorHour());
        panel.setCursor(-5, -5);
        assertEquals(0, panel.getCursorDay());
        assertEquals(AgendaPanel.START_HOUR, panel.getCursorHour());
    }

    @Test
    void titleEditIsSaved() {
        AgendaPanel panel = new AgendaPanel();
        panel.newAppointment();
        panel.commitTitle("Standup");
        assertEquals("Standup", panel.getSelected().getTitle());
        List<Appointment> reloaded = new AppointmentStore().load();
        assertEquals(1, reloaded.size());
        assertEquals("Standup", reloaded.get(0).getTitle());
    }

    @Test
    void dayHourAndDurationAreClamped() {
        AgendaPanel panel = new AgendaPanel();
        panel.setCursor(1, 10);
        panel.newAppointment();
        Appointment a = panel.getSelected();

        panel.setDay(99);
        assertEquals(AgendaPanel.DAYS - 1, a.getDay());
        panel.setDay(-3);
        assertEquals(0, a.getDay());

        // duration max is END_HOUR - startHour (18 - 10 = 8)
        panel.setDuration(99);
        assertEquals(AgendaPanel.END_HOUR - a.getStartHour(), a.getDuration());
        panel.setDuration(0);
        assertEquals(1, a.getDuration());

        // hour max is END_HOUR - duration
        panel.setHour(99);
        assertEquals(AgendaPanel.END_HOUR - a.getDuration(), a.getStartHour());
        panel.setHour(-9);
        assertEquals(AgendaPanel.START_HOUR, a.getStartHour());
    }

    @Test
    void inviteAndRemoveAttendee() {
        AgendaPanel panel = new AgendaPanel();
        panel.newAppointment();
        Appointment a = panel.getSelected();
        assertTrue(a.getAttendees().isEmpty());
        panel.addAttendee();
        assertEquals(1, a.getAttendees().size());
        // Adding the same contact twice is a no-op.
        panel.addAttendee();
        assertEquals(1, a.getAttendees().size());
        panel.removeAttendee();
        assertTrue(a.getAttendees().isEmpty());
    }

    @Test
    void deleteRemovesFromModelAndStore() {
        AgendaPanel panel = new AgendaPanel();
        panel.newAppointment();
        assertEquals(1, panel.getAppointmentCount());
        panel.deleteSelected();
        assertEquals(0, panel.getAppointmentCount());
        assertNull(panel.getSelected());
        assertEquals(0, new AppointmentStore().load().size());
    }

    @Test
    void dayAndHourEditsMoveTheCursorWhenNothingSelected() {
        AgendaPanel panel = new AgendaPanel();
        panel.setCursor(0, AgendaPanel.START_HOUR);
        panel.setDay(3);
        assertEquals(3, panel.getCursorDay());
        panel.setHour(13);
        assertEquals(13, panel.getCursorHour());
    }

    @Test
    void weekNavigationMovesTheLabel() {
        AgendaPanel panel = new AgendaPanel();
        panel.shiftWeeks(1);
        panel.shiftWeeks(-1);
        panel.shiftMonths(1);
        panel.shiftYears(1);
        panel.jumpToToday();
        // No exception and the grid still lists appointments by day-of-week.
        assertEquals(0, panel.appointmentsOnDay(0).size());
    }

    @Test
    void jumpToDateNavigatesToThatWeek() {
        AgendaPanel panel = new AgendaPanel();
        LocalDate friday = LocalDate.of(2026, 3, 20);
        panel.jumpToDate(friday);
        // The displayed week starts on that date's Monday and the cursor sits on
        // the day itself (0=Mon .. 4=Fri).
        assertEquals(LocalDate.of(2026, 3, 16), panel.dateFor(0));
        assertEquals(friday, panel.dateFor(4));
        assertEquals(4, panel.getCursorDay());
        // A null date is ignored, leaving the week where it was.
        panel.jumpToDate(null);
        assertEquals(LocalDate.of(2026, 3, 16), panel.dateFor(0));
    }

    @Test
    void weekendColumnsAreDetected() {
        AgendaPanel panel = new AgendaPanel();
        panel.jumpToToday();
        // Monday-first week: index 5 = Saturday, 6 = Sunday.
        assertTrue(panel.isWeekendColumn(5));
        assertTrue(panel.isWeekendColumn(6));
        assertFalse(panel.isWeekendColumn(0));
        assertFalse(panel.isWeekendColumn(4));
        assertEquals(DayOfWeek.SATURDAY, panel.dateFor(5).getDayOfWeek());
    }

    @Test
    void weekendColumnsAreTintedAndPlainBusinessDaysAreNot() {
        AgendaPanel panel = new AgendaPanel();
        panel.jumpToToday();
        assertNotNull(panel.columnTint(5));   // Saturday wash
        assertNotNull(panel.columnTint(6));   // Sunday wash
        // A weekday that is not a statutory holiday keeps a plain (null) tint.
        boolean foundPlainWeekday = false;
        for (int d = 0; d < 5; d++) {
            if (!panel.isHolidayColumn(d)) {
                assertNull(panel.columnTint(d));
                foundPlainWeekday = true;
                break;
            }
        }
        assertTrue(foundPlainWeekday);
    }

    @Test
    void businessDayIsNeitherWeekendNorHoliday() {
        AgendaPanel panel = new AgendaPanel();
        panel.jumpToToday();
        for (int d = 0; d < AgendaPanel.DAYS; d++) {
            assertEquals(!panel.isWeekendColumn(d) && !panel.isHolidayColumn(d),
                    panel.isBusinessDayColumn(d));
        }
    }

    @Test
    void holidayColumnsGetTheirOwnTint() {
        AgendaPanel panel = new AgendaPanel();
        panel.jumpToToday();
        for (int d = 0; d < AgendaPanel.DAYS; d++) {
            if (panel.isHolidayColumn(d)) {
                // A holiday tint always wins over the weekend tint.
                assertNotNull(panel.columnTint(d));
            }
        }
    }

    @Test
    void todayColumnIsOnlyMarkedInTheCurrentWeek() {
        AgendaPanel panel = new AgendaPanel();
        panel.jumpToToday();
        int today = panel.todayColumnInWeek();
        assertTrue(today >= 0 && today < AgendaPanel.DAYS);
        assertEquals(LocalDate.now(), panel.dateFor(today));
        panel.shiftWeeks(4);
        assertEquals(-1, panel.todayColumnInWeek());
    }

    // ------------------------------------------------------------------
    // Real invitations
    // ------------------------------------------------------------------

    /** Records what the panel hands to the sender instead of touching SMTP. */
    private static final class RecordingSender extends InvitationSender {
        volatile Appointment appointment;
        volatile LocalDate date;
        volatile List<ContactDirectory.ContactInfo> invitees;
        volatile int count = 1;

        RecordingSender() {
            super(null); // the override below never touches the manager
        }

        @Override
        public int send(Appointment a, LocalDate date,
                List<ContactDirectory.ContactInfo> invitees) {
            this.appointment = a;
            this.date = date;
            this.invitees = invitees;
            return count;
        }
    }

    @Test
    void sendInvitesSyncResolvesAttendeesAndReportsTheCount() throws Exception {
        AgendaPanel panel = new AgendaPanel();
        panel.setCursor(2, 10);
        panel.newAppointment();
        panel.addAttendee(); // the single seeded contact
        RecordingSender recorder = new RecordingSender();
        panel.invitationSender = recorder;

        Appointment a = panel.getSelected();
        String msg = panel.sendInvitesSync(a, panel.dateFor(a.getDay()));

        assertEquals("Invitation sent to 1 attendee.", msg);
        assertSame(a, recorder.appointment);
        assertEquals(panel.dateFor(2), recorder.date,
                "the invite is dated for the displayed week's occurrence");
        assertEquals(1, recorder.invitees.size());
        assertEquals(a.getAttendees().get(0), recorder.invitees.get(0).uid);
        assertEquals("test@example.org", recorder.invitees.get(0).email);

        recorder.count = 3;
        assertEquals("Invitations sent to 3 attendees.",
                panel.sendInvitesSync(a, recorder.date));
    }

    @Test
    void sendInvitesValidatesAndThenSendsOffTheEdt() throws Exception {
        AgendaPanel panel = new AgendaPanel();
        RecordingSender recorder = new RecordingSender();
        panel.invitationSender = recorder;

        // Nothing selected: a headless no-op, nothing reaches the sender.
        panel.sendInvites();
        assertNull(recorder.appointment);

        // Selected but without attendees: still refused.
        panel.setCursor(2, 10);
        panel.newAppointment();
        panel.sendInvites();
        assertNull(recorder.appointment);

        // With an attendee the daemon worker performs the send.
        panel.addAttendee();
        panel.sendInvites();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (recorder.appointment == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertNotNull(recorder.appointment);
        assertEquals(panel.dateFor(2), recorder.date);
        assertEquals(1, recorder.invitees.size());
    }
}
