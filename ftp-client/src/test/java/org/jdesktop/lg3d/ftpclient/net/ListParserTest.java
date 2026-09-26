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
package org.jdesktop.lg3d.ftpclient.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ListParser} against representative Unix {@code LIST}, Windows/NT
 * {@code LIST} and machine-readable {@code MLSD} lines, including directories,
 * symlinks, names with spaces, and the rows that must be dropped (blank,
 * {@code total} header, {@code .}/{@code ..}).
 */
class ListParserTest {

    @Test
    @DisplayName("a Unix file line yields size, permissions and a positive mtime")
    void unixFile() {
        RemoteEntry e = ListParser.parse("-rw-r--r-- 1 owner group 1024 Feb 15 2025 notes.txt");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("notes.txt");
        assertThat(e.getSize()).isEqualTo(1024L);
        assertThat(e.isDirectory()).isFalse();
        assertThat(e.getPermissions()).isEqualTo("rw-r--r--");
        assertThat(e.getLinkTarget()).isNull();
        assertThat(e.getModifiedMillis()).isPositive();
    }

    @Test
    @DisplayName("a Unix directory line reports UNKNOWN_SIZE and the directory flag")
    void unixDirectory() {
        RemoteEntry e = ListParser.parse("drwxr-xr-x 2 owner group 4096 Jan 01 2026 pub");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("pub");
        assertThat(e.isDirectory()).isTrue();
        assertThat(e.getSize()).isEqualTo(RemoteEntry.UNKNOWN_SIZE);
        assertThat(e.getPermissions()).isEqualTo("rwxr-xr-x");
    }

    @Test
    @DisplayName("a Unix symlink splits the name from its target")
    void unixSymlink() {
        RemoteEntry e = ListParser.parse("lrwxrwxrwx 1 owner group 11 Mar 01 2025 link -> target.txt");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("link");
        assertThat(e.getLinkTarget()).isEqualTo("target.txt");
        assertThat(e.isDirectory()).isFalse();
    }

    @Test
    @DisplayName("a Unix time-only date (no year) is rebased and stays positive")
    void unixTimeOnlyDate() {
        RemoteEntry e = ListParser.parse("-rw-r--r-- 1 owner group 10 Jan 01 12:30 recent.log");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("recent.log");
        assertThat(e.getModifiedMillis()).isPositive();
    }

    @Test
    @DisplayName("names containing spaces are preserved whole")
    void spacesInName() {
        RemoteEntry e = ListParser.parse("-rw-r--r-- 1 owner group 10 Jan 01 2026 my summer holiday.txt");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("my summer holiday.txt");
    }

    @Test
    @DisplayName("a Windows <DIR> line is a directory")
    void windowsDirectory() {
        RemoteEntry e = ListParser.parse("01-01-26 12:00PM <DIR>          pub");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("pub");
        assertThat(e.isDirectory()).isTrue();
        assertThat(e.getSize()).isEqualTo(RemoteEntry.UNKNOWN_SIZE);
    }

    @Test
    @DisplayName("a Windows file line reports its size")
    void windowsFile() {
        RemoteEntry e = ListParser.parse("02-15-2025 03:30PM               1024 notes.txt");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("notes.txt");
        assertThat(e.getSize()).isEqualTo(1024L);
        assertThat(e.isDirectory()).isFalse();
        assertThat(e.getModifiedMillis()).isPositive();
    }

    @Test
    @DisplayName("an MLSD file line maps type/size/modify facts")
    void mlsdFile() {
        RemoteEntry e = ListParser.parse("type=file;size=4096;modify=20260101120000; report.txt");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("report.txt");
        assertThat(e.getSize()).isEqualTo(4096L);
        assertThat(e.isDirectory()).isFalse();
        assertThat(e.getModifiedMillis()).isPositive();
    }

    @Test
    @DisplayName("an MLSD dir fact (and a trailing slash) marks a directory")
    void mlsdDirectory() {
        RemoteEntry e = ListParser.parse("type=dir;modify=20260101120000; pub");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("pub");
        assertThat(e.isDirectory()).isTrue();

        RemoteEntry slashed = ListParser.parse("type=dir;modify=20260101120000; data/");
        assertThat(slashed).isNotNull();
        assertThat(slashed.getName()).isEqualTo("data");
        assertThat(slashed.isDirectory()).isTrue();
    }

    @Test
    @DisplayName("MLSD cdir/pdir rows are dropped")
    void mlsdNavigationDropped() {
        assertThat(ListParser.parse("type=cdir;modify=20260101120000; .")).isNull();
        assertThat(ListParser.parse("type=pdir;modify=20260101120000; ..")).isNull();
    }

    @Test
    @DisplayName("fractional MLSD seconds are tolerated")
    void mlsdFractionalSeconds() {
        RemoteEntry e = ListParser.parse("type=file;size=8;modify=20260101120000.123; f.bin");
        assertThat(e).isNotNull();
        assertThat(e.getName()).isEqualTo("f.bin");
        assertThat(e.getSize()).isEqualTo(8L);
        assertThat(e.getModifiedMillis()).isPositive();
    }

    @Test
    @DisplayName("blank lines, the total header and navigation rows parse to null")
    void droppedLines() {
        assertThat(ListParser.parse(null)).isNull();
        assertThat(ListParser.parse("")).isNull();
        assertThat(ListParser.parse("   ")).isNull();
        assertThat(ListParser.parse("total 42")).isNull();
        assertThat(ListParser.parse("drwxr-xr-x 2 owner group 4096 Jan 01 2026 .")).isNull();
        assertThat(ListParser.parse("drwxr-xr-x 2 owner group 4096 Jan 01 2026 ..")).isNull();
        assertThat(ListParser.parse("this is not a listing line at all")).isNull();
    }

    @Test
    @DisplayName("parseAll keeps the parseable rows and drops the rest, in order")
    void parseAll() {
        List<String> lines = Arrays.asList(
                "total 12",
                "drwxr-xr-x 2 owner group 4096 Jan 01 2026 .",
                "drwxr-xr-x 2 owner group 4096 Jan 01 2026 ..",
                "-rw-r--r-- 1 owner group 1024 Feb 15 2025 notes.txt",
                "",
                "drwxr-xr-x 3 owner group 4096 Jan 01 2026 pub",
                "type=file;size=99;modify=20260101120000; extra.dat");
        List<RemoteEntry> entries = ListParser.parseAll(lines);
        assertThat(entries).extracting(RemoteEntry::getName)
                .containsExactly("notes.txt", "pub", "extra.dat");
    }

    @Test
    @DisplayName("parseAll is null-safe and returns an empty list for no input")
    void parseAllEmpty() {
        assertThat(ListParser.parseAll(null)).isEmpty();
        assertThat(ListParser.parseAll(List.of())).isEmpty();
        assertThat(ListParser.parseAll(List.of("total 0", "  "))).isEmpty();
    }
}
