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
package org.jdesktop.lg3d.apps.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link BackupProfile} bean: defaults, defensive setters,
 * compression clamping, the comma-separated exclude helper, deep copy and
 * Jackson round-tripping (the format {@link BackupStore} persists).
 */
class BackupProfileTest {

    @Test
    @DisplayName("a default profile has sane, non-null values")
    void defaults() {
        BackupProfile p = new BackupProfile();
        assertEquals("New Backup", p.getName());
        assertEquals(System.getProperty("user.home"), p.getDestinationDir());
        assertEquals("backup", p.getArchiveBaseName());
        assertEquals(BackupProfile.DEFAULT_COMPRESSION, p.getCompressionLevel());
        assertFalse(p.isIncludeHidden());
        assertTrue(p.isTimestampArchiveName());
        assertTrue(p.getSources().isEmpty());
        assertTrue(p.getExcludePatterns().isEmpty());
    }

    @Test
    @DisplayName("the convenience constructor assigns name, dest and base")
    void convenienceConstructor() {
        BackupProfile p = new BackupProfile("Docs", "/tmp/dest", "docs-arc");
        assertEquals("Docs", p.getName());
        assertEquals("/tmp/dest", p.getDestinationDir());
        assertEquals("docs-arc", p.getArchiveBaseName());
    }

    @Test
    @DisplayName("blank name/dest/base fall back to defaults")
    void blankValuesFallBack() {
        BackupProfile p = new BackupProfile();
        p.setName("   ");
        p.setDestinationDir("");
        p.setArchiveBaseName(null);
        assertEquals("New Backup", p.getName());
        assertEquals(System.getProperty("user.home"), p.getDestinationDir());
        assertEquals("backup", p.getArchiveBaseName());
    }

    @Test
    @DisplayName("compression level is clamped to 0..9")
    void compressionClamped() {
        BackupProfile p = new BackupProfile();
        p.setCompressionLevel(-5);
        assertEquals(0, p.getCompressionLevel());
        p.setCompressionLevel(99);
        assertEquals(9, p.getCompressionLevel());
        p.setCompressionLevel(7);
        assertEquals(7, p.getCompressionLevel());
    }

    @Test
    @DisplayName("null lists are coerced to empty, and setters copy defensively")
    void nullListsCoerced() {
        BackupProfile p = new BackupProfile();
        p.setSources(null);
        p.setExcludePatterns(null);
        assertTrue(p.getSources().isEmpty());
        assertTrue(p.getExcludePatterns().isEmpty());

        java.util.List<String> src = new java.util.ArrayList<>(Arrays.asList("/a", "/b"));
        p.setSources(src);
        src.add("/c");
        assertEquals(2, p.getSources().size(), "the profile must copy, not alias, the list");
    }

    @Test
    @DisplayName("exclude patterns round-trip through the CSV helper")
    void excludeCsvRoundTrip() {
        BackupProfile p = new BackupProfile();
        p.setExcludePatternsFromCsv("*.log, *.tmp ,  ,cache/");
        assertEquals(Arrays.asList("*.log", "*.tmp", "cache/"), p.getExcludePatterns());
        assertEquals("*.log, *.tmp, cache/", p.getExcludePatternsAsCsv());
        p.setExcludePatternsFromCsv(null);
        assertTrue(p.getExcludePatterns().isEmpty());
    }

    @Test
    @DisplayName("copy() yields an equal but independent profile")
    void copyIsDeep() {
        BackupProfile p = new BackupProfile("Orig", "/dest", "base");
        p.getSources().add("/src");
        p.getExcludePatterns().add("*.bak");
        p.setCompressionLevel(3);
        p.setIncludeHidden(true);

        BackupProfile c = p.copy();
        assertNotSame(p, c);
        assertEquals("Orig", c.getName());
        assertEquals("/dest", c.getDestinationDir());
        assertEquals("base", c.getArchiveBaseName());
        assertEquals(3, c.getCompressionLevel());
        assertTrue(c.isIncludeHidden());
        assertEquals(p.getSources(), c.getSources());
        assertEquals(p.getExcludePatterns(), c.getExcludePatterns());

        // Mutating the copy must not touch the original.
        c.getSources().add("/other");
        c.setName("Changed");
        assertEquals(1, p.getSources().size());
        assertEquals("Orig", p.getName());
    }

    @Test
    @DisplayName("a profile survives a Jackson round-trip")
    void jsonRoundTrip() throws Exception {
        BackupProfile p = new BackupProfile("Round", "/tmp/o", "rt");
        p.getSources().add("/tmp/o/data");
        p.setExcludePatternsFromCsv("*.log, *.tmp");
        p.setCompressionLevel(9);
        p.setIncludeHidden(true);
        p.setTimestampArchiveName(false);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(p);
        BackupProfile back = mapper.readValue(json, BackupProfile.class);

        assertEquals(p.getName(), back.getName());
        assertEquals(p.getDestinationDir(), back.getDestinationDir());
        assertEquals(p.getArchiveBaseName(), back.getArchiveBaseName());
        assertEquals(p.getCompressionLevel(), back.getCompressionLevel());
        assertEquals(p.isIncludeHidden(), back.isIncludeHidden());
        assertEquals(p.isTimestampArchiveName(), back.isTimestampArchiveName());
        assertEquals(p.getSources(), back.getSources());
        assertEquals(p.getExcludePatterns(), back.getExcludePatterns());
    }

    @Test
    @DisplayName("toString is the profile name (used by the JList renderer)")
    void toStringIsName() {
        assertEquals("Docs", new BackupProfile("Docs", "/d", "b").toString());
        BackupProfile p = new BackupProfile();
        p.setName(null);
        assertEquals("New Backup", p.toString());
    }
}
