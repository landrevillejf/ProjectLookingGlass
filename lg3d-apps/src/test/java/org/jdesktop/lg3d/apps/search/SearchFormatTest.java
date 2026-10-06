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
package org.jdesktop.lg3d.apps.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the pure {@link SearchFormat} helpers. */
class SearchFormatTest {

    @Test
    @DisplayName("parseSize reads bare bytes and unit suffixes")
    void parseSizeUnits() {
        assertEquals(512L, SearchFormat.parseSize("512").getAsLong());
        assertEquals(1536L, SearchFormat.parseSize("1.5K").getAsLong());
        assertEquals(1024L * 1024L, SearchFormat.parseSize("1 MB").getAsLong());
        assertEquals(2L * 1024L * 1024L * 1024L, SearchFormat.parseSize("2GB").getAsLong());
        assertEquals(100L, SearchFormat.parseSize("100b").getAsLong());
        assertEquals(1024L, SearchFormat.parseSize("  1 k  ").getAsLong());
    }

    @Test
    @DisplayName("parseSize rejects blank, malformed, negative and unknown units")
    void parseSizeInvalid() {
        assertFalse(SearchFormat.parseSize(null).isPresent());
        assertFalse(SearchFormat.parseSize("").isPresent());
        assertFalse(SearchFormat.parseSize("   ").isPresent());
        assertFalse(SearchFormat.parseSize("abc").isPresent());
        assertFalse(SearchFormat.parseSize("-5").isPresent());
        assertFalse(SearchFormat.parseSize("10XB").isPresent());
    }

    @Test
    @DisplayName("parseSize clamps an overflowing value to Long.MAX_VALUE")
    void parseSizeOverflow() {
        assertEquals(Long.MAX_VALUE, SearchFormat.parseSize("99999999999 PB").getAsLong());
    }

    @Test
    @DisplayName("formatSize renders human-readable byte counts")
    void formatSize() {
        assertEquals("0 B", SearchFormat.formatSize(0));
        assertEquals("512 B", SearchFormat.formatSize(512));
        assertEquals("1.0 KB", SearchFormat.formatSize(1024));
        assertEquals("1.5 KB", SearchFormat.formatSize(1536));
        assertEquals("1.0 MB", SearchFormat.formatSize(1024 * 1024));
        assertEquals("1.0 GB", SearchFormat.formatSize(1024L * 1024L * 1024L));
    }

    @Test
    @DisplayName("parseDays reads a positive integer and rejects the rest")
    void parseDays() {
        assertEquals(7, SearchFormat.parseDays("7").getAsInt());
        assertEquals(1, SearchFormat.parseDays(" 1 ").getAsInt());
        assertFalse(SearchFormat.parseDays("").isPresent());
        assertFalse(SearchFormat.parseDays(null).isPresent());
        assertFalse(SearchFormat.parseDays("0").isPresent());
        assertFalse(SearchFormat.parseDays("-3").isPresent());
        assertFalse(SearchFormat.parseDays("week").isPresent());
    }

    @Test
    @DisplayName("formatTimestamp blanks a zero time and renders a real one")
    void formatTimestamp() {
        assertEquals("", SearchFormat.formatTimestamp(0));
        assertEquals("", SearchFormat.formatTimestamp(-1));
        String s = SearchFormat.formatTimestamp(1_700_000_000_000L);
        assertFalse(s.isBlank());
        assertTrue(s.contains("2023"), "renders the year, got: " + s);
    }

    @Test
    @DisplayName("defaultScopes always includes Home and the file-system root")
    void defaultScopes() {
        List<SearchFormat.Scope> scopes = SearchFormat.defaultScopes();
        assertFalse(scopes.isEmpty());
        assertEquals("Home", scopes.get(0).label());
        assertEquals(Paths.get(System.getProperty("user.home")), scopes.get(0).path());
        SearchFormat.Scope last = scopes.get(scopes.size() - 1);
        assertEquals("File system", last.label());
        assertEquals(Paths.get("/"), last.path());
        // Scope.toString is the label, so it renders nicely in a JList.
        assertEquals("Home", scopes.get(0).toString());
    }

    @Test
    @DisplayName("locationOf returns the parent folder, or the path for a root")
    void locationOf() {
        assertEquals("/a/b", SearchFormat.locationOf(Paths.get("/a/b/c.txt")));
        assertEquals("/", SearchFormat.locationOf(Paths.get("/file.txt")));
        assertEquals("", SearchFormat.locationOf(null));
    }

    @Test
    @DisplayName("a Scope exposes its label and path")
    void scopeAccessors() {
        Path p = Paths.get("/tmp");
        SearchFormat.Scope scope = new SearchFormat.Scope("Tmp", p);
        assertEquals("Tmp", scope.label());
        assertEquals(p, scope.path());
    }
}
