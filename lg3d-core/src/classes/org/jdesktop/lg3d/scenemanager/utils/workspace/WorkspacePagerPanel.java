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
package org.jdesktop.lg3d.scenemanager.utils.workspace;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;

/**
 * The workspace pager's paint surface: a compact glassy strip of one cell per
 * workspace, flanked by previous/next arrows, rendered offscreen into a
 * {@code SwingNode} texture by {@link WorkspacePager3D}. It is the native 3D
 * counterpart of the 2D/Swing desktop's {@code WorkspacePager}.
 *
 * <p>This class is pure Swing — no Java 3D — so its geometry, hit-testing and
 * painting are all unit-testable headless. The layout is fixed at construction
 * because the workspace count never changes at runtime, which keeps the hosted
 * texture a constant size (resizing a live {@code SwingNode} texture is what the
 * toast pool deliberately avoids).</p>
 *
 * <p>Interaction is driven from {@code mouseClicked} rather than Swing buttons: a
 * {@code SwingNode}-hosted panel reliably receives {@code MOUSE_CLICKED} with real
 * coordinates, but not always the {@code PRESSED}/{@code RELEASED} pair a
 * {@code JButton} needs to fire. Cells and arrows are therefore painted regions
 * resolved by the pure {@link #hit} helper, and the panel acts on the registry
 * directly — clicking a cell switches to it, an arrow steps one workspace.</p>
 */
public class WorkspacePagerPanel extends JPanel implements WorkspaceRegistry.Listener {

    /** Outer padding around the strip, in pixels. */
    static final int PAD = 5;
    /** Gap between neighbouring elements, in pixels. */
    static final int GAP = 3;
    /** Width of each previous/next arrow cell, in pixels. */
    static final int ARROW_W = 16;
    /** Width of one workspace cell, in pixels. */
    static final int CELL_W = 30;
    /** Height of the cells and arrows, in pixels. */
    static final int CELL_H = 24;
    /** Radius of the rounded cell/strip corners. */
    static final int ARC = 10;

    /** {@link #hit} result: the click landed on nothing. */
    public static final int HIT_NONE = -1;
    /** {@link #hit} result: the click landed on the previous-workspace arrow. */
    public static final int HIT_PREV = -2;
    /** {@link #hit} result: the click landed on the next-workspace arrow. */
    public static final int HIT_NEXT = -3;

    static final Color CARD = new Color(26, 32, 44);
    static final Color BORDER = new Color(96, 148, 214, 190);
    static final Color CELL_IDLE = new Color(255, 255, 255, 18);
    static final Color CELL_CURRENT = new Color(96, 148, 214);
    static final Color TEXT = new Color(226, 232, 240);
    static final Color TEXT_DIM = new Color(150, 165, 185);
    static final Color DOT = new Color(140, 200, 150);
    /** Most window dots drawn in a cell before the count is shown as a number. */
    static final int MAX_DOTS = 4;

    private static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    private final WorkspaceRegistry registry;
    private final int count;

    private volatile int current;
    private volatile int[] perWorkspace;

    /**
     * Builds a pager for {@code registry}, sized once for its workspace count and
     * registered as a listener so it repaints whenever the workspaces change.
     */
    public WorkspacePagerPanel(WorkspaceRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry cannot be null");
        }
        this.registry = registry;
        this.count = Math.max(WorkspaceModel.MIN_COUNT, registry.count());
        this.current = registry.current();
        this.perWorkspace = readCounts();

