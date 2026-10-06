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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.junit.jupiter.api.AfterEach;
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

    @AfterEach
    void restore() {
        // Never leave an icon pack selected or a cached icon behind for the next
        // test: both are process-wide statics shared across the whole suite.
        DesktopConfig.get().resetToDefaults();
        AppIcons.clearCache();
    }

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
    @DisplayName("a non-bundled size resizes the bundled glyph, never the MissingIcon")
    void nonBundledSizeResizesGlyph() {
        // The quick-launch strip asks for 22px, but the bundled toolbar glyphs
        // only ship at 16/24; a naive loadIcon(...,22,22) returns the red-X
        // MissingIcon. iconFor must load a bundled edge and resize instead.
        Icon icon = AppIcons.iconFor("Mail 3D", null, 22);
        assertNotNull(icon, "a family app resolves at a non-bundled size");
        assertEquals(22, icon.getIconWidth());
        assertEquals(22, icon.getIconHeight());
        assertFalse(icon instanceof com.protonmail.landrevillejf.MissingIcon,
                "a family glyph must never degrade to the red-X MissingIcon");
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

    @Test
    @DisplayName("the Application Launcher rocket is preferred descriptor artwork")
    void launcherDescriptorIconIsPreferred() {
        // The 3D start menu shows the launcher's rocket PNG; the 2D desktop must
        // show the same artwork rather than a name-derived "AL" initials tile.
        assertTrue(AppIcons.prefersDescriptorIcon("resources/images/icon/launcher.png"));
        assertTrue(AppIcons.prefersDescriptorIcon("  resources/images/icon/launcher.png  "),
                "the resource path is trimmed before matching");
    }

    @Test
    @DisplayName("only genuine per-app artwork is preferred; everything else is name-derived")
    void otherDescriptorIconsAreNotPreferred() {
        assertFalse(AppIcons.prefersDescriptorIcon("resources/images/icon/gitgui.png"));
        assertFalse(AppIcons.prefersDescriptorIcon("resources/images/icon/defaultapp.png"));
        assertFalse(AppIcons.prefersDescriptorIcon(null));
        assertFalse(AppIcons.prefersDescriptorIcon("   "));
    }

    @Test
    @DisplayName("the launcher still resolves to a real icon when its PNG is off the classpath")
    void preferredIconDegradesToTileWhenPngMissing() {
        // On the test classpath the runtime-resources "resources/" tree is not
        // assembled, so the preferred PNG cannot be resolved; iconFor must then
        // fall through to a name-derived icon rather than return null.
        Icon icon = AppIcons.iconFor(
                "Application Launcher", "resources/images/icon/launcher.png", 16);
        assertNotNull(icon, "a missing preferred PNG degrades, never blanks the entry");
        assertEquals(16, icon.getIconWidth());
    }

    @Test
    @DisplayName("clearCache invalidates the cache so the next call rebuilds")
    void clearCacheInvalidatesCache() {
        // Drive this through an active pack: the override path builds a brand-new
        // ImageIcon on every resolve, whereas the generated-art path can hand back
        // an IconManager-shared instance that would make identity assertions moot.
        DesktopConfig.get().setIconPack("testpack");
        AppIcons.clearCache();
        String res = "resources/images/icon/chess.png";

        Icon first = AppIcons.iconFor("Chess", res, 16);
        assertSame(first, AppIcons.iconFor("Chess", res, 16), "a cache hit shares one instance");
        AppIcons.clearCache();
        Icon rebuilt = AppIcons.iconFor("Chess", res, 16);
        assertNotSame(first, rebuilt, "after clearCache the icon is rebuilt");
        assertEquals(Color.RED, centerColor(rebuilt), "and still resolves the packed PNG");
    }

    @Test
    @DisplayName("an active icon pack overrides the generated art")
    void iconPackOverridesGeneratedArt() {
        // With no pack selected, Chess resolves to its generated art (a semantic
        // glyph or an initials tile) - never the solid-red fixture PNG.
        DesktopConfig.get().resetToDefaults();
        AppIcons.clearCache();
        assertNotNull(AppIcons.iconFor("Chess", "resources/images/icon/chess.png", 16));

        // Activate the bundled "testpack" fixture, whose chess.png is solid red,
        // and clear the cache the way Desktop2D.applyIconPack does. The pack
        // override is consulted first, so the packed PNG must now win.
        DesktopConfig.get().setIconPack("testpack");
        AppIcons.clearCache();
        Icon overridden = AppIcons.iconFor("Chess", "resources/images/icon/chess.png", 16);
        assertNotNull(overridden);
        assertEquals(16, overridden.getIconWidth());
        assertEquals(Color.RED, centerColor(overridden),
                "the packed PNG replaced the generated art");
    }

    /** Renders an icon headless and returns the colour at its centre pixel. */
    private static Color centerColor(Icon icon) {
        int w = Math.max(1, icon.getIconWidth());
        int h = Math.max(1, icon.getIconHeight());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return new Color(img.getRGB(w / 2, h / 2) & 0xFFFFFF);
    }
}
