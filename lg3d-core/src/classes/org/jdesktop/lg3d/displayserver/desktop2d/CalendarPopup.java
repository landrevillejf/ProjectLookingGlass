/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;

/**
 * The calendar/agenda popup behind the 2D taskbar clock: clicking the clock
 * opens a month grid (ISO Monday-first, today highlighted) with prev/next-month
 * buttons, and below it a small "agenda" of the notifications raised today.
 *
 * <p>This is the thin Swing view over the pure {@link CalendarModel}; the clock
 * is injectable so the grid and the agenda can be built headless in tests, and
 * the agenda filter ({@link #agendaFor}) is a pure static helper. The popup is
 * only ever <em>shown</em> on a real display, but constructing and rebuilding it
 * needs none.</p>
 */
final class CalendarPopup {

    /** Today's cell background. */
    private static final Color TODAY_BACKGROUND = new Color(0x2F, 0x6F, 0xED);
    /** A neighbouring-month (padding) day's foreground. */
    private static final Color PADDED_FOREGROUND = Color.GRAY;
    /** Text drawn on the highlighted today cell. */
    private static final Color TODAY_FOREGROUND = Color.WHITE;

    private static final String NO_EVENTS_LABEL = "No events today";
    private static final String PREV_TEXT = "\u00AB";
    private static final String NEXT_TEXT = "\u00BB";

    private final JLabel anchor;
    private final NotificationModel model;
    private final Clock clock;
    private final ZoneId zone;
    private final JPopupMenu menu = new JPopupMenu();
    private final JLabel title = new JLabel();

    private YearMonth viewMonth;

    CalendarPopup(JLabel anchor, NotificationModel model) {
        this(anchor, model, Clock.systemDefaultZone());
    }

    CalendarPopup(JLabel anchor, NotificationModel model, Clock clock) {
        this.anchor = anchor;
        this.model = model;
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
        this.zone = this.clock.getZone();
        this.viewMonth = CalendarModel.currentMonth(this.clock);
        if (anchor != null) {
            anchor.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    show();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    show();
                }
            });
        }
        rebuild();
    }

    /** Rebuilds for the current month and shows the popup above the clock. */
    void show() {
        viewMonth = CalendarModel.currentMonth(clock);
        rebuild();
        if (anchor != null) {
            int height = menu.getPreferredSize().height;
            menu.show(anchor, 0, -height);
        }
    }

    /** Advances to the next month. */
    void next() {
        viewMonth = viewMonth.plusMonths(1);
        rebuild();
    }

    /** Steps back to the previous month. */
    void prev() {
        viewMonth = viewMonth.minusMonths(1);
        rebuild();
    }

    /** The month currently rendered. Package-private for tests. */
    YearMonth viewMonth() {
        return viewMonth;
    }

    /** The popup component. Package-private for tests. */
    JPopupMenu menu() {
        return menu;
    }

    /** Rebuilds the popup's contents for {@link #viewMonth}. */
    void rebuild() {
        menu.removeAll();
        menu.add(buildHeader());
        menu.add(buildGrid());
        menu.addSeparator();
        for (JComponent row : buildAgenda()) {
            menu.add(row);
        }
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        JButton prevButton = new JButton(PREV_TEXT);
        prevButton.addActionListener(e -> prev());
        JButton nextButton = new JButton(NEXT_TEXT);
        nextButton.addActionListener(e -> next());
        title.setText(CalendarModel.title(viewMonth));
        title.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(prevButton, BorderLayout.WEST);
        header.add(title, BorderLayout.CENTER);
        header.add(nextButton, BorderLayout.EAST);
        return header;
    }

    private JComponent buildGrid() {
        JPanel grid = new JPanel(new GridLayout(0, CalendarModel.DAYS, 2, 2));
        for (String headerText : CalendarModel.weekdayHeaders()) {
            JLabel day = new JLabel(headerText, SwingConstants.CENTER);
            day.setForeground(PADDED_FOREGROUND);
            grid.add(day);
        }
        int[][] weeks = CalendarModel.weeksOfMonth(viewMonth);
        LocalDate today = CalendarModel.today(clock);
        for (int week = 0; week < CalendarModel.WEEKS; week++) {
            for (int day = 0; day < CalendarModel.DAYS; day++) {
                int dayOfMonth = weeks[week][day];
                boolean padded = dayOfMonth == CalendarModel.OUT_OF_MONTH;
                JLabel cell = new JLabel(
                        padded ? "" : Integer.toString(dayOfMonth),
                        SwingConstants.CENTER);
                LocalDate date = CalendarModel.cellDate(viewMonth, week, day);
                if (date.equals(today)) {
                    cell.setOpaque(true);
                    cell.setBackground(TODAY_BACKGROUND);
                    cell.setForeground(TODAY_FOREGROUND);
                } else if (padded) {
                    cell.setForeground(PADDED_FOREGROUND);
                }
                grid.add(cell);
            }
        }
        return grid;
    }

    private List<JComponent> buildAgenda() {
        List<Notification> all =
                (model == null) ? List.of() : model.notifications();
        List<Notification> todays =
                agendaFor(all, CalendarModel.today(clock), zone);
        List<JComponent> rows = new ArrayList<>();
        if (todays.isEmpty()) {
            JMenuItem none = new JMenuItem(NO_EVENTS_LABEL);
            none.setEnabled(false);
            rows.add(none);
            return rows;
        }
        for (Notification n : todays) {
            JMenuItem item = new JMenuItem(n.title());
            item.setToolTipText(n.message());
            item.setEnabled(false);
            rows.add(item);
        }
        return rows;
    }

    /**
     * The notifications raised on {@code day} in {@code zone}, in log order. Pure
     * so the agenda filter is unit-testable without a display or a live model.
     */
    static List<Notification> agendaFor(List<Notification> all, LocalDate day, ZoneId zone) {
        if (all == null || day == null || zone == null) {
            return List.of();
        }
        List<Notification> result = new ArrayList<>();
        for (Notification n : all) {
            if (n == null) {
                continue;
            }
            LocalDate raised =
                    Instant.ofEpochMilli(n.timestampMillis()).atZone(zone).toLocalDate();
            if (raised.equals(day)) {
                result.add(n);
            }
        }
        return result;
    }
}
