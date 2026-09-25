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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.jdesktop.lg3d.utils.prefs.HolidayRegions;

/**
 * The 2D/Swing counterpart of the native-3D {@link Agenda3D} week agenda. It
 * reuses the very same AWT-free model and stores as the 3D app: appointments
 * persisted by {@link AppointmentStore} under {@code /agenda/appointments}, and
 * the shared {@code /contacts} directory (through {@link ContactDirectory}) that
 * supplies meeting attendees and their live free/busy presence. Because both
 * desktops read and write the same Preferences nodes, an appointment created in
 * one shows up in the other.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Agenda3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop. The panel touches
 * no Java 3D class: the week grid that {@code AgendaGrid} renders into a live
 * texture is here a plain custom-painted {@link JPanel}, and the grid constants
 * (08:00-18:00, seven days) are duplicated locally rather than referenced from
 * the {@code Component3D}-based {@code AgendaGrid}.</p>
 *
 * <p>Like {@code Agenda3D}, appointments are keyed by day-of-week (0=Monday ..
 * 6=Sunday) and hour, so they repeat every week; the navigation row only relabels
 * the displayed week's dates. Editing uses real Swing widgets (an editable title
 * field, day/hour/duration spinners, an attendee picker) instead of the 3D app's
 * preset-cycling buttons, and every accepted change is saved immediately.</p>
 *
 * <p>The grid marks weekends and statutory holidays exactly like the 3D
 * {@code AgendaGrid}: each column is anchored to a real date, weekend columns get
 * a blue wash, holiday columns a red wash, and the current week's today column an
 * accent bar. Classification is routed through the shared
 * {@link org.jdesktop.lg3d.utils.prefs.HolidayRegions} seam backed by the bundled
 * {@code jbusinessday} library, honouring the same persisted, locale-resolved
 * region (Control Center &rarr; Desktop, or {@code -Dlg.agenda.holidayRegion}) so
 * a region change is picked up on the next repaint, and degrading to plain
 * untinted columns if the library is ever absent. A legend in the header keys the
 * three washes.</p>
 */
