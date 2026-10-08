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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
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

    @Test
    @DisplayName("the content paints a white card with dark text, never a grey block")
    void contentPaintsWhiteNotGrey() {
        // Regression guard for the "grey rectangle" start-up bug: the splash is
        // a white card, so once it is actually painted (show() now forces one
        // synchronous paint before the EDT-blocking desktop build) the user sees
        // white with the name and version, not the window's grey peer
        // background. Painting to an off-screen image works headless, so the
        // rendered pixels can be asserted directly.
        JPanel content = Desktop2DSplash.buildContent();
        Dimension pref = content.getPreferredSize();
        content.setSize(pref);
        content.doLayout();
        BufferedImage img = new BufferedImage(Math.max(1, pref.width),
                Math.max(1, pref.height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            content.paint(g);
        } finally {
            g.dispose();
        }
        int white = 0;
        int dark = 0;
        int total = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int gr = (p >> 8) & 0xff;
                int b = p & 0xff;
                total++;
                if (r > 240 && gr > 240 && b > 240) {
                    white++;
                }
                if (r < 110 && gr < 110 && b < 110) {
                    dark++;
                }
            }
        }
        assertTrue(white > total / 2,
                "the splash paints a predominantly white card, not a grey block");
        assertTrue(dark > 0,
                "the product name and version are drawn as dark text on the card");
    }

    @Test
    @DisplayName("the looking-glass is omitted when there is no mascot to reflect")
    void mirrorWithoutMascotIsNull() {
        assertNull(Desktop2DSplash.buildMirror(null),
                "no mascot means no mirror, never a blank frame");
    }

    @Test
    @DisplayName("the looking-glass shows the mascot through the glass with a reflection")
    void mirrorPaintsMascotAndReflection() {
        BufferedImage mascot = new BufferedImage(40, 60, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mascot.createGraphics();
        mg.setColor(Color.RED);
        mg.fillRect(0, 0, 40, 60);
        mg.dispose();

        JComponent mirror = Desktop2DSplash.buildMirror(mascot);
        assertNotNull(mirror, "a mascot yields a looking-glass component");
        Dimension pref = mirror.getPreferredSize();
        assertTrue(pref.width >= 40, "the glass is at least as wide as the mascot");
        assertTrue(pref.height > 60,
                "glass plus its reflection is taller than the mascot alone");

        mirror.setSize(pref);
        BufferedImage img = new BufferedImage(Math.max(1, pref.width),
                Math.max(1, pref.height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            mirror.paint(g);
        } finally {
            g.dispose();
        }
        assertTrue(containsRed(img),
                "the mascot is visible through the looking-glass");
    }

    @Test
    @DisplayName("the mascot degrades to null off the runtime classpath, omitting the mirror")
    void mascotDegradesAndContentStillBuilds() {
        // The runtime-resources "resources/" tree is only on the desktop run
        // classpath, not the test classpath, so the PNG cannot be resolved here;
        // loadMascot must return null (not throw) and buildContent must simply
        // omit the mirror rather than fail.
        assertNull(Desktop2DSplash.loadMascot());
        assertNotNull(Desktop2DSplash.buildContent());
    }

    /** True when any pixel of {@code img} carries the fixture's red. */
    private static boolean containsRed(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (r > 120 && g < 90 && b < 90) {
                    return true;
                }
            }
        }
        return false;
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
