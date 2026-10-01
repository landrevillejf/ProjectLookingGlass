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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the reusable {@link AboutDialog} helper's pure, headless-testable
 * logic: the null-safe title/description normalisation, the version fallback
 * chain, the fixed author / product credit lines, and the assembled content
 * panel. The modal dialog itself needs a display, so it is not constructed
 * here - only {@link AboutDialog#buildContent}, which is a plain panel.
 */
class AboutDialogTest {

    /** Every {@link JLabel} text in {@code root}, depth-first. */
    private static List<String> labelTexts(Container root) {
        List<String> texts = new ArrayList<>();
        collectLabels(root, texts);
        return texts;
    }

    private static void collectLabels(Container c, List<String> out) {
        for (Component comp : c.getComponents()) {
            if (comp instanceof JLabel) {
                out.add(((JLabel) comp).getText());
            }
            if (comp instanceof Container) {
                collectLabels((Container) comp, out);
            }
        }
    }

    /** Whether any label in {@code root} carries an icon. */
    private static boolean hasIconLabel(Container root) {
        for (Component comp : root.getComponents()) {
            if (comp instanceof JLabel && ((JLabel) comp).getIcon() != null) {
                return true;
            }
            if (comp instanceof Container && hasIconLabel((Container) comp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyContains(List<String> texts, String needle) {
        return texts.stream().anyMatch(t -> t != null && t.contains(needle));
    }

    private static boolean anyStartsWith(List<String> texts, String prefix) {
        return texts.stream().anyMatch(t -> t != null && t.startsWith(prefix));
    }

    // ------------------------------------------------------------------
    // Pure normalisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank title falls back to the default")
    void displayTitleFallsBackForBlank() {
        assertEquals("Application", AboutDialog.displayTitle(null));
        assertEquals("Application", AboutDialog.displayTitle("   "));
        assertEquals("Foo", AboutDialog.displayTitle("  Foo "));
    }

    @Test
    @DisplayName("a blank description falls back to the default blurb")
    void displayDescriptionFallsBackForBlank() {
        assertEquals(AboutDialog.DEFAULT_DESCRIPTION,
                AboutDialog.displayDescription(null));
        assertEquals(AboutDialog.DEFAULT_DESCRIPTION,
                AboutDialog.displayDescription(""));
        assertEquals("Bar", AboutDialog.displayDescription("  Bar "));
    }

    // ------------------------------------------------------------------
    // Version resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the system property wins over the manifest")
    void resolveVersionPrefersSystemProperty() {
        assertEquals("1.2.3", AboutDialog.resolveVersion("1.2.3", "9.9.9"));
    }

    @Test
    @DisplayName("a blank system property falls back to the manifest")
    void resolveVersionFallsBackToManifest() {
        assertEquals("9.9.9", AboutDialog.resolveVersion("   ", "9.9.9"));
        assertEquals("9.9.9", AboutDialog.resolveVersion(null, "9.9.9"));
    }

    @Test
    @DisplayName("no source at all resolves to unknown")
    void resolveVersionFallsBackToUnknown() {
        assertEquals("unknown", AboutDialog.resolveVersion(null, null));
        assertEquals("unknown", AboutDialog.resolveVersion("", "  "));
    }

    @Test
    @DisplayName("a resolved version is trimmed")
    void resolveVersionTrims() {
        assertEquals("1.0", AboutDialog.resolveVersion(" 1.0 ", null));
        assertEquals("2.0", AboutDialog.resolveVersion(null, " 2.0 "));
    }

    @Test
    @DisplayName("getVersion is never null or blank")
    void getVersionIsNeverBlank() {
        String version = AboutDialog.getVersion();
        assertTrue(version != null && !version.isBlank());
    }

    // ------------------------------------------------------------------
    // Credit lines
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the author is always Jean-Francois Landreville")
    void authorConstant() {
        assertEquals("Jean-Francois Landreville", AboutDialog.AUTHOR);
    }

    @Test
    @DisplayName("the credit lines are author, version and product, in order")
    void creditLines() {
        List<String> lines = AboutDialog.creditLines();
        assertEquals(3, lines.size());
        assertEquals("Author: Jean-Francois Landreville", lines.get(0));
        assertTrue(lines.get(1).startsWith("Version: "), lines.get(1));
        assertEquals(AboutDialog.PRODUCT_LINE, lines.get(2));
    }

    // ------------------------------------------------------------------
    // Content assembly (headless-safe: a plain panel, no dialog)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the content shows the title, description and every credit")
    void buildContentShowsTitleDescriptionAndCredits() {
        JPanel content = AboutDialog.buildContent("Chess", "Play a game of chess", null);
        List<String> texts = labelTexts(content);
        assertTrue(texts.contains("Chess"), texts.toString());
        assertTrue(anyContains(texts, "Play a game of chess"), texts.toString());
        assertTrue(anyContains(texts, "Jean-Francois Landreville"), texts.toString());
        assertTrue(anyStartsWith(texts, "Version: "), texts.toString());
        assertTrue(texts.contains(AboutDialog.PRODUCT_LINE), texts.toString());
    }

    @Test
    @DisplayName("a blank title and description fall back in the content")
    void buildContentFallsBackForBlank() {
        JPanel content = AboutDialog.buildContent(null, "   ", null);
        List<String> texts = labelTexts(content);
        assertTrue(texts.contains("Application"), texts.toString());
        assertTrue(anyContains(texts, AboutDialog.DEFAULT_DESCRIPTION), texts.toString());
    }

    @Test
    @DisplayName("the icon is shown when supplied and omitted when null")
    void buildContentIconPresence() {
        Icon icon = new ImageIcon(
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
        assertTrue(hasIconLabel(AboutDialog.buildContent("X", "d", icon)));
        assertFalse(hasIconLabel(AboutDialog.buildContent("X", "d", null)));
    }
}
