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
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * The calendar/agenda popup behind the 2D taskbar clock: clicking the clock
 * opens a month grid (ISO Monday-first, today highlighted, statutory holidays
 * and weekend days tinted) with prev/next-month controls, and below it a small
 * "agenda" of the notifications raised today.
 *
 * <p>This is the thin Swing view over the pure {@link CalendarModel}; holiday and
 * weekend classification is delegated to {@link HolidayCalendar} (region-aware,
 * backed by {@code jbusinessday}), and the clock is injectable so the grid and
 * the agenda can be built headless in tests. The agenda filter
 * ({@link #agendaFor}) is a pure static helper. The popup is only ever
 * <em>shown</em> on a real display, but constructing and rebuilding it needs
 * none.</p>
 *
 * <p>The prev/next controls are {@link JMenuItem}s, not {@code JButton}s: a
 * {@link JPopupMenu} routes mouse events through Swing's {@code
 * MenuSelectionManager}, which only dispatches real clicks to {@code MenuElement}
 * children, so a button nested in a header panel is silently swallowed. Selecting
 * a nav item dismisses the popup, so {@link #next()}/{@link #prev()} re-open it on
 * the next EDT tick to keep it visible at the new month.</p>
 */
final class CalendarPopup {

    /** Today's cell background. */
    private static final Color TODAY_BACKGROUND = new Color(0x2F, 0x6F, 0xED);
    /** A neighbouring-month (padding) day's foreground. */
    private static final Color PADDED_FOREGROUND = Color.GRAY;
    /** Text drawn on the highlighted today cell. */
    private static final Color TODAY_FOREGROUND = Color.WHITE;
    /** A statutory holiday's foreground (a muted red, legible on the light popup). */
    private static final Color HOLIDAY_FOREGROUND = new Color(0xB0, 0x30, 0x30);
    /** A weekend (Sat/Sun) day's foreground (a muted blue). */
    private static final Color WEEKEND_FOREGROUND = new Color(0x3A, 0x5A, 0xA0);

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

    /**
     * Invoked with the date of a day cell the user double-clicked, or null when
     * the interaction is not wired. The 2D desktop sets this to open the Agenda
     * at that day.
     */
    private Consumer<LocalDate> onOpenDate;

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
            showMenu();
        }
    }

    /**
     * Advances to the next month. Because the prev/next controls are
     * {@link JMenuItem}s (a {@link JPopupMenu} only routes real clicks to
     * {@code MenuElement} children), selecting one dismisses the popup, so this
     * re-opens it on the next EDT tick to keep it visible at the new month.
     */
    void next() {
        viewMonth = viewMonth.plusMonths(1);
        rebuild();
        reshow();
    }

    /** Steps back to the previous month, re-opening the popup as {@link #next()} does. */
    void prev() {
        viewMonth = viewMonth.minusMonths(1);
        rebuild();
        reshow();
    }

    /** Positions and shows the popup just above the clock anchor. */
    private void showMenu() {
        menu.show(anchor, 0, -menu.getPreferredSize().height);
    }

    /**
     * Re-opens the popup after a nav item dismissed it. Deferred to the next EDT
     * tick so it runs after the menu-selection machinery finishes hiding the
     * popup; a no-op when there is no anchor (headless construction/tests).
     */
    private void reshow() {
        if (anchor == null) {
            return;
        }
        SwingUtilities.invokeLater(this::showMenu);
    }

    /** The month currently rendered. Package-private for tests. */
    YearMonth viewMonth() {
        return viewMonth;
    }

    /** The popup component. Package-private for tests. */
    JPopupMenu menu() {
        return menu;
    }

    /**
     * Sets the callback invoked with the date of a day cell the user
     * double-clicks; the 2D desktop wires this to open the Agenda at that day.
     * A null callback disables the interaction.
     */
    void setOnOpenDate(Consumer<LocalDate> onOpenDate) {
        this.onOpenDate = onOpenDate;
    }

    /**
     * Hides the popup and asks the desktop to open {@code date} in the Agenda.
     * Triggered by a double-click on a day cell; a no-op when no callback is
     * wired (e.g. headless tests that never set one).
     */
    private void openDate(LocalDate date) {
        if (onOpenDate == null || date == null) {
            return;
        }
        menu.setVisible(false);
        onOpenDate.accept(date);
    }

    /** Rebuilds the popup's contents for {@link #viewMonth}. */
    void rebuild() {
        menu.removeAll();
        // Nav controls are direct JMenuItem children, the only components a
        // JPopupMenu dispatches real clicks to (a JButton nested in a JPanel is
        // swallowed by the MenuSelectionManager and never fires).
        JMenuItem prevItem = new JMenuItem(PREV_TEXT);
        prevItem.setName("prev");
        prevItem.setHorizontalAlignment(SwingConstants.CENTER);
        prevItem.addActionListener(e -> prev());
        JMenuItem nextItem = new JMenuItem(NEXT_TEXT);
        nextItem.setName("next");
        nextItem.setHorizontalAlignment(SwingConstants.CENTER);
        nextItem.addActionListener(e -> next());
        title.setText(CalendarModel.title(viewMonth));
        title.setHorizontalAlignment(SwingConstants.CENTER);
        menu.add(prevItem);
        menu.add(title);
        menu.add(nextItem);
        menu.addSeparator();
        menu.add(buildGrid(new HolidayCalendar()));
        menu.addSeparator();
        for (JComponent row : buildAgenda()) {
            menu.add(row);
        }
    }

    private JComponent buildGrid(HolidayCalendar holidays) {
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
                } else if (holidays.isHoliday(date)) {
                    cell.setForeground(HOLIDAY_FOREGROUND);
                } else if (holidays.isWeekend(date)) {
                    cell.setForeground(WEEKEND_FOREGROUND);
                }
                if (!padded) {
                    // A day cell is a plain JLabel, not a MenuElement, so the
                    // popup's MenuSelectionManager ignores it and never
                    // dismisses on a click over it: the cell receives the
                    // physical mouse events through normal Swing dispatch and
                    // can therefore see the second click of a double-click.
                    final LocalDate cellDate = date;
                    cell.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            if (e.getClickCount() == 2) {
                                openDate(cellDate);
                            }
                        }
                    });
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
