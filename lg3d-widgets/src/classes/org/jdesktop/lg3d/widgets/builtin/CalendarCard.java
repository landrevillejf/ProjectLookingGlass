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
package org.jdesktop.lg3d.widgets.builtin;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Consumer;
import org.jdesktop.lg3d.displayserver.desktop2d.CalendarModel;
import org.jdesktop.lg3d.displayserver.desktop2d.HolidayCalendar;

/**
 * A month calendar for the desktop: the 3D counterpart of the 2D taskbar clock's
 * {@code CalendarPopup}. It lays the current month out as the same ISO
 * Monday-first grid, highlights today, tints statutory holidays and weekend days,
 * steps between months with the {@code <} / {@code >} header arrows (or the mouse
 * wheel), and - on a double-click of a day cell - asks its host to open that day
 * in the Agenda.
 *
 * <p>The month layout is delegated to the pure {@link CalendarModel} and the
 * holiday/weekend classification to {@link HolidayCalendar}, so this card marks
 * exactly the days the 2D popup does, from the same persisted region preference.
 * Like every built-in card it holds <em>no</em> reference to Java 3D: the 3D
 * {@link CalendarWidget} hosts it on a {@code SwingNode}, and the 2D desktop can
 * drop the same card straight onto its widget layer.</p>
 *
 * <p>Interaction is driven from {@code mouseClicked} rather than Swing buttons:
 * a hosted {@code SwingNode} panel reliably receives {@code MOUSE_CLICKED} (with
 * the original click count preserved) but not always the {@code PRESSED} /
 * {@code RELEASED} pair a {@code JButton} needs, so the nav arrows are painted
 * regions resolved by the pure {@link #navAt} / {@link #dayAt} hit-tests. Both
 * helpers are static and take their geometry explicitly, so the mapping from a
 * pixel to a month-navigation direction or a {@link LocalDate} is unit-testable
 * headless.</p>
 */
public class CalendarCard extends WidgetCard {

    /** The widget type id, matching the persisted {@code type} and the spec. */
    public static final String ID = "calendar";

    /** Default card width in pixels. */
    static final int WIDTH = 192;
    /** Default card height in pixels. */
    static final int HEIGHT = 216;

    // Fixed body geometry, shared by the painter and the hit-tests so a click
    // always resolves against the pixels that were drawn.
    /** Left/right inset of the grid and nav row. */
    static final int PAD_X = 8;
    /** Height of the month-title / prev-next nav row. */
    static final int NAV_H = 18;
    /** Height of the single-letter weekday header row. */
    static final int HEAD_H = 15;
    /** Bottom inset of the grid. */
    static final int BOTTOM_PAD = 8;
    /** Click-zone width of each nav arrow. */
    static final int NAV_W = 22;

    /** {@link #navAt} result: the click was not on a nav arrow. */
    static final int NAV_NONE = 0;
    /** {@link #navAt} result: the click was on the previous-month arrow. */
    static final int NAV_PREV = -1;
    /** {@link #navAt} result: the click was on the next-month arrow. */
    static final int NAV_NEXT = 1;

    private static final String PREV_GLYPH = "<";
    private static final String NEXT_GLYPH = ">";

    /** Today's cell background (matches the 2D popup's highlight). */
    private static final Color TODAY_BG = new Color(0x2F, 0x6F, 0xED);
    /** Text drawn on the highlighted today cell. */
    private static final Color TODAY_FG = Color.WHITE;
    /** A statutory holiday's foreground (a red legible on the dark card). */
    private static final Color HOLIDAY_FG = new Color(0xE8, 0x8A, 0x8A);
    /** A weekend (Sat/Sun) day's foreground (a soft blue). */
    private static final Color WEEKEND_FG = new Color(0x8F, 0xB4, 0xE8);
    /** A neighbouring-month (padding) cell's foreground. */
    private static final Color PADDED_FG = new Color(88, 98, 112);

    private final Clock clock;
    /** The holiday/weekend classifier, or null when it could not be built. */
    private final HolidayCalendar holidays;

    private volatile YearMonth viewMonth;
    private volatile LocalDate today;
    /** The content-top inset captured on the last paint, for click mapping. */
    private volatile int bodyTop = 22;

    /** Invoked with the date of a double-clicked day cell; null disables it. */
    private Consumer<LocalDate> onOpenDate;

    public CalendarCard() {
        this(Clock.systemDefaultZone());
    }

