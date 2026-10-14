/*
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;

/**
 * The browser's vignette: what the world the picker holds looks like. It has two
 * answers and shows the better one it is given. A {@link BufferedImage} &mdash;
 * the picture the server's {@code /api/world/preview} carries, the same art the
 * original Wonderland browser put next to each world &mdash; is painted to fill
 * the panel. With no picture, the {@link WorldPlan} is painted as a top-down
 * floor plan instead, every room a labelled box at its real relative spot.
 *
 * <p>Like every other face in this panel it is LAF-neutral: boxes are outlined
 * in the component foreground and labeled in it too, with a faint translucent
 * <em>fill derived from the foreground</em> so overlapping rooms stay readable
 * &mdash; no palette color is invented here, so the panel paints the same in
 * the 3D desktop's SwingNode offscreen buffer as in the 2D one.</p>
 *
 * <p>The plan arrives fully normalized; this class only maps it to pixels. Two
 * of those mappings are the difference between a readable plan and the mush the
 * first version painted, so they live here explicitly: the drawing is fit to
 * {@link WorldPlan#contentBounds()} rather than the normalized unit square (a
 * wide world uses a wide viewport instead of painting a stamp in the middle),
 * and a label is wrapped into its box or left out &mdash; never drawn over its
 * neighbours.</p>
 */
final class WorldPreviewPanel extends JComponent {

    /** Pixels kept clear around the drawing, so nothing touches the border. */
    private static final int MARGIN_PX = 8;

    /** The smallest box worth a line of text; below it a room is just a mark. */
    private static final int LABEL_MIN_PX = 10;

    private WorldPlan plan = WorldPlan.EMPTY;
    private BufferedImage preview;

    WorldPreviewPanel() {
        setPreferredSize(new Dimension(300, 200));
        setToolTipText("What the selected world looks like: its own picture when "
                + "the server has one, otherwise its top-down plan, drawn from "
                + "the cells' own placement data.");
    }

    /** Show another world's plan; repaints immediately. Call on the EDT. */
    void setPlan(WorldPlan plan) {
        this.plan = (plan == null) ? WorldPlan.EMPTY : plan;
        repaint();
    }

    /**
     * Show a world's picture, or {@code null} for "this world has none" &mdash;
     * which puts the plan back. The bytes are decoded by the caller off the EDT;
     * this class only paints what it is handed.
     */
    void setPreviewImage(BufferedImage preview) {
        this.preview = preview;
        repaint();
    }

    /** What is currently shown. For the tests. */
    WorldPlan getPlan() {
        return plan;
    }