public class AgendaPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 820;
    public static final int HEIGHT_PX = 600;

    /** Duplicated from the 3D {@code AgendaGrid} (which is a Component3D). */
    static final int START_HOUR = 8;
    static final int END_HOUR = 18;
    static final int DAYS = 7;
    static final int ROWS = END_HOUR - START_HOUR;

    private static final String[] DAY_NAMES = {
        "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"
    };
    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("d MMM");
    private static final DateTimeFormatter YEAR_FMT =
            DateTimeFormatter.ofPattern("yyyy");

    /** Weekend / statutory-holiday column washes, drawn behind the grid lines. */
    private static final Color WEEKEND_BODY = new Color(0x9A, 0xB4, 0xD0, 0x2A);
    private static final Color HOLIDAY_BODY = new Color(0xE0, 0x5A, 0x5A, 0x26);

    private final AppointmentStore store = new AppointmentStore();
    private final ContactDirectory directory = new ContactDirectory();
    private final List<Appointment> appointments = new ArrayList<Appointment>();

    private final WeekGrid grid = new WeekGrid();
    private final JLabel weekLabel = new JLabel(" ", JLabel.CENTER);
    private final JTextField titleField = new JTextField(12);
    private final JSpinner daySpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, DAYS - 1, 1));
    private final JSpinner hourSpinner =
            new JSpinner(new SpinnerNumberModel(START_HOUR, START_HOUR, END_HOUR - 1, 1));
    private final JSpinner durationSpinner =
            new JSpinner(new SpinnerNumberModel(1, 1, ROWS, 1));
    private final JComboBox<String> attendeeBox = new JComboBox<String>();
    private final List<String> attendeeUids = new ArrayList<String>();

    private Appointment selected;
    private LocalDate weekStart = LocalDate.now().with(DayOfWeek.MONDAY);

    /** Per-year statutory-holiday list from {@link HolidayRegions}, valid for
     *  whichever region {@link #cachedRegion} was built from. */
    private final Map<Integer, List<LocalDate>> holidayCache =
            new HashMap<Integer, List<LocalDate>>();
    /** The region {@link #holidayCache} holds; a Control Center &rarr; Desktop
     *  change (or {@code -Dlg.agenda.holidayRegion}) is picked up on the next
     *  redraw by clearing the cache. */
    private String cachedRegion;

    public AgendaPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        appointments.addAll(store.load());
        reloadAttendees();

        add(buildHeader(), BorderLayout.NORTH);
        add(new JScrollPane(grid), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        titleField.addActionListener(e -> commitTitle(titleField.getText()));
        daySpinner.addChangeListener(e -> onSpinner());
        hourSpinner.addChangeListener(e -> onSpinner());
        durationSpinner.addChangeListener(e -> onSpinner());

        refreshWeekLabel();
        syncEditors();
        grid.repaint();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        nav.add(navButton("Yr-", () -> shiftYears(-1)));
        nav.add(navButton("Mo-", () -> shiftMonths(-1)));
        nav.add(navButton("Wk-", () -> shiftWeeks(-1)));
        nav.add(navButton("Today", this::jumpToToday));
        nav.add(navButton("Wk+", () -> shiftWeeks(1)));
        nav.add(navButton("Mo+", () -> shiftMonths(1)));
        nav.add(navButton("Yr+", () -> shiftYears(1)));
        header.add(nav, BorderLayout.NORTH);

        weekLabel.setFont(weekLabel.getFont().deriveFont(Font.BOLD, 15f));
        weekLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 6, 4));

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        legend.setOpaque(false);
        legend.add(legendChip(new Color(0x9A, 0xB4, 0xD0), "Weekend"));
        legend.add(legendChip(new Color(0xE0, 0x5A, 0x5A), "Holiday"));
        legend.add(legendChip(new Color(0x3D, 0x6B, 0xC4), "Today"));
        header.add(legend, BorderLayout.CENTER);
        header.add(weekLabel, BorderLayout.SOUTH);
        return header;
    }

    /** A small coloured-square + label chip for the weekend/holiday/today legend. */
    private JLabel legendChip(Color color, String text) {
        JLabel chip = new JLabel("\u25A0 " + text);
        chip.setForeground(color);
        chip.setFont(chip.getFont().deriveFont(11f));
        return chip;
    }

    private JButton navButton(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel buildControls() {
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton newBtn = new JButton("New");
        newBtn.addActionListener(e -> newAppointment());
        JButton delBtn = new JButton("Delete");
        delBtn.addActionListener(e -> deleteSelected());
        row1.add(newBtn);
        row1.add(delBtn);
        row1.add(new JLabel("Title:"));
        row1.add(titleField);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        row2.add(new JLabel("Day:"));
        row2.add(daySpinner);
        row2.add(new JLabel("Hour:"));
        row2.add(hourSpinner);
        row2.add(new JLabel("Duration:"));
        row2.add(durationSpinner);
        row2.add(new JLabel("Attendee:"));
        row2.add(attendeeBox);
        JButton addBtn = new JButton("Invite");
        addBtn.addActionListener(e -> addAttendee());
        JButton remBtn = new JButton("Remove");
        remBtn.addActionListener(e -> removeAttendee());
        row2.add(addBtn);
        row2.add(remBtn);

        JPanel controls = new JPanel(new GridLayout(2, 1));
        controls.add(row1);
        controls.add(row2);
        return controls;
    }

    private void reloadAttendees() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<String>();
        attendeeUids.clear();
        for (ContactDirectory.ContactInfo c : directory.getContacts()) {
            attendeeUids.add(c.uid);
            model.addElement(c.displayName + (c.busy ? " (busy)" : " (free)"));
        }
        attendeeBox.setModel(model);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    void shiftWeeks(int n) {
        weekStart = weekStart.plusWeeks(n);
        refreshWeekLabel();
        grid.repaint();
    }

    void shiftMonths(int n) {
        weekStart = weekStart.plusMonths(n).with(DayOfWeek.MONDAY);
        refreshWeekLabel();
        grid.repaint();
    }

    void shiftYears(int n) {
        weekStart = weekStart.plusYears(n).with(DayOfWeek.MONDAY);
        refreshWeekLabel();
        grid.repaint();
    }

    void jumpToToday() {
        weekStart = LocalDate.now().with(DayOfWeek.MONDAY);
        refreshWeekLabel();
        grid.repaint();
    }

    /**
     * Navigates to the week containing {@code date} and places the creation
     * cursor on that day. Called reflectively by the 2D desktop (through
     * {@code Desktop2DAppRegistry.showDate}) when the user double-clicks a day
     * in the taskbar calendar popup, so the Agenda opens on that date.
     */
    public void jumpToDate(LocalDate date) {
        if (date == null) {
            return;
        }
        weekStart = date.with(DayOfWeek.MONDAY);
        grid.cursorDay = clampDay(date.getDayOfWeek().getValue() - 1);
        refreshWeekLabel();
        grid.repaint();
    }

    private void refreshWeekLabel() {
        LocalDate end = weekStart.plusDays(DAYS - 1);
        weekLabel.setText(MONTH_FMT.format(weekStart) + " \u2013 "
                + DAY_FMT.format(end) + ", " + YEAR_FMT.format(end));
    }

    // ------------------------------------------------------------------
    // Editing (mirrors Agenda3D's actions)
    // ------------------------------------------------------------------

    void setCursor(int day, int hour) {
        grid.cursorDay = clampDay(day);
        grid.cursorHour = clampHour(hour);
        grid.repaint();
    }

    int getCursorDay() {
        return grid.cursorDay;
    }

    int getCursorHour() {
        return grid.cursorHour;
    }

    void newAppointment() {
        Appointment a = new Appointment(store.newId(),
                Appointment.PRESET_TITLES[0],
                grid.cursorDay, grid.cursorHour, 1);
        appointments.add(a);
        store.save(a);
        setSelected(a);
    }

    void deleteSelected() {
        if (selected == null) {
            return;
        }
        appointments.remove(selected);
        store.delete(selected.getId());
        setSelected(null);
    }

    void commitTitle(String title) {
        if (selected == null || title == null) {
            return;
        }
        selected.setTitle(title);
        commit(selected);
    }

    void setDay(int day) {
        if (selected == null) {
            setCursor(day, grid.cursorHour);
            return;
        }
        selected.setDay(clampDay(day));
        commit(selected);
    }

    void setHour(int hour) {
        if (selected == null) {
            setCursor(grid.cursorDay, hour);
            return;
        }
        int max = END_HOUR - selected.getDuration();
        selected.setStartHour(Math.max(START_HOUR, Math.min(max, hour)));
        commit(selected);
    }

    void setDuration(int duration) {
        if (selected == null) {
            return;
        }
        int max = END_HOUR - selected.getStartHour();
        selected.setDuration(Math.max(1, Math.min(max, duration)));
        commit(selected);
    }

    void addAttendee() {
        if (selected == null) {
            return;
        }
        int idx = attendeeBox.getSelectedIndex();
        if (idx >= 0 && idx < attendeeUids.size()) {
            selected.addAttendee(attendeeUids.get(idx));
            commit(selected);
        }
    }

    void removeAttendee() {
        if (selected == null) {
            return;
        }
        List<String> attendees = selected.getAttendees();
        if (!attendees.isEmpty()) {
            attendees.remove(attendees.size() - 1);
            commit(selected);
        }
    }

    private void onSpinner() {
        if (selected == null || syncing) {
            return;
        }
        setDay((Integer) daySpinner.getValue());
        setHour((Integer) hourSpinner.getValue());
        setDuration((Integer) durationSpinner.getValue());
    }

    /** Persists an edit, keeps the selection and repaints. */
    private void commit(Appointment a) {
        store.save(a);
        setSelected(a);
    }

    void setSelected(Appointment a) {
        selected = a;
        syncEditors();
        grid.repaint();
    }

    Appointment getSelected() {
        return selected;
    }

    private boolean syncing;

    /** Pushes the selection into the editor widgets without re-triggering them. */
    private void syncEditors() {
        syncing = true;
        try {
            if (selected == null) {
                titleField.setText("");
                titleField.setEnabled(false);
                daySpinner.setValue(grid.cursorDay);
                hourSpinner.setValue(grid.cursorHour);
                durationSpinner.setValue(1);
                daySpinner.setEnabled(false);
                hourSpinner.setEnabled(false);
                durationSpinner.setEnabled(false);
            } else {
                titleField.setEnabled(true);
                titleField.setText(selected.getTitle());
                daySpinner.setEnabled(true);
                hourSpinner.setEnabled(true);
                durationSpinner.setEnabled(true);
                daySpinner.setValue(selected.getDay());
                hourSpinner.setValue(selected.getStartHour());
                durationSpinner.setValue(selected.getDuration());
            }
        } finally {
            syncing = false;
        }
    }

    private static int clampDay(int day) {
        return Math.max(0, Math.min(DAYS - 1, day));
    }

    private static int clampHour(int hour) {
        return Math.max(START_HOUR, Math.min(END_HOUR - 1, hour));
    }

    /** The appointments for a given day-of-week (0=Monday). */
    List<Appointment> appointmentsOnDay(int day) {
        List<Appointment> out = new ArrayList<Appointment>();
        for (Appointment a : appointments) {
            if (a.getDay() == day) {
                out.add(a);
            }
        }
        return out;
    }

    // Test/inspection accessors (package-private; the desktop does not use them).
    List<Appointment> getAppointments() {
        return appointments;
    }

    int getAppointmentCount() {
        return appointments.size();
    }

    // ------------------------------------------------------------------
    // Business-day / holiday awareness (shared with the 3D AgendaGrid)
    // ------------------------------------------------------------------

    /** The real calendar date shown in day column {@code d} (0=Monday). */
    LocalDate dateFor(int d) {
        return weekStart.plusDays(d);
    }

    /** Column of the real today within the displayed week, or -1 when today is
     *  not in this week (so the accent only shows on the current week). */
    int todayColumnInWeek() {
        long diff = ChronoUnit.DAYS.between(weekStart, LocalDate.now());
        return (diff >= 0 && diff < DAYS) ? (int) diff : -1;
    }

    /** True when column {@code d} falls on a Saturday or Sunday. */
    boolean isWeekendColumn(int d) {
        return HolidayRegions.isWeekend(dateFor(d));
    }

    /** True when column {@code d} is a statutory holiday in the current region. */
    boolean isHolidayColumn(int d) {
        LocalDate date = dateFor(d);
        return holidaysFor(date.getYear()).contains(date);
    }

    /** A business day is a non-weekend, non-holiday day. */
    boolean isBusinessDayColumn(int d) {
        return !isWeekendColumn(d) && !isHolidayColumn(d);
    }

    /** The body wash for column {@code d}, or {@code null} for a plain business day. */
    Color columnTint(int d) {
        if (isHolidayColumn(d)) {
            return HOLIDAY_BODY;
        }
        if (isWeekendColumn(d)) {
            return WEEKEND_BODY;
        }
        return null;
    }

    /**
     * The region token to mark holidays for: an explicit
     * {@code -Dlg.agenda.holidayRegion} system property wins (back-compat), else
     * the same persisted, locale-resolved {@link HolidayRegions#configuredRegion()}
     * the taskbar calendar and the 3D AgendaGrid use.
     */
    private String currentRegion() {
        String override = System.getProperty("lg.agenda.holidayRegion");
        return (override != null && !override.trim().isEmpty())
                ? HolidayRegions.resolveRegion(override, Locale.getDefault())
                : HolidayRegions.configuredRegion();
    }

    /** Holidays for {@code year} in the current region, cached via {@link HolidayRegions}. */
    private List<LocalDate> holidaysFor(int year) {
        String region = currentRegion();
        if (!region.equals(cachedRegion)) {
            holidayCache.clear();
            cachedRegion = region;
        }
        List<LocalDate> cached = holidayCache.get(year);
        if (cached == null) {
            cached = HolidayRegions.holidays(region, year);
            holidayCache.put(year, cached);
        }
        return cached;
    }

    // ------------------------------------------------------------------
    // The painted week grid
    // ------------------------------------------------------------------

    /** Custom-painted 7-day x hour week grid; the 2D analogue of AgendaGrid. */
    private final class WeekGrid extends JPanel {
        private static final int COL_W = 96;
        private static final int ROW_H = 36;
        private static final int HEADER_H = 28;

        private static final Color HEADER_BG = new Color(0x2B, 0x33, 0x40);
        private static final Color HEADER_WEEKEND = new Color(0x2A, 0x37, 0x4C);
        private static final Color HEADER_HOLIDAY = new Color(0x4A, 0x28, 0x30);
        private static final Color HEADER_TODAY = new Color(0x3D, 0x6B, 0xC4);
        private static final Color WEEKEND_TEXT = new Color(0xB8, 0xC8, 0xE0);
        private static final Color HOLIDAY_TEXT = new Color(0xFF, 0x9E, 0x9E);
        private static final Color GRID_LINE = new Color(0xDD, 0xE1, 0xE7);
        private static final Color HOUR_TEXT = new Color(0x8A, 0x93, 0xA0);
        private static final Color TODAY_WASH = new Color(0x3D, 0x6B, 0xC4, 0x14);

        private int cursorDay = 0;
        private int cursorHour = START_HOUR;

        WeekGrid() {
            setPreferredSize(new Dimension(COL_W * DAYS, HEADER_H + ROW_H * ROWS));
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    handleClick(e.getX(), e.getY());
                }
            });
        }

        private void handleClick(int x, int y) {
            int day = x / COL_W;
            if (day < 0 || day >= DAYS || y < HEADER_H) {
                return;
            }
            int hour = START_HOUR + (y - HEADER_H) / ROW_H;
            if (hour < START_HOUR || hour >= END_HOUR) {
                return;
            }
            Appointment hit = appointmentAt(day, hour);
            if (hit != null) {
                setSelected(hit);
            } else {
                cursorDay = day;
                cursorHour = hour;
                if (selected == null) {
                    syncEditors();
                }
                repaint();
            }
        }

        private Appointment appointmentAt(int day, int hour) {
            for (Appointment a : appointmentsOnDay(day)) {
                if (hour >= a.getStartHour()
                        && hour < a.getStartHour() + a.getDuration()) {
                    return a;
                }
            }
            return null;
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int bodyH = ROW_H * ROWS;
            int today = todayColumnInWeek();

            // Weekend / statutory-holiday column washes (business days stay plain).
            for (int d = 0; d < DAYS; d++) {
                Color tint = columnTint(d);
                if (tint != null) {
                    g.setColor(tint);
                    g.fillRect(d * COL_W, HEADER_H, COL_W, bodyH);
                }
            }
            // Today column accent (only when the displayed week holds today).
            if (today >= 0) {
                g.setColor(TODAY_WASH);
                g.fillRect(today * COL_W, HEADER_H, COL_W, bodyH);
            }

            // Day column headers, tinted per today / holiday / weekend.
            g.setFont(g.getFont().deriveFont(Font.BOLD));
            FontMetrics fm = g.getFontMetrics();
            for (int d = 0; d < DAYS; d++) {
                boolean holiday = isHolidayColumn(d);
                boolean weekend = !holiday && isWeekendColumn(d);
                g.setColor(d == today ? HEADER_TODAY
                        : holiday ? HEADER_HOLIDAY
                        : weekend ? HEADER_WEEKEND : HEADER_BG);
                g.fillRect(d * COL_W, 0, COL_W, HEADER_H);

                String label = DAY_NAMES[d] + " "
                        + DAY_FMT.format(weekStart.plusDays(d));
                g.setColor(d == today ? Color.WHITE
                        : holiday ? HOLIDAY_TEXT
                        : weekend ? WEEKEND_TEXT : Color.WHITE);
                g.drawString(label,
                        d * COL_W + (COL_W - fm.stringWidth(label)) / 2,
                        HEADER_H - 9);
            }

            // Hour rows.
            g.setFont(g.getFont().deriveFont(Font.PLAIN));
            for (int r = 0; r <= ROWS; r++) {
                int y = HEADER_H + r * ROW_H;
                g.setColor(GRID_LINE);
                g.drawLine(0, y, w, y);
                g.setColor(HOUR_TEXT);
                g.drawString(String.format("%02d:00", START_HOUR + r), 2, y + 12);
            }
            for (int d = 0; d <= DAYS; d++) {
                g.setColor(GRID_LINE);
                g.drawLine(d * COL_W, HEADER_H, d * COL_W, HEADER_H + bodyH);
            }
            // Today vertical accent bar.
            if (today >= 0) {
                g.setColor(HEADER_TODAY);
                g.fillRect(today * COL_W, HEADER_H, 2, bodyH);
            }
            // Creation cursor.
            if (selected == null) {
                g.setColor(new Color(0x3D, 0x6B, 0xC4, 0x33));
                g.fillRect(cursorDay * COL_W + 1,
                        HEADER_H + (cursorHour - START_HOUR) * ROW_H + 1,
                        COL_W - 2, ROW_H - 2);
            }
            // Appointment blocks.
            for (int d = 0; d < DAYS; d++) {
                for (Appointment a : appointmentsOnDay(d)) {
                    drawBlock(g, d, a);
                }
            }
        }

        private void drawBlock(Graphics g, int day, Appointment a) {
            int x = day * COL_W + 3;
            int y = HEADER_H + (a.getStartHour() - START_HOUR) * ROW_H + 2;
            int h = a.getDuration() * ROW_H - 4;
            boolean isSel = a == selected;
            g.setColor(isSel ? new Color(0x3D, 0x6B, 0xC4) : new Color(0x6F, 0x9B, 0xE0));
            g.fillRoundRect(x, y, COL_W - 6, h, 8, 8);
            g.setColor(isSel ? Color.YELLOW : new Color(0x2A, 0x3B, 0x55));
            g.drawRoundRect(x, y, COL_W - 6, h, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(a.getTitle(), x + 5, y + 14);
            // Attendee free/busy dots.
            int dotX = x + 6;
            for (String uid : a.getAttendees()) {
                ContactDirectory.ContactInfo c = directory.get(uid);
                boolean busy = c != null && c.busy;
                g.setColor(busy ? new Color(0xE0, 0x5A, 0x5A) : new Color(0x6B, 0xE0, 0x7A));
                g.fillOval(dotX, y + h - 10, 6, 6);
                dotX += 9;
            }
        }
    }
}
