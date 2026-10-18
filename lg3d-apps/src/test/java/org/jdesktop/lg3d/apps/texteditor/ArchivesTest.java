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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless round-trips for the pure-JDK archive reader. The canned {@code .zip}
 * and {@code .tar.gz} come from {@link CannedArchives} (an independent writer),
 * so a mismatch points at {@link Archives} rather than at a fixture that shares
 * its bug. The security cases - an entry climbing out of the destination and an
 * unknown format - are asserted directly.
 */
class ArchivesTest {

    @Test
    @DisplayName("a zip round-trips its tree, and a bin/ launcher is made executable by the caller")
    void extractZipRoundTrip(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("gradle-8.10-bin.zip");
        CannedArchives.writeToolZip(zip, "gradle-8.10", "gradle");

        Path dest = tmp.resolve("out");
        Archives.extract(zip, dest);

        Path launcher = dest.resolve("gradle-8.10").resolve("bin").resolve("gradle");
        assertTrue(Files.isRegularFile(launcher), "the nested launcher extracted");
        assertTrue(Files.readString(launcher).contains("echo gradle"));
        assertEquals("license",
                Files.readString(dest.resolve("gradle-8.10").resolve("LICENSE")));
        // java.util.zip drops the unix mode, so the caller re-applies it.
        assertFalse(Files.isExecutable(launcher), "a zip member is not executable on its own");
        Archives.makeExecutable(launcher);
        assertTrue(Files.isExecutable(launcher));
    }

    @Test
    @DisplayName("a tar.gz round-trips its tree and carries the owner-execute bit through")
    void extractTarGzRoundTrip(@TempDir Path tmp) throws Exception {
        Path tgz = tmp.resolve("jdk.tar.gz");
        CannedArchives.writeJdkTarGz(tgz, "jdk-21.0.4+7", "21.0.4");

        Path dest = tmp.resolve("out");
        Archives.extract(tgz, dest);

        Path java = dest.resolve("jdk-21.0.4+7").resolve("bin").resolve("java");
        assertTrue(Files.isRegularFile(java));
        assertTrue(Files.isExecutable(java), "the 0755 member comes out executable");
        Path release = dest.resolve("jdk-21.0.4+7").resolve("release");
        assertEquals("JAVA_VERSION=\"21.0.4\"\n", Files.readString(release));
        assertFalse(Files.isExecutable(release), "a 0644 member stays non-executable");
    }

    @Test
    @DisplayName("an entry that climbs out of the destination is refused and nothing escapes")
    void zipSlipIsRefused(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.txt"));
            out.write("nope".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path dest = tmp.resolve("out");
        IOException ex = assertThrows(IOException.class, () -> Archives.extract(zip, dest));
        assertTrue(ex.getMessage().contains("escapes"), ex.getMessage());
        assertFalse(Files.exists(tmp.resolve("escaped.txt")), "nothing was written outside");
    }

    @Test
    @DisplayName("an unknown extension is rejected rather than guessed at")
    void unknownFormatIsRejected(@TempDir Path tmp) throws Exception {
        Path bogus = tmp.resolve("thing.rar");
        Files.writeString(bogus, "not an archive");
        IOException ex = assertThrows(IOException.class,
                () -> Archives.extract(bogus, tmp.resolve("out")));
        assertTrue(ex.getMessage().contains("Unsupported"), ex.getMessage());
    }
}