    /** The picture currently shown, or null when the plan is. For the tests. */
    BufferedImage getPreviewImage() {
        return preview;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 2 * MARGIN_PX || h <= 2 * MARGIN_PX) {
            return; // no room to fit anything yet
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            if (preview != null) {
                paintPicture(g2, w, h);
            } else if (plan.isEmpty()) {
                paintPlaceholder(g2, w, h);
            } else {
                paintPlan(g2, w, h);
            }
        } finally {
            g2.dispose();
        }
    }

    /** The world's own picture, aspect-kept and centered in the panel. */
    private void paintPicture(Graphics2D g2, int w, int h) {
        int innerW = w - 2 * MARGIN_PX;
        int innerH = h - 2 * MARGIN_PX;
        double scale = Math.min((double) innerW / preview.getWidth(),
                (double) innerH / preview.getHeight());
        int drawW = Math.max(1, (int) Math.round(preview.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(preview.getHeight() * scale));
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(preview, (w - drawW) / 2, (h - drawH) / 2, drawW, drawH, null);
        // A hairline frame so a picture with white margins still has an edge.
        g2.setColor(ink());
        g2.drawRect((w - drawW) / 2, (h - drawH) / 2, drawW, drawH);
    }

    /** The floor plan: the normalized content fitted to this panel's rectangle. */
    private void paintPlan(Graphics2D g2, int w, int h) {
        int innerW = w - 2 * MARGIN_PX;
        int innerH = h - 2 * MARGIN_PX;
        double[] bounds = plan.contentBounds(); // {minX, minY, width, height}
        if (bounds[2] <= 0 || bounds[3] <= 0) {
            paintPlaceholder(g2, w, h);
            return;
        }
        double scale = Math.min(innerW / bounds[2], innerH / bounds[3]);
        double offX = MARGIN_PX + (innerW - bounds[2] * scale) / 2 - bounds[0] * scale;
        double offY = MARGIN_PX + (innerH - bounds[3] * scale) / 2 - bounds[1] * scale;

        plan.ground().ifPresent(ground -> {
            // The floor is context, not a room: outlined, never filled, never
            // labeled, and clipped to the panel because it is bigger than what
            // fits -- that is precisely why it may not set the scale.
            g2.setColor(new Color(ink().getRed(),
                    ink().getGreen(), ink().getBlue(), 90));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 1f, new float[] {3f, 3f}, 0f));
            g2.draw(rect(ground, scale, offX, offY));
        });

        Color fill = new Color(ink().getRed(), ink().getGreen(), ink().getBlue(), 28);
        FontMetrics fm = g2.getFontMetrics();
        for (WorldPlan.Item item : plan.items()) {
            Rectangle box = rect(item, scale, offX, offY);
            g2.setColor(fill);
            g2.fillRect(box.x, box.y, box.width, box.height);
            g2.setColor(ink());
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(box.x, box.y, box.width, box.height);
            paintLabel(g2, fm, item.label(), box.x, box.y, box.width, box.height);
        }
    }

    private static Rectangle rect(WorldPlan.Item item, double scale,
            double offX, double offY) {
        int x = (int) Math.round(offX + item.x() * scale);
        int y = (int) Math.round(offY + item.y() * scale);
        int boxW = Math.max(2, (int) Math.round(item.w() * scale));
        int boxH = Math.max(2, (int) Math.round(item.h() * scale));
        return new Rectangle(x, y, boxW, boxH);
    }

    /**
     * Write a room's name inside its box: one line when it fits, two when the
     * box is tall enough for two and the words can be split, a truncated one
     * when it is wide enough for something, and nothing at all when the box is
     * a mark. Overlapping the neighbours with text is the one thing a floor
     * plan may not do.
     */
    private void paintLabel(Graphics2D g2, FontMetrics fm, String label,
            int x, int y, int boxW, int boxH) {
        if (label == null || label.isEmpty()
                || boxW < LABEL_MIN_PX || boxH < fm.getHeight()) {
            return;
        }
        int usable = boxW - 4;
        if (fm.stringWidth(label) <= usable) {
            drawCentered(g2, fm, label, x, y, boxW, boxH, 1, 0);
            return;
        }
        List<String> lines = wrap(fm, label, usable);
        if (lines.size() > 1 && boxH >= 2 * fm.getHeight()) {
            drawCentered(g2, fm, lines.get(0), x, y, boxW, boxH, 2, 0);
            String second = fit(fm, lines.get(1), usable);
            if (second != null) {
                drawCentered(g2, fm, second, x, y, boxW, boxH, 2, 1);
            }
            return;
        }
        String one = fit(fm, (lines.isEmpty() ? label : lines.get(0)), usable);
        if (one != null) {
            drawCentered(g2, fm, one, x, y, boxW, boxH, 1, 0);
        }
    }

    /**
     * Greedy word wrap into at most two lines: the longest first line that still
     * fits, and every remaining word as the second (which {@link #fit} shortens
     * if it too is too wide). A single word wider than the box is its own first
     * line rather than an empty one.
     */
    private static List<String> wrap(FontMetrics fm, String label, int usable) {
        String[] words = label.split("\\s+");
        String first = words[0];
        int split = words.length;
        for (int i = 1; i < words.length; i++) {
            String candidate = first + " " + words[i];
            if (fm.stringWidth(candidate) > usable) {
                split = i;
                break;
            }
            first = candidate;
        }
        if (split >= words.length) {
            return List.of(first);
        }
        return List.of(first, String.join(" ",
                Arrays.copyOfRange(words, split, words.length)));
    }

    /** {@code text} centered in the box, laid out as line {@code line} of {@code lines}. */
    private void drawCentered(Graphics2D g2, FontMetrics fm, String text,
            int x, int y, int boxW, int boxH, int lines, int line) {
        int block = lines * fm.getHeight();
        int top = y + (boxH - block) / 2;
        int baseline = top + line * fm.getHeight()
                + (fm.getAscent() + fm.getDescent()) / 2;
        g2.setColor(ink());
        g2.drawString(text, x + (boxW - fm.stringWidth(text)) / 2, baseline);
    }

    /** {@code text} shortened with an ellipsis until it fits {@code usable} pixels. */
    private static String fit(FontMetrics fm, String text, int usable) {
        if (fm.stringWidth(text) <= usable) {
            return text;
        }
        for (int end = text.length() - 1; end > 1; end--) {
            String candidate = text.substring(0, end) + "\u2026";
            if (fm.stringWidth(candidate) <= usable) {
                return candidate;
            }
        }
        return null; // even "a..." does not fit: this box gets no text
    }

    /** The honest empty state: a world with no placement and no picture. */
    private void paintPlaceholder(Graphics2D g2, int w, int h) {
        String text = "no preview for this world";
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(ink());
        g2.drawString(text, (w - fm.stringWidth(text)) / 2,
                (h + fm.getAscent() - fm.getDescent()) / 2);
    }

    /**
     * The colour to draw with. Not {@code getForeground()}: this panel is painted
     * into an offscreen image by the desktop's own screencapture and by the
     * {@code SwingNode} bridge, and a bare {@code JComponent} that never got a
     * look-and-feel UI delegate has a <em>null</em> foreground -- which threw the
     * first time anything tried to read it and left the vignette unpainted. A
     * panel decides its own ink; the toolkit's default is a courtesy at best.
     */
    private Color ink() {
        Color declared = getForeground();
        return (declared == null) ? Color.DARK_GRAY : declared;
    }
}
