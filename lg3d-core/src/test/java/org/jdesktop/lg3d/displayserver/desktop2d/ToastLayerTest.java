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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JDesktopPane;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ToastLayer}: the bottom-right upward-stacking geometry, the
 * ellipsize and wrap text helpers, that {@code render} draws (and is a no-op
 * when empty) headless, that a shown toast makes the overlay visible and
 * click-through only over the card, that install/uninstall add and remove it
 * from the desktop pane's popup layer, and that a null toast is ignored.
 */
class ToastLayerTest {

    private static final Font FONT = new Font(Font.DIALOG, Font.PLAIN, 13);

    private static FontMetrics metrics() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setFont(FONT);
            return g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    private static boolean allTransparent(BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("the newest toast sits in the bottom-right corner")
    void toastBoundsAnchorsBottomRight() {
        Rectangle r = ToastLayer.toastBounds(1000, 800, 0);
        assertEquals(ToastLayer.TOAST_WIDTH, r.width);
        assertEquals(ToastLayer.TOAST_HEIGHT, r.height);
        assertEquals(1000 - ToastLayer.MARGIN - ToastLayer.TOAST_WIDTH, r.x);
        assertEquals(800 - ToastLayer.MARGIN - ToastLayer.TOAST_HEIGHT, r.y);
    }

    @Test
    @DisplayName("older toasts stack upward, one card height plus a gap apart")
    void toastBoundsStacksUpward() {
        Rectangle bottom = ToastLayer.toastBounds(1000, 800, 0);
        Rectangle above = ToastLayer.toastBounds(1000, 800, 1);
        assertEquals(bottom.x, above.x);
        assertTrue(above.y < bottom.y);
        assertEquals(ToastLayer.TOAST_HEIGHT + ToastLayer.GAP, bottom.y - above.y);
    }

    @Test
    @DisplayName("a card never starts off the top-left of a small pane")
    void toastBoundsClampsToPane() {
        Rectangle r = ToastLayer.toastBounds(50, 50, 5);
        assertTrue(r.x >= 0);
        assertTrue(r.y >= 0);
    }

    @Test
    @DisplayName("ellipsize returns null as empty and short text unchanged")
    void ellipsizeHandlesNullAndShort() {
        FontMetrics fm = metrics();
        assertEquals("", ToastLayer.ellipsize(null, fm, 100));
        assertEquals("hi", ToastLayer.ellipsize("hi", fm, 1000));
    }

    @Test
    @DisplayName("ellipsize truncates over-long text with an ellipsis")
    void ellipsizeTruncates() {
        FontMetrics fm = metrics();
        String longText = "the quick brown fox jumps over the lazy dog again";
        String out = ToastLayer.ellipsize(longText, fm, 40);
        assertTrue(out.endsWith("\u2026"));
        assertTrue(out.length() < longText.length());
        assertTrue(fm.stringWidth(out) <= 40 + fm.stringWidth("\u2026"));
    }

    @Test
    @DisplayName("wrap keeps a short message on one line")
    void wrapSingleLine() {
        FontMetrics fm = metrics();
        assertEquals(List.of("short"), ToastLayer.wrap("short", fm, 1000, 2));
    }

    @Test
    @DisplayName("wrap never exceeds the line cap")
    void wrapRespectsMaxLines() {
        FontMetrics fm = metrics();
        String text = "word ".repeat(50).trim();
        List<String> lines = ToastLayer.wrap(text, fm, 60, 3);
        assertFalse(lines.isEmpty());
        assertTrue(lines.size() <= 3);
    }

    @Test
    @DisplayName("wrap breaks a narrow-width message onto several lines")
    void wrapBreaksLines() {
        FontMetrics fm = metrics();
        assertTrue(ToastLayer.wrap("alpha beta gamma delta", fm, 40, 4).size() >= 2);
    }

    @Test
    @DisplayName("wrap of blank text or a zero line cap is empty")
    void wrapBlankIsEmpty() {
        FontMetrics fm = metrics();
        assertTrue(ToastLayer.wrap("   ", fm, 100, 2).isEmpty());
        assertTrue(ToastLayer.wrap(null, fm, 100, 2).isEmpty());
        assertTrue(ToastLayer.wrap("x", fm, 100, 0).isEmpty());
    }

    @Test
    @DisplayName("render draws toast cards into the canvas")
    void renderDraws() {
        List<Notification> toasts = List.of(
                new Notification(1L, "Title one", "Body message", Notification.Kind.INFO, 0L),
                new Notification(2L, "Warning", null, Notification.Kind.WARNING, 0L));
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ToastLayer.render(g, toasts, 600, 400, FONT);
        } finally {
            g.dispose();
        }
        assertFalse(allTransparent(image));
    }

