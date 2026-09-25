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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link WorkspacePagerPanel}: the fixed strip layout, the
 * pure {@link WorkspacePagerPanel#hit} resolution that stands in for Swing button
 * hit-testing inside a {@code SwingNode}, the click → registry hand-off, and the
 * painting (rendered onto a {@link BufferedImage}, which is how the panel reaches
 * the screen as a texture). Nothing here touches Java 3D: the scene-graph host
 * {@link WorkspacePager3D} and the event glue in {@link WorkspacePlugin} are
 * probe-verified separately.
 */
class WorkspacePagerPanelTest {

    private static final int PAD = WorkspacePagerPanel.PAD;
    private static final int GAP = WorkspacePagerPanel.GAP;
    private static final int ARROW_W = WorkspacePagerPanel.ARROW_W;
    private static final int CELL_W = WorkspacePagerPanel.CELL_W;
    private static final int CELL_H = WorkspacePagerPanel.CELL_H;
    private static final int MID_Y = PAD + CELL_H / 2;

    private static WorkspacePagerPanel panel(int count) {
        return new WorkspacePagerPanel(new WorkspaceRegistry(count));
    }

    /** Renders the panel offscreen, exactly as the SwingNode texture does. */
    private static BufferedImage render(WorkspacePagerPanel p) {
        BufferedImage image = new BufferedImage(p.getWidth(), p.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            p.paintComponent(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** How many pixels are painted in something other than the card colour. */
    private static int ink(BufferedImage image) {
        int card = WorkspacePagerPanel.CARD.getRGB();
        int inked = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) != card) {
                    inked++;
                }
            }
        }
        return inked;
    }

    /** True when any pixel of the strip is painted exactly {@code rgb}. */
    private static boolean containsColour(BufferedImage image, int rgb) {
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (image.getRGB(x, y) == rgb) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int x = 0; x < a.getWidth(); x++) {
            for (int y = 0; y < a.getHeight(); y++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ layout

    @Test
    @DisplayName("the strip is sized once, for its workspace count")
    void sizesItselfForTheWorkspaceCount() {
        WorkspacePagerPanel p = panel(4);
        assertEquals(4, p.workspaceCount());
        assertEquals(WorkspacePagerPanel.width(4), p.getWidth());
        assertEquals(WorkspacePagerPanel.height(), p.getHeight());
        assertEquals(p.getWidth(), p.getPreferredSize().width);
        assertEquals(p.getHeight(), p.getPreferredSize().height);
        assertEquals(p.getWidth(), p.getMinimumSize().width);
        assertEquals(p.getHeight(), p.getMinimumSize().height);
    }

    @Test
    @DisplayName("the width grows with the cell count and never shrinks below one")
    void widthGrowsWithTheCount() {
        assertEquals(PAD * 2 + ARROW_W * 2 + GAP * 2 + CELL_W, WorkspacePagerPanel.width(1));
        for (int count = 1; count < WorkspaceModel.MAX_COUNT; count++) {
            assertEquals(CELL_W + GAP,
                    WorkspacePagerPanel.width(count + 1) - WorkspacePagerPanel.width(count),
                    "one more cell adds one cell plus its gap");
        }
        assertEquals(WorkspacePagerPanel.width(1), WorkspacePagerPanel.width(0),
                "clamped to the model's minimum");
        assertEquals(WorkspacePagerPanel.width(1), WorkspacePagerPanel.width(-3));
        assertEquals(PAD * 2 + CELL_H, WorkspacePagerPanel.height());
    }

    @Test
    @DisplayName("the arrows and cells tile the strip edge to edge")
    void layoutTilesWithoutOverlapOrGap() {
        int count = 4;
        assertEquals(PAD, WorkspacePagerPanel.prevArrowX());
        assertEquals(WorkspacePagerPanel.prevArrowX() + ARROW_W + GAP,
                WorkspacePagerPanel.cellX(0), "the first cell starts after the prev arrow");
        for (int i = 0; i < count - 1; i++) {
            assertEquals(CELL_W + GAP,
                    WorkspacePagerPanel.cellX(i + 1) - WorkspacePagerPanel.cellX(i));
        }
        assertEquals(WorkspacePagerPanel.cellX(count - 1) + CELL_W + GAP,
                WorkspacePagerPanel.nextArrowX(count), "the next arrow starts after the last cell");
        assertEquals(WorkspacePagerPanel.width(count) - PAD,
                WorkspacePagerPanel.nextArrowX(count) + ARROW_W, "and ends before the right pad");
        assertEquals(WorkspacePagerPanel.cellX(0), WorkspacePagerPanel.cellX(-1),
                "a negative index is clamped, not offset backwards");
    }

    // -------------------------------------------------------------- hit tests

    @Test
    @DisplayName("hit resolves every cell and both arrows")
    void hitResolvesEveryTarget() {
        int count = 4;
        assertEquals(WorkspacePagerPanel.HIT_PREV,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.prevArrowX() + 3, MID_Y, count));
        assertEquals(WorkspacePagerPanel.HIT_NEXT,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.nextArrowX(count) + 3, MID_Y, count));
        for (int i = 0; i < count; i++) {
            assertEquals(i, WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(i) + 2, MID_Y, count),
                    "cell " + i);
            // The left edge is inclusive, the right edge exclusive.
            assertEquals(i, WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(i), MID_Y, count));
            assertEquals(i, WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(i) + CELL_W - 1,
                    MID_Y, count));
        }
    }

    @Test
    @DisplayName("padding, the gaps between cells and the outside hit nothing")
    void hitIgnoresTheDeadZones() {
        int count = 4;
        assertEquals(WorkspacePagerPanel.HIT_NONE, WorkspacePagerPanel.hit(0, MID_Y, count),
                "the left padding");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(0) + CELL_W + 1, MID_Y, count),
                "the gap between two cells");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(1) + 2, PAD - 1, count),
                "above the strip");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(1) + 2, PAD + CELL_H, count),
                "below the strip");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.width(count) + 40, MID_Y, count),
                "off the strip entirely");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(-10, -10, count));
    }

    @Test
    @DisplayName("hit never offers a workspace that does not exist")
    void hitClampsToTheWorkspaceCount() {
        assertEquals(0, WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(0) + 2, MID_Y, 0),
                "a degenerate count still resolves one cell");
        assertEquals(0, WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(0) + 2, MID_Y, -3));
        assertEquals(WorkspacePagerPanel.HIT_NEXT,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(2) + 2, MID_Y, 2),
                "where a third cell would sit is the next arrow, never a phantom workspace");
        assertEquals(WorkspacePagerPanel.HIT_NONE,
                WorkspacePagerPanel.hit(WorkspacePagerPanel.cellX(9) + 2, MID_Y, 1));
    }

    @Test
    @DisplayName("inside is left/top-inclusive and right/bottom-exclusive")
    void insideIsHalfOpen() {
        assertTrue(WorkspacePagerPanel.inside(5, 5, 5, 5, 10, 10));
        assertTrue(WorkspacePagerPanel.inside(14, 14, 5, 5, 10, 10));
        assertFalse(WorkspacePagerPanel.inside(15, 5, 5, 5, 10, 10), "right edge is exclusive");
        assertFalse(WorkspacePagerPanel.inside(5, 15, 5, 5, 10, 10), "bottom edge is exclusive");
        assertFalse(WorkspacePagerPanel.inside(4, 5, 5, 5, 10, 10));
        assertFalse(WorkspacePagerPanel.inside(5, 4, 5, 5, 10, 10));
    }

    // ----------------------------------------------------------- interaction

    @Test
    @DisplayName("a null registry is rejected rather than painting a dead strip")
    void nullRegistryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspacePagerPanel(null));
    }

    @Test
    @DisplayName("clicking a cell switches to that workspace")
    void clickingACellSwitches() {
        WorkspaceRegistry registry = new WorkspaceRegistry(4);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);

        p.handleClick(WorkspacePagerPanel.cellX(2) + 2, MID_Y);
        assertEquals(2, registry.current());
        p.handleClick(WorkspacePagerPanel.cellX(0) + 2, MID_Y);
        assertEquals(0, registry.current());
    }

    @Test
    @DisplayName("clicking the arrows steps, wrapping at both ends")
    void clickingTheArrowsSteps() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);

        p.handleClick(WorkspacePagerPanel.nextArrowX(3) + 2, MID_Y);
        assertEquals(1, registry.current());
        p.handleClick(WorkspacePagerPanel.nextArrowX(3) + 2, MID_Y);
        p.handleClick(WorkspacePagerPanel.nextArrowX(3) + 2, MID_Y);
        assertEquals(0, registry.current(), "next wraps to the first");
        p.handleClick(WorkspacePagerPanel.prevArrowX() + 2, MID_Y);
        assertEquals(2, registry.current(), "prev wraps to the last");
    }

    @Test
    @DisplayName("a click in the padding changes nothing")
    void clickingThePaddingIsInert() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);

        p.handleClick(0, MID_Y);
        p.handleClick(WorkspacePagerPanel.cellX(0) + CELL_W + 1, MID_Y);
        p.handleClick(-100, -100);
        assertEquals(0, registry.current());
    }

    // --------------------------------------------------------------- painting

    @Test
    @DisplayName("the strip is outlined and highlights only the current cell")
    void paintsTheStrip() {
        WorkspacePagerPanel p = panel(4);
        BufferedImage image = render(p);
        assertEquals(WorkspacePagerPanel.width(4), image.getWidth());
        assertTrue(ink(image) > 0, "an empty strip still draws its outline and cells");

        int card = WorkspacePagerPanel.CARD.getRGB();
        assertEquals(card, image.getRGB(0, 0), "the rounded corner is cut away");
        assertTrue(image.getRGB(image.getWidth() / 2, 0) != card, "the top edge is outlined");
        assertTrue(containsColour(image, WorkspacePagerPanel.CELL_CURRENT.getRGB()),
                "the current cell is filled");
        assertFalse(containsColour(image, WorkspacePagerPanel.CELL_IDLE.getRGB()),
                "an idle cell is a translucent wash, never its raw colour");

        // Only the current workspace's cell carries the highlight fill.
        int highlightedCells = 0;
        int current = WorkspacePagerPanel.CELL_CURRENT.getRGB();
        for (int i = 0; i < 4; i++) {
            int x0 = WorkspacePagerPanel.cellX(i);
            int centre = image.getRGB(x0 + CELL_W / 2, MID_Y);
            boolean isHighlighted = false;
            for (int x = x0; x < x0 + CELL_W && !isHighlighted; x++) {
                for (int y = PAD; y < PAD + CELL_H; y++) {
                    if (image.getRGB(x, y) == current) {
                        isHighlighted = true;
                        break;
                    }
                }
            }
            if (isHighlighted) {
                highlightedCells++;
                assertEquals(0, i, "workspace 0 is current, so only its cell is filled");
            }
            assertTrue(centre != card, "cell " + i + " is drawn");
        }
        assertEquals(1, highlightedCells);
    }

    @Test
    @DisplayName("the highlighted cell follows the current workspace")
    void repaintsOnEveryWorkspaceChange() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);
        BufferedImage before = render(p);

        registry.next();
        BufferedImage after = render(p);
        assertFalse(sameImage(before, after), "the panel is a registry listener and re-syncs");

        registry.switchTo(0);
        assertTrue(sameImage(before, render(p)), "switching back repaints the same strip");
    }

    @Test
    @DisplayName("the open windows are drawn into their cell")
    void paintsTheWindowCount() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);
        BufferedImage noWindows = render(p);

        registry.register("a", v -> { });
        BufferedImage oneWindow = render(p);
        assertFalse(sameImage(noWindows, oneWindow), "the current cell shows what is open");

        registry.register("b", v -> { });
        assertFalse(sameImage(oneWindow, render(p)), "a second window adds a second dot");

        // Past MAX_DOTS the dots give way to a numeric badge.
        for (int i = 0; i < WorkspacePagerPanel.MAX_DOTS + 2; i++) {
            registry.register("w" + i, v -> { });
        }
        assertFalse(sameImage(oneWindow, render(p)));

        // A window parked on an idle workspace shows its green dot there, which an
        // empty idle cell never draws. (The current cell's dots are white, like its
        // label, so the dot colour is only unambiguous on an idle cell.)
        WorkspaceRegistry idle = new WorkspaceRegistry(3);
        WorkspacePagerPanel idlePanel = new WorkspacePagerPanel(idle);
        int dot = WorkspacePagerPanel.DOT.getRGB();
        assertFalse(containsColour(render(idlePanel), dot), "nothing open, no dots");
        idle.register("x", v -> { });
        idle.moveTo("x", 2);
        assertTrue(containsColour(render(idlePanel), dot),
                "the pager shows which workspaces hold windows");
    }

    @Test
    @DisplayName("a detached panel stops tracking the registry")
    void detachStopsListening() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);
        registry.next();
        BufferedImage synced = render(p);

        p.detach();
        registry.next();
        assertTrue(sameImage(synced, render(p)), "the last state is what stays painted");

        // Detaching twice is harmless, and a click still steps the registry.
        p.detach();
        p.handleClick(WorkspacePagerPanel.cellX(0) + 2, MID_Y);
        assertEquals(0, registry.current());
    }

    @Test
    @DisplayName("a single-workspace pager is still drawn and clickable")
    void singleWorkspacePagerStillWorks() {
        WorkspaceRegistry registry = new WorkspaceRegistry(1);
        WorkspacePagerPanel p = new WorkspacePagerPanel(registry);
        assertEquals(1, p.workspaceCount());
        assertTrue(ink(render(p)) > 0);

        p.handleClick(WorkspacePagerPanel.cellX(0) + 2, MID_Y);
        assertEquals(0, registry.current());
        p.handleClick(WorkspacePagerPanel.nextArrowX(1) + 2, MID_Y);
        assertEquals(0, registry.current(), "the only workspace is always current");
    }
}
