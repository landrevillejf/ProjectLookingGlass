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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import javax.swing.Icon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AppIcons}' deterministic resolution: the initials extraction,
 * the stable per-name palette colour, and that one application name always
 * yields the same icon while two different names yield different icons. The
 * bundled IconManager library is on the test classpath, so real icons are
 * built headless (no window is ever shown).
 */
class AppIconsTest {

    @Test
    @DisplayName("initials take the first letter of the first two words")
    void initialsFromWords() {
        assertEquals("M3", AppIcons.initials("Mail 3D"));
        assertEquals("CH", AppIcons.initials("Chess"));
        assertEquals("SO", AppIcons.initials("Solitaire"));
        assertEquals("CC", AppIcons.initials("Control Center"));
    }

    @Test
    @DisplayName("a single word contributes two letters, and junk yields '?'")
    void initialsEdges() {
        assertEquals("CH", AppIcons.initials("chess"));
        assertEquals("?", AppIcons.initials("!!!"));
    }

    @Test
    @DisplayName("the palette colour is stable for a name")
    void colorIsStable() {
        assertEquals(AppIcons.colorFor("Chess"), AppIcons.colorFor("Chess"));
        assertEquals(AppIcons.colorFor("Mail 3D"), AppIcons.colorFor("Mail 3D"));
    }

    @Test
    @DisplayName("a semantic family resolves to a real icon")
    void semanticFamilyResolves() {
        Icon mail = AppIcons.iconFor("Mail 3D", null, 16);
        assertNotNull(mail, "a mail app gets an IconManager glyph");
        assertEquals(16, mail.getIconWidth());
        assertEquals(16, mail.getIconHeight());
    }

    @Test
    @DisplayName("an unmatched name still resolves to a generated tile")
    void generatedTileResolves() {
        Icon tile = AppIcons.iconFor("Solitaire 3D", null, 16);
        assertNotNull(tile, "an unmatched app gets an initials tile");
        assertEquals(16, tile.getIconWidth());
    }

    @Test
    @DisplayName("the same name caches one icon; different names differ")
    void cacheAndDistinctness() {
        Icon first = AppIcons.iconFor("Chess", null, 16);
        Icon again = AppIcons.iconFor("Chess", null, 16);
        assertSame(first, again, "menu, frame and taskbar must share one icon");
        assertNotSame(first, AppIcons.iconFor("Solitaire", null, 16),
                "two applications must not share an icon");
    }

    @Test
    @DisplayName("a blank name degrades to a placeholder tile, never null")
    void blankNameStillResolves() {
        assertNotNull(AppIcons.iconFor("   ", null, 16));
        assertNotNull(AppIcons.iconFor(null, null, 16));
    }
}
