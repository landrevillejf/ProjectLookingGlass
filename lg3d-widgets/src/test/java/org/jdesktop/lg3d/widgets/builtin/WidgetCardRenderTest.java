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
package org.jdesktop.lg3d.widgets.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the extracted cards still draw: each built-in is painted offscreen
 * into a {@link BufferedImage} and asserted to have laid down real ink. This is
 * the render half of "the same card works on the 3D desktop and the 2D one" -
 * the paint path is pure {@code Graphics2D}, so it runs headless and needs no
 * Java 3D, no {@code SwingNode} texture and no X display.
 *
 * <p>The weather card is painted without a {@code tick()} (its tick does an HTTP
 * fetch), exercising the empty/loading state a freshly-placed card shows before
 * its first refresh.</p>
 */
class WidgetCardRenderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("every built-in card paints non-blank offscreen")
    void everyCardPaintsOffscreen() {
        WidgetConfigStore store = new WidgetConfigStore(tmp.resolve("render.properties"));
        for (WidgetCardSpec spec : BuiltinWidgetCards.all()) {
            WidgetCard card = spec.create();
            card.attach(store, spec.id() + "-render", Runnable::run);
            if (!"weather".equals(spec.id())) {
                card.tick();   // weather's tick fetches over HTTP; paint its empty state
            }

            Dimension d = card.getPreferredSize();
            card.setBounds(0, 0, d.width, d.height);

            BufferedImage img = new BufferedImage(
                    d.width, d.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            try {
                card.paint(g);
            } finally {
                g.dispose();
            }

            assertTrue(hasInk(img), spec.id() + " painted nothing offscreen");
        }
    }

    /** True when the image has a meaningful number of pixels differing from the
     *  (opaque) card-corner background - i.e. content was actually drawn. */
    private static boolean hasInk(BufferedImage img) {
        int corner = img.getRGB(0, 0);
        assertTrue((corner & 0xFF000000) != 0, "card background is not opaque");
        int painted = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if ((argb & 0xFF000000) != 0 && argb != corner) {
                    painted++;
                }
            }
        }
        return painted > 50;
    }
}
