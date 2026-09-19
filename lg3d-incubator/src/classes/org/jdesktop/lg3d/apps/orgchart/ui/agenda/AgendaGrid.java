/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.Geometry;
import org.jdesktop.lg3d.sg.GeometryArray;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.QuadArray;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.sg.TransparencyAttributes;
import org.jdesktop.lg3d.utils.action.ActionFloat3;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;

/**
 * A week-view agenda grid rendered entirely at runtime into a single texture:
 * seven day columns (Mon..Sun) by {@value #ROWS} one-hour rows starting at
 * {@link #START_HOUR}. User-created {@link Appointment}s are drawn as coloured
 * blocks; each block shows its title plus a presence chip per attendee,
 * resolved against the shared {@code /contacts} directory that {@code Contact3D}
 * populates (green = free, red = busy). This is the visible half of the one-way
 * Agenda3D &rarr; Contact3D interaction.
 *
 * <p>The grid follows the live-texture recipe used by {@code Histogram3D}: one
 * fixed-size {@link ImageComponent2D} with {@code ALLOW_IMAGE_WRITE} is attached
 * to a {@link Texture2D} once, off-live, and every later change only repaints the
 * {@code BufferedImage} and calls {@link ImageComponent2D#set} in place — no
 * texture is ever re-attached to the live scene graph.</p>
 *
 * <p>A left click maps the pick's local intersection back to a (day, hour) cell:
 * clicking an occupied cell selects that appointment, clicking an empty cell
 * moves the creation cursor there. Selection changes are reported through
 * {@link GridListener} so the host can refresh its control strip.</p>
 */
public class AgendaGrid extends Component3D {

    /** First hour row; also the default start hour for new appointments. */
    static final int START_HOUR = 8;
    /** One past the last hour row (rows run {@code START_HOUR}..{@code END_HOUR-1}). */
    static final int END_HOUR = 18;
    /** Number of day columns. */
    static final int DAYS = 7;
    /** Number of hour rows. */
    static final int ROWS = END_HOUR - START_HOUR;

    static final String[] DAY_NAMES = {
        "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"
    };

    // Power-of-two texture the grid is rasterized into.
    private static final int TW = 1024;
    private static final int TH = 512;

    // Image-space layout: hour-label gutter on the left, day-name header on top.
    private static final int GUTTER_PX = 64;
    private static final int HEADER_PX = 48;
    private static final float GRID_W = TW - GUTTER_PX;
    private static final float GRID_H = TH - HEADER_PX;
    private static final float COL_W = GRID_W / DAYS;
    private static final float ROW_H = GRID_H / ROWS;
    private static final int PAD = 3;

    private static final Color BG = new Color(0x0E, 0x14, 0x20, 0xF2);
    private static final Color HEADER_BG = new Color(0x1A, 0x2A, 0x44, 0xF8);
    private static final Color TODAY_BG = new Color(0x2A, 0x4A, 0x74, 0xF8);
    private static final Color GRID_LINE = new Color(255, 255, 255, 34);
    private static final Color AXIS_LINE = new Color(255, 255, 255, 80);
    private static final Color TEXT_DIM = new Color(198, 214, 236, 225);
    private static final Color CURSOR_FILL = new Color(120, 180, 255, 46);
    private static final Color SELECT_BORDER = new Color(255, 226, 120, 255);
    private static final Color FREE_CHIP = new Color(90, 214, 120, 255);
    private static final Color BUSY_CHIP = new Color(232, 92, 92, 255);
    private static final Color UNKNOWN_CHIP = new Color(150, 158, 170, 255);

    private static final Color[] BLOCK_COLORS = {
        new Color(70, 130, 220, 215),
        new Color(90, 178, 140, 215),
        new Color(206, 126, 92, 215),
        new Color(150, 122, 210, 215),
        new Color(80, 172, 196, 215),
    };

