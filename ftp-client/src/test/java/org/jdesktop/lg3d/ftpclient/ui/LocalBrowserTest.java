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
package org.jdesktop.lg3d.ftpclient.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link LocalBrowser}'s synchronous, headless-safe surface: construction
 * rooted at the home directory, directory navigation (including the ignore and
 * parent-of-file rules), and a stable empty selection.
 */
class LocalBrowserTest {

    @Test
    @DisplayName("construction is headless-safe and starts at the user home")
    void construction() {
        LocalBrowser b = new LocalBrowser();
        assertThat(b).isNotNull();
        assertThat(b.getCurrentDirectory())
                .isEqualTo(new File(System.getProperty("user.home")));
        assertThat(b.getSelectedFiles()).isEmpty();
    }

    @Test
    @DisplayName("setDirectory navigates to a readable directory")
    void setDirectory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.createDirectory(dir.resolve("sub"));
        LocalBrowser b = new LocalBrowser();
        File target = dir.toFile();
        b.setDirectory(target);
        assertThat(b.getCurrentDirectory()).isEqualTo(target);
        // A refresh over a populated directory must not throw and keeps the cursor.
        b.refresh();
        assertThat(b.getCurrentDirectory()).isEqualTo(target);
        assertThat(b.getSelectedFiles()).isEmpty();
    }

    @Test
    @DisplayName("setDirectory(null) is ignored")
    void setDirectoryNull(@TempDir Path dir) {
        LocalBrowser b = new LocalBrowser();
        b.setDirectory(dir.toFile());
        File before = b.getCurrentDirectory();
        b.setDirectory(null);
        assertThat(b.getCurrentDirectory()).isEqualTo(before);
    }

    @Test
    @DisplayName("pointing at a regular file lands on its parent directory")
    void setDirectoryToFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("only.txt");
        Files.writeString(file, "x", StandardCharsets.UTF_8);
        LocalBrowser b = new LocalBrowser();
        b.setDirectory(file.toFile());
        assertThat(b.getCurrentDirectory()).isEqualTo(dir.toFile());
    }

    @Test
    @DisplayName("an activation callback can be attached without throwing")
    void activationCallback() {
        LocalBrowser b = new LocalBrowser();
        b.setOnActivate(files -> {
            // no-op
        });
        b.setOnActivate(null);
        assertThat(b.getSelectedFiles()).isEmpty();
    }
}
