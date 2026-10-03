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
package org.jdesktop.lg3d.displayserver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the 2D desktop start-up splash. The version resolution is
 * a pure helper ({@link Desktop2DSplash#resolveVersion}) so every fallback branch
 * is exercised directly without mutating global system state, and the content is
 * a plain {@code JPanel} ({@link Desktop2DSplash#buildContent}) that constructs
 * headless. {@code show}/{@code dispose} create a top-level {@code JWindow}, so
 * they are only asserted to be safe no-ops under {@code java.awt.headless=true}.
 */
class Desktop2DSplashTest {

    @Test
    @DisplayName("resolveVersion prefers the system property, then the build info")
    void resolveVersionPrecedence() {
        assertEquals("1.2.3",
                Desktop2DSplash.resolveVersion("1.2.3", "9.9.9"),
                "the lg.version system property wins over the build version");
        assertEquals("9.9.9",
                Desktop2DSplash.resolveVersion(null, "9.9.9"),
                "falls back to the generated build version when the property is absent");
        assertEquals("9.9.9",
                Desktop2DSplash.resolveVersion("   ", "9.9.9"),
                "a blank property is treated as absent");
    }

    @Test
    @DisplayName("resolveVersion trims and degrades to unknown")
    void resolveVersionFallback() {
        assertEquals("1.2.3", Desktop2DSplash.resolveVersion("  1.2.3  ", null),
                "the resolved value is trimmed");
        assertEquals(Desktop2DSplash.UNKNOWN_VERSION,
                Desktop2DSplash.resolveVersion(null, null));
        assertEquals(Desktop2DSplash.UNKNOWN_VERSION,
                Desktop2DSplash.resolveVersion("", "  "));
    }

    @Test
    @DisplayName("getVersion never returns null or blank")
    void versionIsResolved() {
        String version = Desktop2DSplash.getVersion();
        assertNotNull(version);
        assertFalse(version.isBlank());
    }

    @Test
    @DisplayName("versionLine is the label plus the resolved version")
    void versionLineFormat() {
        assertEquals("Version " + Desktop2DSplash.getVersion(),
                Desktop2DSplash.versionLine());
        assertTrue(Desktop2DSplash.versionLine()
                .startsWith(Desktop2DSplash.VERSION_LABEL));
    }

    @Test
    @DisplayName("the product name is Project Looking Glass")
    void productName() {
        assertEquals("Project Looking Glass", Desktop2DSplash.PRODUCT_NAME);
        assertEquals("lg.version", Desktop2DSplash.VERSION_PROPERTY);
    }

    @Test
    @DisplayName("buildContent shows only the product name and the version")
    void contentCarriesNameAndVersion() {
        JPanel content = Desktop2DSplash.buildContent();
        assertNotNull(content);
        List<String> labels = labelTexts(content);
        assertTrue(labels.contains(Desktop2DSplash.PRODUCT_NAME),
                "the splash carries the product name");
        assertTrue(labels.contains(Desktop2DSplash.versionLine()),
                "the splash carries the version line");
        assertEquals(2, labels.size(),
                "just the name and the version - nothing else");
        for (String text : labels) {
            assertFalse(text.contains("3D"),
                    "the 2D splash must not mention 3D");
            assertFalse(text.contains("Sun"),
                    "the 2D splash must not mention Sun Microsystems");
        }
    }

    @Test
    @DisplayName("show and dispose are safe no-ops when headless")
    void showAndDisposeHeadless() {
        // The test task runs with java.awt.headless=true, so neither call may
        // create a JWindow or throw; they simply do nothing.
        assertDoesNotThrow(Desktop2DSplash::show);
        assertDoesNotThrow(Desktop2DSplash::dispose);
    }

    /** The text of every {@link JLabel} directly held by {@code panel}. */
    private static List<String> labelTexts(JPanel panel) {
        List<String> texts = new ArrayList<>();
        for (Component child : panel.getComponents()) {
            if (child instanceof JLabel) {
                texts.add(((JLabel) child).getText());
            }
        }
        return texts;
    }
}
