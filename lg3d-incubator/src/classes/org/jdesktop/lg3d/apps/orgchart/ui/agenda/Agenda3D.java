/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D week-agenda that sits beside {@code Contact3D} in the same JVM
 * and reads the very same shared {@code /contacts} store {@code Contact3D}
 * populates. The interaction is deliberately one-way: {@code Agenda3D} never
 * writes to the contact data, it only invites those contacts to user-created
 * appointments and shows each invitee's live presence (free/busy) beside the
 * block, so you can see at a glance who is available.
 *
 * <p>The window is a plain {@link Frame3D} using the standard glassy window
 * decoration (title bar buttons plus the CTRL + right-click flip-to-sticky
 * gesture). Its body is an {@link AgendaGrid} week view over a strip of
 * {@link AgendaButton} controls. Appointments start empty and are persisted to
 * the user {@link java.util.prefs.Preferences} tree under
 * {@link AppointmentStore#ROOT}, mirroring how {@code Contact3D} persists
 * contacts, so the agenda survives across launches.</p>
 *
 * <p>Editing model: click a cell to select an existing appointment or to move
 * the creation cursor; {@code New} drops a one-hour block at the cursor; the
 * {@code Day/Hr/Dur} buttons nudge the selection (or the cursor when nothing is
 * selected); {@code Title} cycles a preset caption; {@code Att-}/{@code Att+}
 * remove/invite contacts from the shared directory. Every change is saved
 * immediately.</p>
 *
 * <p>A top navigation row ({@code Yr-}/{@code Mo-}/{@code Wk-} and their
 * {@code +} counterparts) cycles the displayed week back and forth through the
 * calendar, and {@code Today} snaps back to the current week. The grid's title
 * band shows the displayed week's month range and year, so the year is always
 * visible while paging.</p>
 *
 * <p>When launched with an ISO-8601 {@code yyyy-MM-dd} argument - as the 3D
 * calendar widget does when the user double-clicks a day - the grid opens on
 * that day's week instead of the current one (see {@link #parseInitialDate}).
 * Launched with no argument (the start menu), it opens on today as before.</p>
 */
public class Agenda3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    private ContactDirectory directory;
    private AppointmentStore store;
    private final List<Appointment> appointments = new ArrayList<Appointment>();
    private AgendaGrid grid;

    // Computed layout (physical world units).
    private float width;
    private float height;
    private float gridW;
    private float gridH;
    private float gridCenterY;
    private float btnW;
    private float btnH;
    private float startX;
    private float bottomY;
    private float colGap;
    private float rowGap;
    private int controlRows = 3;

    public static void main(String[] args) {
        Agenda3D agenda = new Agenda3D();
        LocalDate date = parseInitialDate(args);
        if (date != null) {
            agenda.jumpToDate(date);
        }
    }

    /**
     * Opens the Agenda on the week containing {@code date}. Invoked by
     * {@link #main} when the app was launched with a day to show (the 3D
     * calendar widget's double-click); a null date is a no-op.
     */
    public void jumpToDate(LocalDate date) {
        if (grid != null && date != null) {
            grid.jumpToDate(date);
        }
    }

    /**
     * Parses an optional ISO-8601 {@code yyyy-MM-dd} date from the launch args.
     * {@code AppLaunchAction} delivers a {@code java <main> <date>} command to
     * {@code main} as a single trailing string, so this scans every arg's
     * whitespace-delimited tokens and returns the first that parses as a date,
     * or null. A missing or malformed date never blocks the launch.
     */
    static LocalDate parseInitialDate(String[] args) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            for (String token : arg.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    return LocalDate.parse(token);
                } catch (RuntimeException ignored) {
                    // Not a date token; keep scanning the remaining args.
                }
            }
        }
        return null;
    }

    public Agenda3D() {
        super();
        try {
            setName("Agenda 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            initData();
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Agenda3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;
        width = height * 1.6f;

        float topMargin = height * 0.10f;    // clears the corner window buttons
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.21f;     // three rows: nav / edit / move
        float gap = height * 0.025f;

        gridH = height - topMargin - bottomMargin - controlH - gap;
        gridW = gridH * 2.0f;                // match the 2:1 grid texture
        float maxW = width * 0.96f;
        if (gridW > maxW) {
            gridW = maxW;
            gridH = gridW / 2.0f;
        }
        gridCenterY = height * 0.5f - topMargin - gridH * 0.5f;

        int cols = 6;
        colGap = gridW * 0.012f;
        btnW = (gridW - (cols - 1) * colGap) / cols;
        rowGap = controlH * 0.06f;
        btnH = (controlH - (controlRows - 1) * rowGap) / controlRows;
        startX = -gridW * 0.5f + btnW * 0.5f;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void initData() {
        directory = new ContactDirectory();
        store = new AppointmentStore();
        appointments.addAll(store.load());
    }

    private void createUI() {
        grid = new AgendaGrid(gridW, gridH);
        grid.setTranslation(0.0f, gridCenterY, 0.001f);
        grid.setDirectory(directory);
        grid.setAppointments(appointments);
        addChild(grid);

        // Navigation row: cycle the displayed week / month / year back and
        // forth; "Today" (middle row) snaps back to the current week.
        addButton("Yr-", 0, 0, new Runnable() {
            public void run() { grid.shiftYears(-1); } });
        addButton("Mo-", 1, 0, new Runnable() {
            public void run() { grid.shiftMonths(-1); } });
        addButton("Wk-", 2, 0, new Runnable() {
            public void run() { grid.shiftWeeks(-1); } });
        addButton("Wk+", 3, 0, new Runnable() {
            public void run() { grid.shiftWeeks(1); } });
        addButton("Mo+", 4, 0, new Runnable() {
            public void run() { grid.shiftMonths(1); } });
        addButton("Yr+", 5, 0, new Runnable() {
            public void run() { grid.shiftYears(1); } });

        // Middle row: create / delete / rename / jump / attendees.
        addButton("New", 0, 1, new Runnable() {
            public void run() { newAppointment(); } });
        addButton("Del", 1, 1, new Runnable() {
            public void run() { deleteSelected(); } });
        addButton("Title", 2, 1, new Runnable() {
            public void run() { cycleTitle(); } });
        addButton("Today", 3, 1, new Runnable() {
            public void run() { grid.jumpToToday(); } });
        addButton("Att-", 4, 1, new Runnable() {
            public void run() { removeAttendee(); } });
        addButton("Att+", 5, 1, new Runnable() {
            public void run() { addAttendee(); } });

        // Bottom row: move / resize the selection (or the cursor when none).
        addButton("Day-", 0, 2, new Runnable() {
            public void run() { adjustDay(-1); } });
        addButton("Day+", 1, 2, new Runnable() {
            public void run() { adjustDay(1); } });
        addButton("Hr-", 2, 2, new Runnable() {
            public void run() { adjustHour(-1); } });
        addButton("Hr+", 3, 2, new Runnable() {
            public void run() { adjustHour(1); } });
        addButton("Dur-", 4, 2, new Runnable() {
            public void run() { adjustDuration(-1); } });
        addButton("Dur+", 5, 2, new Runnable() {
            public void run() { adjustDuration(1); } });
    }

    private void addButton(String label, int col, int row, Runnable action) {
        AgendaButton button = new AgendaButton(label, btnW, btnH,
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        action.run();
                    }
                });
        button.setTranslation(colX(col), rowY(row), 0.002f);
        addChild(button);
    }

    private float colX(int col) {
        return startX + col * (btnW + colGap);
    }

    private float rowY(int row) {
        return bottomY + (controlRows - 1 - row) * (btnH + rowGap);
    }

    // ------------------------------------------------------------------
    // Editing actions
    // ------------------------------------------------------------------

    private void newAppointment() {
        Appointment a = new Appointment(store.newId(),
                Appointment.PRESET_TITLES[0],
                grid.getCursorDay(), grid.getCursorHour(), 1);
        appointments.add(a);
        store.save(a);
        grid.setSelected(a);
    }

    private void deleteSelected() {
        Appointment a = grid.getSelected();
        if (a == null) {
            return;
        }
        appointments.remove(a);
        store.delete(a.getId());
        grid.setSelected(null);
    }

    private void cycleTitle() {
        Appointment a = grid.getSelected();
        if (a == null) {
            return;
        }
        a.nextTitle();
        commit(a);
    }

    private void adjustDay(int delta) {
        Appointment a = grid.getSelected();
        if (a != null) {
            a.setDay(Math.max(0, Math.min(AgendaGrid.DAYS - 1,
                    a.getDay() + delta)));
            commit(a);
        } else {
            grid.setCursor(grid.getCursorDay() + delta, grid.getCursorHour());
        }
    }

    private void adjustHour(int delta) {
        Appointment a = grid.getSelected();
        if (a != null) {
            int max = AgendaGrid.END_HOUR - a.getDuration();
            a.setStartHour(Math.max(AgendaGrid.START_HOUR,
                    Math.min(max, a.getStartHour() + delta)));
            commit(a);
        } else {
            grid.setCursor(grid.getCursorDay(), grid.getCursorHour() + delta);
        }
    }

    private void adjustDuration(int delta) {
        Appointment a = grid.getSelected();
        if (a == null) {
            return;
        }
        int max = AgendaGrid.END_HOUR - a.getStartHour();
        a.setDuration(Math.max(1, Math.min(max, a.getDuration() + delta)));
        commit(a);
    }

    private void addAttendee() {
        Appointment a = grid.getSelected();
        if (a == null) {
            return;
        }
        for (ContactDirectory.ContactInfo c : directory.getContacts()) {
            if (!a.hasAttendee(c.uid)) {
                a.addAttendee(c.uid);
                break;
            }
        }
        commit(a);
    }

    private void removeAttendee() {
        Appointment a = grid.getSelected();
        if (a == null) {
            return;
        }
        List<String> attendees = a.getAttendees();
        if (!attendees.isEmpty()) {
            attendees.remove(attendees.size() - 1);
        }
        commit(a);
    }

    /** Persists an edit and refreshes the grid, keeping the cursor on it. */
    private void commit(Appointment a) {
        store.save(a);
        grid.setSelected(a);
    }
}