    /** Injectable-clock constructor so tests can pin "today" and the month. */
    CalendarCard(Clock clock) {
        super("Calendar", WIDTH, HEIGHT);
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
        this.viewMonth = CalendarModel.currentMonth(this.clock);
        this.today = CalendarModel.today(this.clock);
        this.holidays = buildHolidays();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e.getX(), e.getY(), e.getClickCount());
            }
        });
    }

    private static HolidayCalendar buildHolidays() {
        try {
            return new HolidayCalendar();
        } catch (Throwable t) {
            // No jbusinessday/slf4j runtime: weekends still tint (pure
            // DayOfWeek), holidays are simply left unmarked.
            return null;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    /** Refreshes the "today" highlight once a minute (cheap; no I/O). */
    @Override
    public long tickPeriodMillis() {
        return 60_000L;
    }

    @Override
    public void tick() {
        today = CalendarModel.today(clock);
        repaint();
    }

    /** Wheel down advances a month, wheel up steps back. */
    @Override
    public void onWheel(int rotation) {
        viewMonth = (rotation > 0) ? viewMonth.plusMonths(1) : viewMonth.minusMonths(1);
        repaint();
    }

    /**
     * Sets the callback invoked with the date of a day cell the user
     * double-clicks; the 3D host wires this to open the Agenda at that day. A
     * null callback disables the interaction.
     */
    public void setOnOpenDate(Consumer<LocalDate> onOpenDate) {
        this.onOpenDate = onOpenDate;
    }

    /** The month currently displayed. Package-private for tests. */
    YearMonth viewMonth() {
        return viewMonth;
    }

    /** The content-top inset used for click mapping. Package-private for tests. */
    int bodyTop() {
        return bodyTop;
    }

    /** Steps to the previous month. Package-private for tests/host. */
    void prevMonth() {
        viewMonth = viewMonth.minusMonths(1);
        repaint();
    }

    /** Steps to the next month. Package-private for tests/host. */
    void nextMonth() {
        viewMonth = viewMonth.plusMonths(1);
        repaint();
    }

    /** Resolves a click at {@code (x, y)} to navigation or a day open. Package-private for tests. */
    void handleClick(int x, int y, int clickCount) {
        int w = getWidth();
        int h = getHeight();
        int top = bodyTop;
        if (clickCount >= 2) {
            LocalDate date = dayAt(x, y, w, h, top, viewMonth);
            if (date != null) {
                openDate(date);
            }
            return;
        }
        int nav = navAt(x, y, w, top);
        if (nav == NAV_PREV) {
            prevMonth();
        } else if (nav == NAV_NEXT) {
            nextMonth();
        }
    }

    private void openDate(LocalDate date) {
        Consumer<LocalDate> cb = onOpenDate;
        if (cb != null && date != null) {
            cb.accept(date);
        }
    }

    // ------------------------------------------------------------------
    // Pure geometry / hit-testing (headless-testable)
    // ------------------------------------------------------------------

    /** Y of the first day-cell row for a content area starting at {@code top}. */
    static int gridTop(int top) {
        return top + NAV_H + HEAD_H;
    }

    /** Y just past the last day-cell row for a card of height {@code h}. */
    static int gridBottom(int h) {
        return h - BOTTOM_PAD;
    }

    /**
     * Which nav arrow (if any) a single click at {@code (x, y)} lands on, given
     * the card width {@code w} and content-top {@code top}. Returns
     * {@link #NAV_PREV}, {@link #NAV_NEXT} or {@link #NAV_NONE}.
     */
    static int navAt(int x, int y, int w, int top) {
        if (y < top || y >= top + NAV_H) {
            return NAV_NONE;
        }
        if (x >= PAD_X && x < PAD_X + NAV_W) {
            return NAV_PREV;
        }
        if (x >= w - PAD_X - NAV_W && x < w - PAD_X) {
            return NAV_NEXT;
        }
        return NAV_NONE;
    }

    /**
     * The date of the day cell under {@code (x, y)} in {@code month}, or null
     * when the point is off the grid or on a neighbouring-month padding cell.
     * Pure: recomputes the month grid from {@link CalendarModel}.
     */
    static LocalDate dayAt(int x, int y, int w, int h, int top, YearMonth month) {
        if (month == null) {
            return null;
        }
        int gt = gridTop(top);
        int gb = gridBottom(h);
        int gl = PAD_X;
        int gr = w - PAD_X;
        if (gb <= gt || gr <= gl) {
            return null;
        }
        if (y < gt || y >= gb || x < gl || x >= gr) {
            return null;
        }
        double colW = (gr - gl) / (double) CalendarModel.DAYS;
        double rowH = (gb - gt) / (double) CalendarModel.WEEKS;
        int col = (int) ((x - gl) / colW);
        int row = (int) ((y - gt) / rowH);
        if (col < 0 || col >= CalendarModel.DAYS || row < 0 || row >= CalendarModel.WEEKS) {
            return null;
        }
        int[][] weeks = CalendarModel.weeksOfMonth(month);
        if (weeks[row][col] == CalendarModel.OUT_OF_MONTH) {
            return null;
        }
        return CalendarModel.cellDate(month, row, col);
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    @Override
    protected void paintContent(Graphics2D g2, int w, int h, int top) {
        this.bodyTop = top;   // capture for click mapping (same geometry)
        YearMonth month = viewMonth;
        LocalDate now = today;

        paintNav(g2, w, top, month);
        paintHeaders(g2, w, top);
        paintDays(g2, w, h, top, month, now);
    }

    private void paintNav(Graphics2D g2, int w, int top, YearMonth month) {
        Font base = g2.getFont();
        g2.setFont(base.deriveFont(Font.BOLD, 13f));
        FontMetrics navFm = g2.getFontMetrics();
        int midY = top + NAV_H / 2;
        g2.setColor(TITLE_COLOR);
        g2.drawString(PREV_GLYPH, PAD_X + 4, midY + navFm.getAscent() / 2 - 1);
        g2.drawString(NEXT_GLYPH, w - PAD_X - 4 - navFm.stringWidth(NEXT_GLYPH),
                midY + navFm.getAscent() / 2 - 1);

        g2.setFont(base.deriveFont(Font.BOLD, 12f));
        FontMetrics tfm = g2.getFontMetrics();
        String title = CalendarModel.title(month);
        g2.setColor(TEXT);
        g2.drawString(title, (w - tfm.stringWidth(title)) / 2,
                midY + tfm.getAscent() / 2 - 1);
    }

    private void paintHeaders(Graphics2D g2, int w, int top) {
        Font base = g2.getFont();
        g2.setFont(base.deriveFont(Font.PLAIN, 10f));
        FontMetrics fm = g2.getFontMetrics();
        double colW = (w - 2 * PAD_X) / (double) CalendarModel.DAYS;
        int y = top + NAV_H + HEAD_H / 2 + fm.getAscent() / 2 - 1;
        String[] headers = CalendarModel.weekdayHeaders();
        g2.setColor(TEXT_DIM);
        for (int c = 0; c < CalendarModel.DAYS; c++) {
            int cx = (int) (PAD_X + c * colW + colW / 2);
            g2.drawString(headers[c], cx - fm.stringWidth(headers[c]) / 2, y);
        }
    }

    private void paintDays(Graphics2D g2, int w, int h, int top, YearMonth month, LocalDate now) {
        Font base = g2.getFont();
        g2.setFont(base.deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g2.getFontMetrics();

        int gt = gridTop(top);
        int gb = gridBottom(h);
        int gl = PAD_X;
        int gr = w - PAD_X;
        if (gb <= gt || gr <= gl) {
            return;
        }
        double colW = (gr - gl) / (double) CalendarModel.DAYS;
        double rowH = (gb - gt) / (double) CalendarModel.WEEKS;
        int[][] weeks = CalendarModel.weeksOfMonth(month);

        for (int row = 0; row < CalendarModel.WEEKS; row++) {
            for (int col = 0; col < CalendarModel.DAYS; col++) {
                int dayOfMonth = weeks[row][col];
                int x = gl + (int) Math.round(col * colW);
                int y = gt + (int) Math.round(row * rowH);
                int cw = (int) Math.round(colW);
                int ch = (int) Math.round(rowH);
                if (dayOfMonth == CalendarModel.OUT_OF_MONTH) {
                    continue;   // leave padding cells blank
                }
                LocalDate date = CalendarModel.cellDate(month, row, col);
                boolean isToday = date.equals(now);
                if (isToday) {
                    g2.setColor(TODAY_BG);
                    g2.fillRoundRect(x + 1, y + 1, cw - 2, ch - 2, 8, 8);
                    g2.setColor(TODAY_FG);
                } else if (isHoliday(date)) {
                    g2.setColor(HOLIDAY_FG);
                } else if (isWeekend(date)) {
                    g2.setColor(WEEKEND_FG);
                } else {
                    g2.setColor(TEXT);
                }
                String label = Integer.toString(dayOfMonth);
                int tx = x + (cw - fm.stringWidth(label)) / 2;
                int ty = y + (ch - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(label, tx, ty);
            }
        }
    }

    private boolean isHoliday(LocalDate date) {
        if (holidays == null) {
            return false;
        }
        try {
            return holidays.isHoliday(date);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
