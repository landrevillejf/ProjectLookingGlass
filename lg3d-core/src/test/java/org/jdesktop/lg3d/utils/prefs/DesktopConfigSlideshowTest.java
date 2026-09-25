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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the wallpaper-slideshow fields on {@link DesktopConfig}: their
 * defaults, the interval clamping and the folder normalisation. The setters
 * only mutate in-memory state (no {@code save()}), and each test restores the
 * defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigSlideshowTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the slideshow defaults are off, 5 minutes, bundled folder")
    void defaults() {
        cfg.resetToDefaults();
        assertFalse(cfg.isSlideshowEnabled());
        assertEquals(DesktopConfig.DEFAULT_SLIDESHOW_INTERVAL_SEC, cfg.getSlideshowIntervalSec());
        assertEquals("", cfg.getSlideshowFolder());
    }

    @Test
    @DisplayName("the enable flag round-trips")
    void enableRoundTrips() {
        cfg.setSlideshowEnabled(true);
        assertTrue(cfg.isSlideshowEnabled());
        cfg.setSlideshowEnabled(false);
        assertFalse(cfg.isSlideshowEnabled());
    }

    @Test
    @DisplayName("the interval is clamped to the min/max range")
    void intervalClamped() {
        cfg.setSlideshowIntervalSec(1);
        assertEquals(DesktopConfig.MIN_SLIDESHOW_INTERVAL_SEC, cfg.getSlideshowIntervalSec());
        cfg.setSlideshowIntervalSec(999_999);
        assertEquals(DesktopConfig.MAX_SLIDESHOW_INTERVAL_SEC, cfg.getSlideshowIntervalSec());
        cfg.setSlideshowIntervalSec(60);
        assertEquals(60, cfg.getSlideshowIntervalSec(), "an in-range value is kept");
    }

    @Test
    @DisplayName("the folder is trimmed and null falls back to the bundled default")
    void folderNormalized() {
        cfg.setSlideshowFolder("  /home/me/Pictures  ");
        assertEquals("/home/me/Pictures", cfg.getSlideshowFolder());
        cfg.setSlideshowFolder(null);
        assertEquals("", cfg.getSlideshowFolder());
        cfg.setSlideshowFolder("   ");
        assertEquals("", cfg.getSlideshowFolder(), "a blank folder means bundled");
    }
}