    @Test
    @DisplayName("render falls back to a default font when none is given")
    void renderNullFontFallsBack() {
        List<Notification> toasts = List.of(
                new Notification(1L, "Title", "Body", Notification.Kind.ERROR, 0L));
        BufferedImage image = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ToastLayer.render(g, toasts, 600, 400, null);
        } finally {
            g.dispose();
        }
        assertFalse(allTransparent(image));
    }

    @Test
    @DisplayName("render of an empty list leaves the canvas untouched")
    void renderEmptyIsNoop() {
        BufferedImage image = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            ToastLayer.render(g, List.of(), 120, 120, FONT);
        } finally {
            g.dispose();
        }
        assertTrue(allTransparent(image));
    }

    @Test
    @DisplayName("a shown toast makes the overlay visible and clickable only over the card")
    void showPopulatesAndContainsHitsCard() {
        ToastLayer layer = new ToastLayer(new ToastQueue(1000L, 4), () -> 0L);
        layer.setBounds(0, 0, 800, 600);
        layer.show(new Notification(1L, "t", "m", null, 0L));
        assertTrue(layer.isVisible());
        Rectangle card = ToastLayer.toastBounds(800, 600, 0);
        assertTrue(layer.contains(card.x + card.width / 2, card.y + card.height / 2));
        assertFalse(layer.contains(5, 5), "clicks away from a card fall through");
        layer.uninstall();
    }

    @Test
    @DisplayName("showing null leaves the overlay idle")
    void showNullIgnored() {
        ToastLayer layer = new ToastLayer(new ToastQueue(1000L, 4), () -> 0L);
        layer.setBounds(0, 0, 800, 600);
        layer.show(null);
        assertFalse(layer.isVisible());
        assertFalse(layer.contains(400, 500));
        layer.uninstall();
    }

    @Test
    @DisplayName("painting a shown overlay draws, an idle one does not")
    void paintDrawsOnlyWhenShown() {
        ToastLayer shown = new ToastLayer(new ToastQueue(1000L, 4), () -> 0L);
        shown.setBounds(0, 0, 800, 600);
        shown.show(new Notification(1L, "Hello", "World", Notification.Kind.ERROR, 0L));
        BufferedImage drawn = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g1 = drawn.createGraphics();
        try {
            shown.paint(g1);
        } finally {
            g1.dispose();
        }
        assertFalse(allTransparent(drawn));
        shown.uninstall();

        ToastLayer idle = new ToastLayer(new ToastQueue(1000L, 4), () -> 0L);
        idle.setBounds(0, 0, 200, 200);
        BufferedImage blank = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = blank.createGraphics();
        try {
            idle.paint(g2);
        } finally {
            g2.dispose();
        }
        assertTrue(allTransparent(blank));
        idle.uninstall();
    }

    @Test
    @DisplayName("install adds the overlay to the popup layer, uninstall removes it")
    void installAndUninstall() {
        JDesktopPane pane = new JDesktopPane();
        ToastLayer layer = new ToastLayer(new ToastQueue(1000L, 4), () -> 0L);
        layer.install(pane);
        assertTrue(pane.isAncestorOf(layer));
        layer.uninstall();
        assertFalse(pane.isAncestorOf(layer));
    }

    @Test
    @DisplayName("uninstall before install is safe")
    void uninstallWithoutInstall() {
        new ToastLayer(new ToastQueue(1000L, 4), () -> 0L).uninstall();
    }
}
