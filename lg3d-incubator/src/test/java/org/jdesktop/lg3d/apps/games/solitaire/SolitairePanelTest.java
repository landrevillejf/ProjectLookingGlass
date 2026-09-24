/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.solitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link SolitairePanel}, the 2D/Swing counterpart of
 * Solitaire3D. They drive {@code handleClick} / {@code handleDoubleClick} (the
 * same entry points the mouse listener uses) and {@code hitTest}, and assert on
 * the reused {@link SolitaireModel}, so no display is needed.
 */
class SolitairePanelTest {

    @Test
    void freshDealHasTheKlondikeLayout() {
        SolitairePanel panel = new SolitairePanel();
        for (int p = 0; p < 7; p++) {
            assertEquals(p + 1, panel.model().tableauSize(p));
        }
        assertEquals(24, panel.model().stockSize());
        assertEquals(0, panel.model().getMoves());
        assertEquals("Moves: 0", panel.statusText());
        assertFalse(panel.model().isWon());
    }

    @Test
    void drawingTheStockMovesACardToTheWaste() {
        SolitairePanel panel = new SolitairePanel();
        int stock = panel.model().stockSize();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        assertEquals(stock - 1, panel.model().stockSize());
        assertTrue(panel.model().wasteTop() >= 0);
        assertEquals(1, panel.model().getMoves());
    }

    @Test
    void undoRestoresTheStock() {
        SolitairePanel panel = new SolitairePanel();
        int stock = panel.model().stockSize();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        panel.undo();
        assertEquals(stock, panel.model().stockSize());
        assertEquals(0, panel.model().getMoves());
    }

    @Test
    void undoWithNothingToUndoReportsIt() {
        SolitairePanel panel = new SolitairePanel();
        panel.undo();
        assertEquals("Nothing to undo", panel.statusText());
    }

    @Test
    void clickingTheWasteSelectsIt() {
        SolitairePanel panel = new SolitairePanel();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        panel.handleClick(SolitaireModel.ZONE_WASTE, -1, -1);
        assertEquals(SolitaireModel.ZONE_WASTE, panel.selectionZone());
    }

    @Test
    void clickingAFoundationWithTheWasteSelectedClearsTheSelection() {
        SolitairePanel panel = new SolitairePanel();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        panel.handleClick(SolitaireModel.ZONE_WASTE, -1, -1);
        panel.handleClick(SolitaireModel.ZONE_FOUNDATION, 0, -1);
        assertEquals(SolitaireModel.ZONE_NONE, panel.selectionZone());
    }

    @Test
    void clickingAFaceUpTableauTopSelectsIt() {
        SolitairePanel panel = new SolitairePanel();
        int p = 3;
        int top = panel.model().tableauSize(p) - 1;
        panel.handleClick(SolitaireModel.ZONE_TABLEAU, p, top);
        assertEquals(SolitaireModel.ZONE_TABLEAU, panel.selectionZone());
        assertEquals(p, panel.selectionPile());
        assertEquals(top, panel.selectionPos());
    }

    @Test
    void clickingAFaceDownTableauCardDoesNotSelect() {
        SolitairePanel panel = new SolitairePanel();
        int p = 5;                       // size 6, only the last card is face-up
        panel.handleClick(SolitaireModel.ZONE_TABLEAU, p, 0);
        assertEquals(SolitaireModel.ZONE_NONE, panel.selectionZone());
    }

    @Test
    void hintEitherPlaysAUsableMoveOrPromptsToDraw() {
        SolitairePanel panel = new SolitairePanel();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        SolitaireModel.Hint h = panel.model().hint();
        int before = panel.model().getMoves();
        if (h == null) {
            panel.hint();
            assertTrue(panel.statusText().contains("No moves"));
            return;
        }
        if (h.srcZone == SolitaireModel.ZONE_WASTE) {
            panel.handleClick(SolitaireModel.ZONE_WASTE, -1, -1);
        } else {
            panel.handleClick(SolitaireModel.ZONE_TABLEAU, h.srcPile, h.srcPos);
        }
        if (h.destZone == SolitaireModel.ZONE_FOUNDATION) {
            panel.handleClick(SolitaireModel.ZONE_FOUNDATION, h.destIndex, -1);
        } else {
            panel.handleClick(SolitaireModel.ZONE_TABLEAU, h.destIndex,
                    panel.model().tableauSize(h.destIndex) - 1);
        }
        assertTrue(panel.model().getMoves() > before,
                "the hinted move should have been applied");
    }

    @Test
    void newGameResetsTheMoveCount() {
        SolitairePanel panel = new SolitairePanel();
        panel.handleClick(SolitaireModel.ZONE_STOCK, -1, -1);
        panel.newGame();
        assertEquals(0, panel.model().getMoves());
        assertEquals(SolitaireModel.ZONE_NONE, panel.selectionZone());
    }

    @Test
    void autoFinishDeclinesWhenNotReady() {
        SolitairePanel panel = new SolitairePanel();
        panel.autoFinish();
        assertEquals("Not ready to auto-finish yet", panel.statusText());
    }

    @Test
    void doubleClickOnATableauTopIsHarmless() {
        SolitairePanel panel = new SolitairePanel();
        panel.handleDoubleClick(SolitaireModel.ZONE_TABLEAU, 0);
        assertEquals(SolitaireModel.ZONE_NONE, panel.selectionZone());
        assertTrue(panel.model().getMoves() >= 0);
    }

    @Test
    void hitTestMapsPointsToZones() {
        SolitairePanel panel = new SolitairePanel();
        // Geometry: MARGIN=12, CARD_W=62, CARD_H=86, GAP=12 => column pitch 74.
        assertEquals(SolitaireModel.ZONE_STOCK, panel.hitTest(43, 55)[0]);
        assertEquals(SolitaireModel.ZONE_WASTE, panel.hitTest(117, 55)[0]);
        int[] foundation = panel.hitTest(265, 55);
        assertEquals(SolitaireModel.ZONE_FOUNDATION, foundation[0]);
        assertEquals(0, foundation[1]);
        // Tableau starts at y = 12 + 86 + 26 = 124; pile 0 sits at x = 12.
        int[] tableau = panel.hitTest(43, 167);
        assertEquals(SolitaireModel.ZONE_TABLEAU, tableau[0]);
        assertEquals(0, tableau[1]);
        assertEquals(SolitaireModel.ZONE_NONE, panel.hitTest(4000, 4000)[0]);
    }
}
