/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Locale;
import javax.swing.Icon;

/**
 * A tiny, in-tree vector-glyph factory for the editor's toolbar and completion
 * surfaces. Every glyph is drawn procedurally with {@link Graphics2D} into a
 * fixed square of the requested size, so a toolbar button always has art even
 * when no icon store is on the classpath (as on the headless test classpath,
 * where {@code IconManager} is absent and loading a PNG would fail).
 *
 * <p>The glyphs are deliberately simple line-art, monochrome and theme-neutral:
 * they inherit the surrounding component's foreground colour at paint time
 * rather than baking in a colour, so they read correctly on both the light and
 * dark editor themes. Nothing here constructs a {@code BufferedImage} or a
 * peer, so the whole class is safe to touch under {@code java.awt.headless=true}
 * &mdash; drawing only happens when a live {@code Graphics} asks for it.</p>
 */
final class EditorGlyphs {

    /** Toolbar button glyph size, in pixels. */
    static final int TOOLBAR_SIZE = 16;

    /** A slightly larger size used by the completion popup, in pixels. */
    static final int LARGE_SIZE = 22;

    private EditorGlyphs() {
    }

    /** The fixed glyph set every toolbar button can fall back to. */
    enum Glyph {
        PLAY, WRENCH, LIST, BUG, BRACES, LIGHTBULB, CLOCK, HASH, PALETTE, CHECK;

