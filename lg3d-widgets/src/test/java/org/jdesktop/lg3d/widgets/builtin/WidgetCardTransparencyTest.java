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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.displayserver.desktop2d.TranslucentSurface;
import org.jdesktop.lg3d.displayserver.desktop2d.TransparencySurface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the one thing a widget card contributes to the 2D desktop's
 * transparency setting: that it <em>declares</em> itself a
 * {@link TransparencySurface#WIDGET widget} surface. The card paints no
 * translucency of its own - the 2D desktop pane composites it when it draws its
 * children - which is exactly why the same card stays solid through a
 * {@code SwingNode} on the 3D desktop, where an opaque background is what makes
 * the texture capture reliable.
 *
 * <p>Asserted across every built-in card, so a new widget cannot join the gallery
 * without opting into the user's setting.</p>
 */
class WidgetCardTransparencyTest {

    @Test
    @DisplayName("every built-in card is a widget surface for the 2D desktop")
    void allBuiltInsDeclareTheWidgetSurface() {
        List<WidgetCard> cards = BuiltinWidgetCards.all().stream()
                .map(WidgetCardSpec::create)
                .toList();
        assertFalse(cards.isEmpty(), "the catalogue must be exercised");
        for (WidgetCard card : cards) {
            assertInstanceOf(TranslucentSurface.class, card,
                    card.id() + " must opt into the desktop's transparency");
            assertEquals(TransparencySurface.WIDGET,
                    card.transparencySurface(), card.id());
        }
    }

    @Test
    @DisplayName("a card keeps its opaque background despite being fadeable")
    void cardStaysOpaqueForTheTexturePath() {
        WidgetCard card = BuiltinWidgetCards.forId("clock").create();
        assertTrue(card.isOpaque(),
                "the solid card background is what makes a SwingNode capture work");
    }
}