        Dimension size = new Dimension(width(count), height());
        setPreferredSize(size);
        setMinimumSize(size);
        setSize(size);
        setOpaque(true);
        setBackground(CARD);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                handleClick(e.getX(), e.getY());
            }
        });

        registry.addListener(this);
    }

    /** Stops listening to the registry; the panel then paints its last state. */
    public void detach() {
        registry.removeListener(this);
    }

    /** How many workspaces this pager shows. */
    public int workspaceCount() {
        return count;
    }

    /** Re-reads the registry and repaints. Safe to call from any thread. */
    @Override
    public void workspaceChanged() {
        sync();
    }

    /** Pulls the current workspace and the per-workspace counts, then repaints. */
    void sync() {
        this.current = registry.current();
        this.perWorkspace = readCounts();
        repaint();
    }

    private int[] readCounts() {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = registry.countOn(i);
        }
        return out;
    }

    /**
     * Resolves a click at {@code (x, y)} to a workspace switch or a step, and
     * applies it. Package-private so tests can drive interaction without AWT
     * event dispatch.
     */
    void handleClick(int x, int y) {
        int target = hit(x, y, count);
        if (target == HIT_PREV) {
            registry.previous();
        } else if (target == HIT_NEXT) {
            registry.next();
        } else if (target >= 0) {
            registry.switchTo(target);
        }
        // HIT_NONE: the click was in the strip's padding; nothing to do.
    }

    /** The strip's total pixel width for {@code count} workspaces. */
    public static int width(int count) {
        int cells = Math.max(WorkspaceModel.MIN_COUNT, count);
        return PAD * 2 + ARROW_W * 2 + GAP * 2 + cells * CELL_W + (cells - 1) * GAP;
    }

    /** The strip's total pixel height. */
    public static int height() {
        return PAD * 2 + CELL_H;
    }

    /** The left edge of workspace cell {@code index}. */
    static int cellX(int index) {
        return PAD + ARROW_W + GAP + Math.max(0, index) * (CELL_W + GAP);
    }

    /** The left edge of the previous-workspace arrow. */
    static int prevArrowX() {
        return PAD;
    }

    /** The left edge of the next-workspace arrow. */
    static int nextArrowX(int count) {
        return width(count) - PAD - ARROW_W;
    }

    /**
     * Pure hit test: the workspace index under {@code (x, y)}, or
     * {@link #HIT_PREV}, {@link #HIT_NEXT} or {@link #HIT_NONE}.
     */
    public static int hit(int x, int y, int count) {
        int cells = Math.max(WorkspaceModel.MIN_COUNT, count);
        if (inside(x, y, prevArrowX(), PAD, ARROW_W, CELL_H)) {
            return HIT_PREV;
        }
        if (inside(x, y, nextArrowX(cells), PAD, ARROW_W, CELL_H)) {
            return HIT_NEXT;
        }
        for (int i = 0; i < cells; i++) {
            if (inside(x, y, cellX(i), PAD, CELL_W, CELL_H)) {
                return i;
            }
        }
        return HIT_NONE;
    }

    /** Inclusive-on-the-left, exclusive-on-the-right box test. */
    static boolean inside(int x, int y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g2.setColor(CARD);
            g2.fillRoundRect(0, 0, w, h, ARC + 4, ARC + 4);
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, ARC + 4, ARC + 4);

            paintArrow(g2, prevArrowX(), false);
            paintArrow(g2, nextArrowX(count), true);

            int cur = current;
            int[] counts = perWorkspace;
            g2.setFont(LABEL_FONT);
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < count; i++) {
                paintCell(g2, i, i == cur, counts != null && i < counts.length ? counts[i] : 0, fm);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintArrow(Graphics2D g2, int x, boolean next) {
        int[] xs = next
                ? new int[] { x + 4, x + 4, x + ARROW_W - 5 }
                : new int[] { x + ARROW_W - 5, x + ARROW_W - 5, x + 4 };
        int[] ys = new int[] { PAD + 5, PAD + CELL_H - 5, PAD + CELL_H / 2 };
        g2.setColor(TEXT_DIM);
        g2.fillPolygon(xs, ys, 3);
    }

    private void paintCell(Graphics2D g2, int index, boolean isCurrent, int windows, FontMetrics fm) {
        int x = cellX(index);
        if (isCurrent) {
            g2.setColor(CELL_CURRENT);
        } else {
            g2.setColor(CELL_IDLE);
        }
        g2.fillRoundRect(x, PAD, CELL_W, CELL_H, ARC, ARC);
        if (!isCurrent) {
            g2.setColor(BORDER);
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(x, PAD, CELL_W - 1, CELL_H - 1, ARC, ARC);
        }

        g2.setColor(isCurrent ? Color.WHITE : TEXT);
        String label = Integer.toString(index + 1);
        int tx = x + (CELL_W - fm.stringWidth(label)) / 2;
        int ty = PAD + (CELL_H - fm.getHeight()) / 2 + fm.getAscent() - 2;
        g2.drawString(label, tx, ty);

        if (windows <= 0) {
            return;
        }
        if (windows <= MAX_DOTS) {
            int dotW = 3;
            int total = windows * dotW + (windows - 1) * 2;
            int dx = x + (CELL_W - total) / 2;
            int dy = PAD + CELL_H - 6;
            g2.setColor(isCurrent ? Color.WHITE : DOT);
            for (int d = 0; d < windows; d++) {
                g2.fillOval(dx + d * (dotW + 2), dy, dotW, dotW);
            }
        } else {
            g2.setColor(isCurrent ? Color.WHITE : DOT);
            String badge = Integer.toString(windows);
            g2.drawString(badge, x + CELL_W - fm.stringWidth(badge) - 3, PAD + CELL_H - 3);
        }
    }
}
