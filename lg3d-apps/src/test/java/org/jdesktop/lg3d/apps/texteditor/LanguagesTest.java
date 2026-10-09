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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the {@link Languages} catalogue: extension, file-name
 * and display-name lookup, the first-registration-wins rule for shared
 * extensions and the Plain Text fallback for anything unknown.
 */
class LanguagesTest {

    @Test
    @DisplayName("the catalogue holds every built-in language, Plain Text first")
    void catalogue() {
        assertFalse(Languages.all().isEmpty());
        assertSame(Languages.PLAIN, Languages.all().get(0));
        assertEquals("Plain Text", Languages.PLAIN.getName());
        // The 16 syntax languages plus Plain Text.
        assertEquals(17, Languages.all().size());
    }

    @Test
    @DisplayName("extensions resolve to their language")
    void forExtension() {
        assertEquals("Java", Languages.forExtension("java").getName());
        assertEquals("Python", Languages.forExtension("py").getName());
        assertEquals("XML", Languages.forExtension("lgcfg").getName());
        assertEquals("Shell", Languages.forExtension("sh").getName());
        assertEquals("Markdown", Languages.forExtension("md").getName());
        // The dot and the case are both tolerated.
        assertEquals("Java", Languages.forExtension(".JAVA").getName());
    }

    @Test
    @DisplayName("unknown or absent extensions fall back to Plain Text")
    void unknownExtension() {
        assertSame(Languages.PLAIN, Languages.forExtension("zzz"));
        assertSame(Languages.PLAIN, Languages.forExtension(null));
        assertSame(Languages.PLAIN, Languages.forExtension(""));
    }

    @Test
    @DisplayName("file names resolve through their extension")
    void forFileName() {
        assertEquals("Java", Languages.forFileName("Main.java").getName());
        assertEquals("Java", Languages.forFileName("build.gradle").getName(),
                "Gradle files highlight as Java");
        assertEquals("JSON", Languages.forFileName("settings.json").getName());
        // Hidden files, trailing dots and nulls are Plain Text.
        assertSame(Languages.PLAIN, Languages.forFileName(".gitignore"));
        assertSame(Languages.PLAIN, Languages.forFileName("notes."));
        assertSame(Languages.PLAIN, Languages.forFileName(null));
    }

    @Test
    @DisplayName("display names resolve case-insensitively")
    void forName() {
        assertEquals("C / C++", Languages.forName("c / c++").getName());
        assertEquals("C#", Languages.forName("C#").getName());
        assertNull(Languages.forName("Klingon"));
        assertNull(Languages.forName(null));
    }

    @Test
    @DisplayName("a shared extension belongs to the first language registered")
    void firstRegistrationWins() {
        // ".h" is listed for C / C++ long before any other language could
        // claim it, and putIfAbsent keeps it that way.
        assertEquals("C / C++", Languages.forExtension("h").getName());
    }

    @Test
    @DisplayName("language definitions are internally consistent")
    void languageShape() {
        Language java = Languages.forName("Java");
        assertTrue(java.hasKeywords());
        assertTrue(java.getKeywords().contains("class"));
        assertEquals("//", java.getLineComment());
        assertEquals("/*", java.getBlockOpen());
        assertEquals("*/", java.getBlockClose());
        assertFalse(java.hasPreprocessor());
        assertFalse(java.isMarkup());

        Language c = Languages.forName("C / C++");
        assertTrue(c.hasPreprocessor());

        Language xml = Languages.forName("XML");
        assertTrue(xml.isMarkup());
        assertEquals("<!--", xml.getBlockOpen());
        assertEquals("-->", xml.getBlockClose());
        assertFalse(xml.hasKeywords());

        assertFalse(Languages.PLAIN.hasKeywords());
        assertNotNull(Languages.PLAIN.getStringQuotes());
        assertEquals("", Languages.PLAIN.getLineComment() == null
                ? "" : Languages.PLAIN.getLineComment());
    }
}