    private static final Font DAY_FONT = new Font("SansSerif", Font.BOLD, 22);
    private static final Font HOUR_FONT = new Font("SansSerif", Font.PLAIN, 16);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 16);

    /** Notified whenever a click changes the selection or creation cursor. */
    public interface GridListener {
        void gridSelectionChanged();
    }

    private final float width;
    private final float height;
    private final BufferedImage canvas;
    private final ImageComponent2D imageComponent;

    private List<Appointment> appointments = new ArrayList<Appointment>();
    private ContactDirectory directory;
    private GridListener listener;

    private Appointment selected;
    private int cursorDay;
    private int cursorHour = START_HOUR;

    public AgendaGrid(float width, float height) {
        this.width = width;
        this.height = height;
        this.cursorDay = todayIndex();

        canvas = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
        imageComponent = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, TW, TH, false, true);
        imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);

        Texture2D texture = new Texture2D(
                Texture2D.BASE_LEVEL, Texture2D.RGBA, TW, TH);
        texture.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
        texture.setBoundaryModeS(Texture2D.CLAMP);
        texture.setBoundaryModeT(Texture2D.CLAMP);
        texture.setImage(0, imageComponent);

        Appearance appearance = new Appearance();
        TextureAttributes texAttr = new TextureAttributes();
        texAttr.setTextureMode(TextureAttributes.REPLACE);
        appearance.setTextureAttributes(texAttr);
        appearance.setTexture(texture);
        appearance.setPolygonAttributes(new PolygonAttributes(
                PolygonAttributes.POLYGON_FILL, PolygonAttributes.CULL_NONE,
                0.0f, false, 0.0f));
        appearance.setTransparencyAttributes(new TransparencyAttributes(
                TransparencyAttributes.BLENDED, 0.0f,
                TransparencyAttributes.BLEND_SRC_ALPHA,
                TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA));

        float hx = width * 0.5f;
        float hy = height * 0.5f;
        QuadArray quad = new QuadArray(4,
                GeometryArray.COORDINATES | GeometryArray.TEXTURE_COORDINATE_2);
        quad.setCoordinates(0, new float[] {
            -hx, -hy, 0.0f,  hx, -hy, 0.0f,  hx, hy, 0.0f,  -hx, hy, 0.0f });
        quad.setTextureCoordinates(0, 0, new float[] {
            0.0f, 1.0f,  1.0f, 1.0f,  1.0f, 0.0f,  0.0f, 0.0f });
        // PICK_GEOMETRY needs this to report an intersection point for clicks.
        quad.setCapability(Geometry.ALLOW_INTERSECT);
        addChild(new Shape3D(quad, appearance));

        addListener(new MouseClickedEventAdapter(
                MouseEvent3D.ButtonId.BUTTON1, false, null,
                new ActionFloat3() {
                    public void performAction(LgEventSource source,
                            float x, float y, float z) {
                        handleClick(x, y);
                    }
                }));
        setCursor(Cursor3D.SMALL_CURSOR);

        refresh();
    }

    // ------------------------------------------------------------------
    // Host wiring
    // ------------------------------------------------------------------

    public void setDirectory(ContactDirectory directory) {
        this.directory = directory;
        refresh();
    }

    /** Adopts the host's live list; the host calls {@link #refresh()} on edits. */
    public void setAppointments(List<Appointment> appointments) {
        this.appointments = appointments;
        refresh();
    }

    public void setGridListener(GridListener listener) {
        this.listener = listener;
    }

    public Appointment getSelected() {
        return selected;
    }

    public void setSelected(Appointment appointment) {
        this.selected = appointment;
        if (appointment != null) {
            cursorDay = appointment.getDay();
            cursorHour = clampHour(appointment.getStartHour());
        }
        refresh();
    }

    public int getCursorDay() {
        return cursorDay;
    }

    public int getCursorHour() {
        return cursorHour;
    }

    public void setCursor(int day, int hour) {
        this.cursorDay = ((day % DAYS) + DAYS) % DAYS;
        this.cursorHour = clampHour(hour);
        refresh();
    }

    /** Clears the selection and moves the cursor to today's column. */
    public void jumpToToday() {
        this.selected = null;
        setCursor(todayIndex(), cursorHour);
    }

    static int clampHour(int hour) {
        return Math.max(START_HOUR, Math.min(END_HOUR - 1, hour));
    }

    /** Repaints the canvas and uploads it in place (safe on a live graph). */
    public final void refresh() {
        redraw();
        imageComponent.set(canvas);
    }

    // ------------------------------------------------------------------
    // Click mapping
    // ------------------------------------------------------------------

    private void handleClick(float x, float y) {
        int[] cell = pickCell(x, y);
        if (cell == null) {
            return;
        }
        cursorDay = cell[0];
        cursorHour = cell[1];
        selected = findAt(cursorDay, cursorHour);
        refresh();
        if (listener != null) {
            listener.gridSelectionChanged();
        }
    }

    /** Maps a local intersection point to a {@code {day, hour}} cell or null. */
    private int[] pickCell(float x, float y) {
        float nx = x / width + 0.5f;   // 0 at left edge, 1 at right edge
        float ny = 0.5f - y / height;  // 0 at top edge, 1 at bottom edge
        float px = nx * TW;
        float py = ny * TH;
        if (px < GUTTER_PX || py < HEADER_PX) {
            return null;
        }
        int day = (int) ((px - GUTTER_PX) / COL_W);
        int slot = (int) ((py - HEADER_PX) / ROW_H);
        if (day < 0 || day >= DAYS || slot < 0 || slot >= ROWS) {
            return null;
        }
        return new int[] { day, START_HOUR + slot };
    }

    private Appointment findAt(int day, int hour) {
        for (Appointment a : appointments) {
            if (a.getDay() == day
                    && hour >= a.getStartHour()
                    && hour < a.getStartHour() + a.getDuration()) {
                return a;
            }
        }
        return null;
    }

    private static int todayIndex() {
        int dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
        return (dow + 5) % 7; // SUNDAY(1)..SATURDAY(7) -> Mon=0..Sun=6
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void redraw() {
        Graphics2D g = canvas.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(BG);
            g.fillRect(0, 0, TW, TH);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawHeader(g);
            drawGutter(g);
            drawGridLines(g);
            drawCursor(g);
            for (int i = 0; i < appointments.size(); i++) {
                drawAppointment(g, appointments.get(i), i);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawHeader(Graphics2D g) {
        int today = todayIndex();
        g.setFont(DAY_FONT);
        FontMetrics fm = g.getFontMetrics();
        for (int d = 0; d < DAYS; d++) {
            int x0 = Math.round(GUTTER_PX + d * COL_W);
            int w = Math.round(COL_W);
            g.setColor(d == today ? TODAY_BG : HEADER_BG);
            g.fillRect(x0, 0, w, HEADER_PX);
            g.setColor(d == today ? Color.WHITE : TEXT_DIM);
            int tx = x0 + (w - fm.stringWidth(DAY_NAMES[d])) / 2;
            int ty = (HEADER_PX - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(DAY_NAMES[d], tx, ty);
        }
        g.setColor(AXIS_LINE);
        g.fillRect(0, HEADER_PX - 1, TW, 2);
    }

    private void drawGutter(Graphics2D g) {
        g.setColor(HEADER_BG);
        g.fillRect(0, HEADER_PX, GUTTER_PX, TH - HEADER_PX);
        g.setFont(HOUR_FONT);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(TEXT_DIM);
        for (int r = 0; r < ROWS; r++) {
            String label = String.valueOf(START_HOUR + r);
            int tx = GUTTER_PX - 8 - fm.stringWidth(label);
            int ty = Math.round(HEADER_PX + r * ROW_H + ROW_H / 2
                    - fm.getHeight() / 2 + fm.getAscent());
            g.drawString(label, tx, ty);
        }
        g.setColor(AXIS_LINE);
        g.fillRect(GUTTER_PX - 1, HEADER_PX, 2, TH - HEADER_PX);
    }

    private void drawGridLines(Graphics2D g) {
        g.setColor(GRID_LINE);
        for (int d = 1; d < DAYS; d++) {
            int x = Math.round(GUTTER_PX + d * COL_W);
            g.fillRect(x, HEADER_PX, 1, TH - HEADER_PX);
        }
        for (int r = 1; r < ROWS; r++) {
            int y = Math.round(HEADER_PX + r * ROW_H);
            g.fillRect(GUTTER_PX, y, TW - GUTTER_PX, 1);
        }
    }

    private void drawCursor(Graphics2D g) {
        int x = Math.round(GUTTER_PX + cursorDay * COL_W);
        int y = Math.round(HEADER_PX + (cursorHour - START_HOUR) * ROW_H);
        g.setColor(CURSOR_FILL);
        g.fillRect(x, y, Math.round(COL_W), Math.round(ROW_H));
    }

    private void drawAppointment(Graphics2D g, Appointment a, int index) {
        int startSlot = a.getStartHour() - START_HOUR;
        int dur = a.getDuration();
        if (startSlot < 0 || startSlot >= ROWS) {
            return;
        }
        dur = Math.min(dur, ROWS - startSlot);

        int x = Math.round(GUTTER_PX + a.getDay() * COL_W) + PAD;
        int y = Math.round(HEADER_PX + startSlot * ROW_H) + PAD;
        int w = Math.round(COL_W) - 2 * PAD;
        int h = Math.round(dur * ROW_H) - 2 * PAD;
        if (w <= 0 || h <= 0) {
            return;
        }

        Color fill = BLOCK_COLORS[index % BLOCK_COLORS.length];
        g.setColor(fill);
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(a == selected ? SELECT_BORDER : new Color(255, 255, 255, 120));
        g.setStroke(new BasicStroke(a == selected ? 3.0f : 1.5f));
        g.drawRoundRect(x, y, w - 1, h - 1, 10, 10);
        g.setStroke(new BasicStroke(1.0f));

        Shape oldClip = g.getClip();
        g.clipRect(x + 4, y + 2, w - 8, h - 4);
        g.setFont(TITLE_FONT);
        g.setColor(Color.WHITE);
        g.drawString(a.getTitle(), x + 7, y + 6 + g.getFontMetrics().getAscent());
        g.setClip(oldClip);

        drawAttendeeChips(g, a, x, y, w, h);
    }

    private void drawAttendeeChips(Graphics2D g, Appointment a,
            int x, int y, int w, int h) {
        List<String> attendees = a.getAttendees();
        if (attendees.isEmpty()) {
            return;
        }
        int dia = 10;
        int step = dia + 4;
        int maxChips = Math.max(0, (w - 12) / step);
        int cy = y + h - dia - 5;
        if (cy < y + 4) {
            return; // block too short to fit chips
        }
        for (int i = 0; i < attendees.size() && i < maxChips; i++) {
            ContactDirectory.ContactInfo info =
                    (directory == null) ? null : directory.get(attendees.get(i));
            if (info == null) {
                g.setColor(UNKNOWN_CHIP);
            } else {
                g.setColor(info.busy ? BUSY_CHIP : FREE_CHIP);
            }
            int cx = x + 6 + i * step;
            g.fillOval(cx, cy, dia, dia);
            g.setColor(new Color(0, 0, 0, 90));
            g.drawOval(cx, cy, dia, dia);
        }
    }
}
