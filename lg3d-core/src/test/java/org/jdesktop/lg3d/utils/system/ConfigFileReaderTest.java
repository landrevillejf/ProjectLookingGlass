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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdesktop.lg3d.utils.system.ConfigFileReader.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ConfigFileReader}, the shared bounded read-only file
 * view extracted from {@link PrivacyService} (the §3.5 read half of
 * {@code system-management-contract.md}). Every case runs against a
 * {@link TempDir} - no process is spawned and nothing is written - so the suite
 * is CI-safe. It pins the honest-degradation contract (missing / unreadable /
 * truncated) and the exact {@link Content#describe()} labels the viewers show.
 */
class ConfigFileReaderTest {

    @Test
    @DisplayName("a null or blank path is reported as not present")
    void nullAndBlankGuard() {
        Content c = ConfigFileReader.read(null);
        assertFalse(c.isExists());
        assertFalse(c.isReadable());
        assertEquals("", c.getText());
        assertEquals("not present", c.describe());
        assertFalse(ConfigFileReader.read("   ").isExists());
    }

    @Test
    @DisplayName("a missing file is reported as not present")
    void readMissing(@TempDir Path dir) {
        Content c = ConfigFileReader.read(dir.resolve("nope.conf").toString());
        assertFalse(c.isExists());
        assertFalse(c.isReadable());
        assertEquals(0, c.getLineCount());
        assertEquals("not present", c.describe());
    }

    @Test
    @DisplayName("a small readable file is returned whole with a line-count label")
    void readPresent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("main.cf");
        Files.writeString(f, "line one\nline two\nline three\n", StandardCharsets.UTF_8);
        Content c = ConfigFileReader.read(f.toString());
        assertTrue(c.isExists());
        assertTrue(c.isReadable());
        assertFalse(c.isTruncated());
        assertEquals("line one\nline two\nline three\n", c.getText());
        assertEquals(3, c.getLineCount());
        assertEquals("3 lines", c.describe());
    }

    @Test
    @DisplayName("a single line without a trailing newline counts as one line")
    void readSingleLine(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("exports");
        Files.writeString(f, "/srv 10.0.0.0/8(ro)", StandardCharsets.UTF_8);
        Content c = ConfigFileReader.read(f.toString());
        assertTrue(c.isReadable());
        assertEquals(1, c.getLineCount());
        assertEquals("1 line", c.describe());
    }

    @Test
    @DisplayName("an unreadable path (a directory) degrades to honest unreadable")
    void readUnreadable(@TempDir Path dir) {
        Content c = ConfigFileReader.read(dir.toString());
        assertTrue(c.isExists(), "the directory exists");
        assertFalse(c.isReadable(), "a directory is not a readable text file");
        assertEquals("", c.getText());
        assertEquals("present, but not readable without privileges", c.describe());
    }

    @Test
    @DisplayName("a file larger than the cap yields the trailing window, truncated")
    void readTruncation(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("big.log");
        int cap = 4096;
        int over = cap + 2000;
        Files.writeString(f, "x".repeat(over), StandardCharsets.UTF_8);
        Content c = ConfigFileReader.read(f.toString(), cap);
        assertTrue(c.isExists());
        assertTrue(c.isReadable());
        assertTrue(c.isTruncated());
        assertEquals(cap, c.getText().length(), "only the trailing cap bytes are kept");
        assertEquals("showing the last " + (cap / 1024) + " KiB of a larger file", c.describe());
    }

    @Test
    @DisplayName("a non-positive cap reads nothing but stays honest about existence")
    void readZeroCap(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("main.cf");
        Files.writeString(f, "some text\n", StandardCharsets.UTF_8);
        Content c = ConfigFileReader.read(f.toString(), 0);
        assertTrue(c.isExists());
        assertTrue(c.isReadable());
        assertTrue(c.isTruncated(), "a zero cap truncates everything");
        assertEquals("", c.getText());
    }

    @Test
    @DisplayName("the default cap is the documented 256 KiB and never throws")
    void defaultCapAndNeverThrows() {
        assertEquals(256 * 1024, ConfigFileReader.DEFAULT_MAX_BYTES);
        assertNotNull(assertDoesNotThrow(() -> ConfigFileReader.read("/definitely/not/here")));
    }
}