        /** @return the enum constant matching {@code name}, or null when unknown. */
        static Glyph from(String name) {
            if (name == null) {
                return null;
            }
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException iae) {
                return null;
            }
        }
    }

    /**
     * @param glyph the glyph to draw, not null
     * @param size  the square edge length in pixels (clamped to at least 8)
     * @return an {@link Icon} painting {@code glyph} at {@code size}
     */
    static Icon icon(Glyph glyph, int size) {
        return new GlyphIcon(glyph, Math.max(8, size));
    }

    /** @return the {@code size}-pixel toolbar glyph for {@code glyph}. */
    static Icon toolbarIcon(Glyph glyph) {
        return icon(glyph, TOOLBAR_SIZE);
    }

    /**
     * Chooses a sensible default glyph for an extension contribution from its
     * free-form id/label, so a user-configured button gets recognisable art
     * without the extension having to supply an icon. The match is a cheap
     * keyword scan; anything unrecognised falls back to the neutral
     * {@link Glyph#WRENCH} (a generic "tool") rather than nothing.
     *
     * @param id    the contribution id (e.g. {@code "run-project"})
     * @param label the contribution label (e.g. {@code "Run Project"})
     * @return the chosen glyph, never null
     */
    static Glyph guess(String id, String label) {
        String hay = ((id == null) ? "" : id) + " " + ((label == null) ? "" : label);
        hay = hay.toLowerCase(Locale.ROOT);
        if (contains(hay, "run", "compile", "build", "launch", "start", "resume", "step")) {
            return Glyph.PLAY;
        }
        if (contains(hay, "debug", "breakpoint", "bug")) {
            return Glyph.BUG;
        }
        if (contains(hay, "structure", "outline", "list", "todo", "task", "scan", "sort",
                "fold", "unfold")) {
            return Glyph.LIST;
        }
        if (contains(hay, "complete", "completion", "snippet", "insert", "uuid", "lorem",
                "suggest")) {
            return Glyph.BRACES;
        }
        if (contains(hay, "comment", "note", "hint", "idea", "lightbulb")) {
            return Glyph.LIGHTBULB;
        }
        if (contains(hay, "timestamp", "date", "time", "clock")) {
            return Glyph.CLOCK;
        }
        if (contains(hay, "hash", "md5", "sha", "base64", "encode", "decode", "escape")) {
            return Glyph.HASH;
        }
        if (contains(hay, "theme", "colour", "color", "palette", "highlight", "wrap")) {
            return Glyph.PALETTE;
        }
        if (contains(hay, "check", "valid", "format", "lint", "diagnostic", "problem")) {
            return Glyph.CHECK;
        }
        if (contains(hay, "tool", "settings", "config", "custom", "json", "yaml", "properties")) {
            return Glyph.WRENCH;
        }
        return Glyph.WRENCH;
    }

    private static boolean contains(String hay, String... tokens) {
        for (String t : tokens) {
            if (hay.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** A single procedurally-drawn glyph scaled to a square edge. */
    private static final class GlyphIcon implements Icon {

        private final Glyph glyph;
        private final int size;

        GlyphIcon(Glyph glyph, int size) {
            this.glyph = (glyph != null) ? glyph : Glyph.WRENCH;
            this.size = size;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color fg = (c != null) ? c.getForeground() : Color.DARK_GRAY;
                if (fg == null) {
                    fg = Color.DARK_GRAY;
                }
                g2.setColor(fg);
                // Draw in a normalised 16-unit box regardless of pixel size.
                double s = size / 16.0;
                g2.translate(x, y);
                g2.scale(s, s);
                float stroke = 1.6f;
                Stroke old = g2.getStroke();
                g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                paint(g2);
                g2.setStroke(old);
            } finally {
                g2.dispose();
            }
        }

        private void paint(Graphics2D g) {
            switch (glyph) {
                case PLAY -> {
                    Path2D tri = new GeneralPath();
                    tri.moveTo(4, 3);
                    tri.lineTo(4, 13);
                    tri.lineTo(13, 8);
                    tri.closePath();
                    g.fill(tri);
                }
                case WRENCH -> {
                    // A diagonal handle with a ring head and a solid grip.
                    g.draw(new Line2D.Double(5.2, 10.8, 10.5, 5.5));
                    g.draw(new Ellipse2D.Double(9, 3, 4.5, 4.5));
                    g.fill(new Ellipse2D.Double(3.2, 10.2, 3.4, 3.4));
                }
                case LIST -> {
                    g.fill(new Ellipse2D.Double(3, 4, 1.8, 1.8));
                    g.fill(new Ellipse2D.Double(3, 7.6, 1.8, 1.8));
                    g.fill(new Ellipse2D.Double(3, 11.2, 1.8, 1.8));
                    g.draw(new Line2D.Double(6.5, 5, 13, 5));
                    g.draw(new Line2D.Double(6.5, 8.6, 13, 8.6));
                    g.draw(new Line2D.Double(6.5, 12.2, 13, 12.2));
                }
                case BUG -> {
                    g.fill(new RoundRectangle2D.Double(5, 5, 6, 7, 4, 4));
                    g.draw(new Line2D.Double(8, 5, 8, 3));
                    g.draw(new Line2D.Double(5, 7, 2.5, 6));
                    g.draw(new Line2D.Double(5, 9, 2.5, 9.5));
                    g.draw(new Line2D.Double(5, 11, 2.5, 12));
                    g.draw(new Line2D.Double(11, 7, 13.5, 6));
                    g.draw(new Line2D.Double(11, 9, 13.5, 9.5));
                    g.draw(new Line2D.Double(11, 11, 13.5, 12));
                }
                case BRACES -> {
                    g.draw(brace(6, true));
                    g.draw(brace(10, false));
                }
                case LIGHTBULB -> {
                    g.draw(new Ellipse2D.Double(5.5, 3, 5, 5));
                    g.draw(new Line2D.Double(7, 9.5, 9, 9.5));
                    g.draw(new Line2D.Double(7.2, 11.5, 8.8, 11.5));
                    g.draw(new Line2D.Double(8, 8, 8, 9.5));
                }
                case CLOCK -> {
                    g.draw(new Ellipse2D.Double(3.5, 3.5, 9, 9));
                    g.draw(new Line2D.Double(8, 8, 8, 5));
                    g.draw(new Line2D.Double(8, 8, 10.5, 9));
                }
                case HASH -> {
                    g.draw(new Line2D.Double(6, 3, 5, 13));
                    g.draw(new Line2D.Double(10.5, 3, 9.5, 13));
                    g.draw(new Line2D.Double(3.5, 6.2, 12.5, 6.2));
                    g.draw(new Line2D.Double(3, 9.8, 12, 9.8));
                }
                case PALETTE -> {
                    g.draw(new Ellipse2D.Double(3, 4, 10, 8));
                    g.fill(new Ellipse2D.Double(5.5, 6, 1.6, 1.6));
                    g.fill(new Ellipse2D.Double(8.5, 5.5, 1.6, 1.6));
                    g.fill(new Ellipse2D.Double(10.5, 7.5, 1.6, 1.6));
                }
                case CHECK -> {
                    g.draw(new Line2D.Double(3.5, 8.5, 6.5, 11.5));
                    g.draw(new Line2D.Double(6.5, 11.5, 12.5, 4.5));
                }
            }
        }

        /** A "{" (open=true) or "}" curly brace as a light double-curve. */
        private static Path2D brace(double x, boolean open) {
            Path2D p = new GeneralPath();
            double dir = open ? 1 : -1;
            p.moveTo(x + dir * 1.6, 3.5);
            p.curveTo(x, 4, x, 6.5, x, 8);
            p.curveTo(x, 9.5, x, 12, x + dir * 1.6, 12.5);
            return p;
        }
    }
}
