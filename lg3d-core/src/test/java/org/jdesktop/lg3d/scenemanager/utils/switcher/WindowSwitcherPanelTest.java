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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link WindowSwitcherPanel}: the fixed footprint that
 * keeps the hosting {@code SwingNode} texture constant, the row cap, the
 * session/clear state, the {@link WindowSwitcherPanel#ellipsize} truncation and
 * the {@link WindowSwitcherPanel#cardHeight} layout maths, and the painting
 * (rendered onto a {@link BufferedImage}, which is how the card reaches the
 * screen as a texture). Nothing here touches Java 3D: the scene-graph host
 * {@link WindowSwitcher3D} and the event glue in {@link WindowSwitcherPlugin}
 * are probe-verified separately.
 */
class WindowSwitcherPanelTest {

    private static final int PAD = WindowSwitcherPanel.PAD_PX;
    private static final int ROW = WindowSwitcherPanel.ROW_HEIGHT_PX;
    private static final int SELECT = WindowSwitcherPanel.SELECT_FILL.getRGB();

    /** Renders the panel offscreen at its preferred size, like the SwingNode does. */
    private static BufferedImage render(WindowSwitcherPanel p) {
        Dimension d = p.getPreferredSize();
        p.setSize(d);
        BufferedImage image = new BufferedImage(d.width, d.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            p.paintComponent(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** How many pixels carry any paint (non-transparent). */
    private static int ink(BufferedImage image) {
        int inked = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    inked++;
                }
            }
        }
        return inked;
    }

    /** The row bands (0-based) that carry the opaque selection fill. */
    private static List<Integer> selectedRows(BufferedImage image) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < WindowSwitcherPanel.MAX_ROWS + 2; i++) {
            int y0 = PAD + i * ROW;
            int y1 = y0 + ROW - 4;
            boolean found = false;
            for (int y = y0; y < y1 && y < image.getHeight() && !found; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if (image.getRGB(x, y) == SELECT) {
                        found = true;
                        break;
                    }
                }
            }
            if (found) {
                rows.add(i);
            }
        }
        return rows;
    }

    private static FontMetrics metrics() {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
            return g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    // ----------------------------------------------------------------- layout

    @Test
    @DisplayName("the panel keeps a fixed footprint for its whole life")
    void fixedFootprint() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        Dimension d = p.getPreferredSize();
        assertEquals(WindowSwitcherPanel.MAX_WIDTH_PX, d.width);
        assertEquals(WindowSwitcherPanel.cardHeight(WindowSwitcherPanel.MAX_ROWS), d.height);
        assertFalse(p.isOpaque());
    }

    @Test
    @DisplayName("cardHeight grows one row at a time and is 0 for none")
    void cardHeightMaths() {
        assertEquals(0, WindowSwitcherPanel.cardHeight(0));
        assertEquals(0, WindowSwitcherPanel.cardHeight(-3));
        assertEquals(PAD * 2 + ROW, WindowSwitcherPanel.cardHeight(1));
        assertEquals(PAD * 2 + 3 * ROW, WindowSwitcherPanel.cardHeight(3));
        assertEquals(ROW,
                WindowSwitcherPanel.cardHeight(4) - WindowSwitcherPanel.cardHeight(3));
    }

    // ------------------------------------------------------------------ state

    @Test
    @DisplayName("a fresh panel is idle with no rows and no selection")
    void startsIdle() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        assertFalse(p.isActive());
        assertEquals(-1, p.selected());
        assertTrue(p.names().isEmpty());
    }

    @Test
    @DisplayName("setSession records the titles and the highlighted row")
    void sessionIsRecorded() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        p.setSession(List.of("Terminal", "Firefox", "Editor"), 1);
        assertTrue(p.isActive());
        assertEquals(List.of("Terminal", "Firefox", "Editor"), p.names());
        assertEquals(1, p.selected());
    }

    @Test
    @DisplayName("setSession(null) and clear() both return the panel to idle")
    void nullAndClearAreIdle() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        p.setSession(null, 3);
        assertFalse(p.isActive(), "a null list is treated as empty");
        assertEquals(-1, p.selected());

        p.setSession(List.of("A", "B"), 0);
        assertTrue(p.isActive());
        p.clear();
        assertFalse(p.isActive());
        assertEquals(-1, p.selected());
        assertTrue(p.names().isEmpty());
    }

    @Test
    @DisplayName("the recorded titles are an immutable snapshot")
    void sessionIsASnapshot() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        List<String> source = new ArrayList<>(List.of("A", "B"));
        p.setSession(source, 0);
        source.add("C");
        assertEquals(List.of("A", "B"), p.names(), "mutating the source does not leak in");
    }

    // --------------------------------------------------------------- painting

    @Test
    @DisplayName("an idle panel paints nothing at all")
    void idlePaintsNothing() {
        assertEquals(0, ink(render(new WindowSwitcherPanel())));
    }

    @Test
    @DisplayName("a session paints a card and highlights exactly the selected row")
    void paintsCardAndOneSelectedRow() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        p.setSession(List.of("Terminal", "Firefox", "Editor"), 1);
        BufferedImage image = render(p);

        assertTrue(ink(image) > 0, "the card and its rows are painted");
        List<Integer> rows = selectedRows(image);
        assertEquals(List.of(1), rows, "only the selected row carries the fill");
    }

    @Test
    @DisplayName("the highlight follows the selected index")
    void highlightFollowsSelection() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        p.setSession(List.of("A", "B", "C"), 0);
        assertEquals(List.of(0), selectedRows(render(p)));
        p.setSession(List.of("A", "B", "C"), 2);
        assertEquals(List.of(2), selectedRows(render(p)));
    }

    @Test
    @DisplayName("more windows than MAX_ROWS paint only the first MAX_ROWS")
    void capsTheRowCount() {
        WindowSwitcherPanel p = new WindowSwitcherPanel();
        List<String> many = new ArrayList<>();
        for (int i = 0; i < WindowSwitcherPanel.MAX_ROWS + 4; i++) {
            many.add("Window " + i);
        }
        p.setSession(many, 0);
        BufferedImage image = render(p);
        // The card is exactly MAX_ROWS tall, so there is no room for the extra
        // windows: the surplus titles are simply not listed.
        assertEquals(WindowSwitcherPanel.cardHeight(WindowSwitcherPanel.MAX_ROWS),
                image.getHeight());
        assertTrue(ink(image) > 0);
        assertEquals(List.of(0), selectedRows(image),
                "only the selected row is highlighted, within the capped card");
    }

    // -------------------------------------------------------------- ellipsize

    @Test
    @DisplayName("ellipsize leaves a null safe and a short title untouched")
    void ellipsizeShortAndNull() {
        FontMetrics fm = metrics();
        assertEquals("", WindowSwitcherPanel.ellipsize(null, fm, 100));
        assertEquals("Hi", WindowSwitcherPanel.ellipsize("Hi", fm, 100));
    }

    @Test
    @DisplayName("ellipsize truncates a long title and keeps it within the width")
    void ellipsizeLong() {
        FontMetrics fm = metrics();
        String longTitle = "The quick brown fox jumps over the lazy dog again and again";
        String cut = WindowSwitcherPanel.ellipsize(longTitle, fm, 80);
        assertTrue(cut.endsWith("\u2026"), "the truncated title ends with an ellipsis");
        assertTrue(cut.length() < longTitle.length());
        assertTrue(fm.stringWidth(cut) <= 80, "the result fits the requested width");
    }
}
