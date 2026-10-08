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
 * Covers the 3D-desktop corner-logo model id on {@link DesktopConfig}: its
 * default (the classic Java logo), the in-memory round-trip, and the
 * normalization that maps unknown/null/blank tokens back to the default. The
 * setter only mutates in-memory state (no {@code save()}), and each test
 * restores the defaults afterwards, so the shared singleton is left clean.
 */
class DesktopConfigCornerLogoTest {

    private final DesktopConfig cfg = DesktopConfig.get();

    @AfterEach
    void restore() {
        cfg.resetToDefaults();
    }

    @Test
    @DisplayName("the corner-logo default is the classic Java logo")
    void defaultIsJava() {
        cfg.resetToDefaults();
        assertEquals("java", cfg.getCornerLogo());
        assertEquals("java", DesktopConfig.DEFAULT_CORNER_LOGO);
    }

    @Test
    @DisplayName("the corner-logo id round-trips")
    void roundTrips() {
        cfg.setCornerLogo("mascot");
        assertEquals("mascot", cfg.getCornerLogo());
        cfg.setCornerLogo("java");
        assertEquals("java", cfg.getCornerLogo());
    }

    @Test
    @DisplayName("unknown, null and blank ids all fall back to java")
    void normalizesUnknownToJava() {
        cfg.setCornerLogo("garbage");
        assertEquals("java", cfg.getCornerLogo());
        cfg.setCornerLogo(null);
        assertEquals("java", cfg.getCornerLogo());
        cfg.setCornerLogo("   ");
        assertEquals("java", cfg.getCornerLogo());
    }

    @Test
    @DisplayName("the id is trimmed and lower-cased")
    void normalizesCase() {
        cfg.setCornerLogo("  MASCOT  ");
        assertEquals("mascot", cfg.getCornerLogo());
    }

    @Test
    @DisplayName("resetToDefaults returns the corner logo to java")
    void resetReturnsToJava() {
        cfg.setCornerLogo("mascot");
        cfg.resetToDefaults();
        assertEquals("java", cfg.getCornerLogo());
    }
}
