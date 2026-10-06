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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.protonmail.landrevillejf.IconManager.IconCategory;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.Icon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link IconGlyphLibrary}'s never-throw bridge over the bundled
 * IconManager glyph catalogue: category/glyph enumeration, size-suffix
 * stripping, the {@code MissingIcon}-to-null mapping and rendering a glyph into
 * a {@link BufferedImage}. The glyph-loading tests are skipped when the bundled
 * jar is absent ({@link IconGlyphLibrary#isAvailable()} is false); the pure
 * string helpers always run. Everything is headless.
 */
class IconGlyphLibraryTest {

    @Test
    @DisplayName("stripSize removes a trailing <edge>.gif suffix")
    void stripSizeHelper() {
        assertEquals("Play", IconGlyphLibrary.stripSize("Play24.gif"));
        assertEquals("About", IconGlyphLibrary.stripSize("About16.gif"));
        assertEquals("NoSuffix", IconGlyphLibrary.stripSize("NoSuffix"));
        assertEquals("", IconGlyphLibrary.stripSize(null));
    }

    @Test
    @DisplayName("categories are enumerated in a stable, labelled order")
    void categoriesAndLabels() {
        List<IconCategory> categories = IconGlyphLibrary.categories();
        assertFalse(categories.isEmpty(), "the category list is fixed and non-empty");
        assertEquals(IconCategory.GENERAL, categories.get(0));
        assertEquals("General", IconGlyphLibrary.label(IconCategory.GENERAL));
        assertEquals("Tables", IconGlyphLibrary.label(IconCategory.TABLE));
    }

    @Test
    @DisplayName("toImage(null) is null and a painted icon fills the requested edge")
    void toImageNullSafe() {
        assertNull(IconGlyphLibrary.toImage(null, 16));
        BufferedImage solid = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        Icon icon = new javax.swing.ImageIcon(solid);
        BufferedImage painted = IconGlyphLibrary.toImage(icon, 4);
        assertNotNull(painted);
        assertEquals(4, painted.getWidth());
        assertEquals(4, painted.getHeight());
    }

    @Test
    @DisplayName("a null or blank glyph name never loads")
    void blankGlyphIsNull() {
        assertNull(IconGlyphLibrary.loadIcon(IconCategory.GENERAL, null, 16));
        assertNull(IconGlyphLibrary.loadIcon(IconCategory.GENERAL, "   ", 16));
        assertNull(IconGlyphLibrary.loadImage(IconCategory.GENERAL, null, 48));
    }

    @Test
    @DisplayName("an unknown glyph name maps MissingIcon to null, not a red X")
    void unknownGlyphIsNull() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        assertNull(IconGlyphLibrary.loadIcon(IconCategory.GENERAL,
                "___no_such_glyph_" + System.nanoTime() + "___", 16));
    }

    @Test
    @DisplayName("a real glyph enumerates, loads and renders to an image")
    void realGlyphEnumeratesAndRenders() {
        assumeTrue(IconGlyphLibrary.isAvailable(), "IconManager jar absent");
        List<String> names = IconGlyphLibrary.glyphNames(IconCategory.GENERAL);
        assertFalse(names.isEmpty(), "the GENERAL category ships glyphs");

        String glyph = names.get(0);
        Icon icon = IconGlyphLibrary.loadIcon(IconCategory.GENERAL, glyph, 16);
        assertNotNull(icon, "a bundled glyph loads at a bundled edge: " + glyph);

        BufferedImage image = IconGlyphLibrary.loadImage(IconCategory.GENERAL, glyph, 48);
        assertNotNull(image, "a bundled glyph renders to an image for saving");
        assertEquals(48, image.getWidth());
        assertEquals(48, image.getHeight());
        assertTrue(image.getType() == BufferedImage.TYPE_INT_ARGB,
                "the rendered image carries an alpha channel");
    }
}
