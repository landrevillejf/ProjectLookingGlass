/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.protonmail.landrevillejf.IconManager.IconCategory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.Icon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the launcher's bridge to the bundled IconManager glyph
 * library. The bundled jar is on the test classpath, so the catalogue, glyph
 * loading and PNG export are all exercised for real; only the picker dialog
 * itself touches a display and is verified at runtime, not here.
 */
class IconLibraryTest {

    @Test
    @DisplayName("the bundled glyph library is available and catalogued")
    void libraryIsAvailable() {
        assertTrue(IconLibrary.isAvailable(), "IconManager is on the classpath");
        List<IconCategory> categories = IconLibrary.categories();
        assertFalse(categories.isEmpty());
        assertEquals(IconCategory.GENERAL, categories.get(0),
                "categories are advertised in a stable, friendly order");
    }

    @Test
    @DisplayName("each category has a friendly label")
    void categoriesHaveLabels() {
        assertEquals("General", IconLibrary.label(IconCategory.GENERAL));
        assertEquals("Media", IconLibrary.label(IconCategory.MEDIA));
        assertEquals("Tables", IconLibrary.label(IconCategory.TABLE));
    }

    @Test
    @DisplayName("glyph names are distinct base names without a size suffix")
    void glyphNamesAreStrippedBases() {
        List<String> media = IconLibrary.glyphNames(IconCategory.MEDIA);
        assertFalse(media.isEmpty());
        assertTrue(media.contains("Play"), "Play is a bundled media glyph");
        assertTrue(media.contains("Movie"), "Movie is a bundled media glyph");
        for (String name : media) {
            assertFalse(name.endsWith(".gif"), "no file suffix leaks through");
            assertFalse(name.matches(".*\\d+$"), "no size suffix leaks through");
        }
    }

    @Test
    @DisplayName("a known glyph loads, an unknown or blank one does not")
    void loadResolvesKnownGlyphsOnly() {
        Icon play = IconLibrary.load(IconCategory.MEDIA, "Play");
        assertNotNull(play, "a bundled glyph loads a real icon");
        assertTrue(play.getIconWidth() > 0);

        assertNull(IconLibrary.load(IconCategory.MEDIA, "NotARealGlyph"),
                "an unknown name yields the MissingIcon, treated as no glyph");
        assertNull(IconLibrary.load(IconCategory.MEDIA, null));
        assertNull(IconLibrary.load(IconCategory.MEDIA, "   "));
    }

    @Test
    @DisplayName("exporting a glyph writes a PNG and returns its absolute path")
    void exportWritesPng(@TempDir Path dir) {
        Path exported = IconLibrary.export(IconCategory.MEDIA, "Play", dir);
        assertNotNull(exported);
        assertTrue(exported.isAbsolute());
        assertTrue(Files.exists(exported), "the PNG is written to disk");
        assertEquals("Play.png", exported.getFileName().toString());
        assertTrue(dir.toFile().list() != null && dir.toFile().list().length > 0);
    }

    @Test
    @DisplayName("exporting an unknown glyph writes nothing")
    void exportRejectsUnknownGlyph(@TempDir Path dir) {
        assertNull(IconLibrary.export(IconCategory.MEDIA, "NotARealGlyph", dir));
        assertNull(IconLibrary.export(IconCategory.MEDIA, null, dir));
    }

    @Test
    @DisplayName("the default icon store lives under the launcher config dir")
    void defaultIconDirIsUnderLauncherConfig() {
        Path dir = IconLibrary.defaultIconDir();
        assertTrue(dir.endsWith(Path.of(".config", "lg3d", "launchers", "icons")),
                "glyphs export beside the saved launchers: " + dir);
    }

    @Test
    @DisplayName("stripSize removes the bundled edge suffix")
    void stripSizeRemovesEdge() {
        assertEquals("About", IconLibrary.stripSize("About16.gif"));
        assertEquals("About", IconLibrary.stripSize("About24.gif"));
        assertEquals("", IconLibrary.stripSize(null));
        assertEquals("NoSuffix", IconLibrary.stripSize("NoSuffix"));
    }

    @Test
    @DisplayName("sanitize produces a safe PNG file stem")
    void sanitizeIsFileSafe() {
        assertEquals("Foo_Bar", IconLibrary.sanitize("Foo Bar"));
        assertEquals("icon", IconLibrary.sanitize(null));
        assertEquals("icon", IconLibrary.sanitize("   "));
        assertEquals("Play", IconLibrary.sanitize("Play"));
    }
}
