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
package org.jdesktop.lg3d.utils.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code icon.pack} and {@code icon.packDir} fields on
 * {@link DesktopConfig}: the blank defaults (generated icons, no import), the
 * in-memory round-trip with trimming, that {@code null} clears each back to the
 * blank default, and that {@code resetToDefaults()} clears both. The setters only
 * mutate in-memory state (no {@code save()}), and each test restores the defaults
 * afterwards, so the shared singleton is left clean and no real user preference
 * is written.
 */
class DesktopConfigIconPackTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the icon pack and import dir default to blank")
    void defaultsAreBlank() {
        cfg.resetToDefaults();
        assertEquals("", cfg.getIconPack(), "no pack selected -> generated icons");
        assertEquals("", cfg.getIconPackDir(), "no imported folder/zip");
        assertEquals("", DesktopConfig.DEFAULT_ICON_PACK);
        assertEquals("", DesktopConfig.DEFAULT_ICON_PACK_DIR);
    }

    @Test
    @DisplayName("the pack id and import dir round-trip, trimmed")
    void roundTrips() {
        cfg.setIconPack("  mono  ");
        assertEquals("mono", cfg.getIconPack());
        cfg.setIconPackDir("  /home/me/packs/vivid  ");
        assertEquals("/home/me/packs/vivid", cfg.getIconPackDir());
    }

    @Test
    @DisplayName("null clears both back to the blank default")
    void nullClears() {
        cfg.setIconPack("mono");
        cfg.setIconPackDir("/tmp/pack.zip");
        cfg.setIconPack(null);
        assertEquals("", cfg.getIconPack(), "null clears the pack id");
        cfg.setIconPackDir(null);
        assertEquals("", cfg.getIconPackDir(), "null clears the import dir");
    }

    @Test
    @DisplayName("resetToDefaults clears the icon pack selection")
    void resetClears() {
        cfg.setIconPack("vivid");
        cfg.setIconPackDir("/tmp/pack.zip");
        cfg.resetToDefaults();
        assertEquals("", cfg.getIconPack());
        assertEquals("", cfg.getIconPackDir());
    }
}
