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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the pure remote-path helpers shared by the FTP and SFTP adapters. */
class RemotePathsTest {

    @Test
    @DisplayName("isAbsolute recognises a leading separator only")
    void isAbsolute() {
        assertThat(RemotePaths.isAbsolute("/pub")).isTrue();
        assertThat(RemotePaths.isAbsolute("/")).isTrue();
        assertThat(RemotePaths.isAbsolute("pub")).isFalse();
        assertThat(RemotePaths.isAbsolute(null)).isFalse();
    }

    @Test
    @DisplayName("normalize forces a leading slash, collapses runs and strips a trailing slash")
    void normalize() {
        assertThat(RemotePaths.normalize(null)).isEqualTo("/");
        assertThat(RemotePaths.normalize("   ")).isEqualTo("/");
        assertThat(RemotePaths.normalize("pub/files")).isEqualTo("/pub/files");
        assertThat(RemotePaths.normalize("/pub//files///")).isEqualTo("/pub/files");
        assertThat(RemotePaths.normalize("/")).isEqualTo("/");
        assertThat(RemotePaths.normalize("  /a/b  ")).isEqualTo("/a/b");
    }

    @Test
    @DisplayName("baseName returns the trailing segment, empty for the root")
    void baseName() {
        assertThat(RemotePaths.baseName("/pub/file.txt")).isEqualTo("file.txt");
        assertThat(RemotePaths.baseName("/only")).isEqualTo("only");
        assertThat(RemotePaths.baseName("/")).isEmpty();
        assertThat(RemotePaths.baseName(null)).isEmpty();
    }

    @Test
    @DisplayName("parent walks up one level and stops at the root")
    void parent() {
        assertThat(RemotePaths.parent("/pub/files")).isEqualTo("/pub");
        assertThat(RemotePaths.parent("/pub")).isEqualTo("/");
        assertThat(RemotePaths.parent("/")).isEqualTo("/");
        assertThat(RemotePaths.parent("relative/deep")).isEqualTo("/relative");
    }

    @Test
    @DisplayName("join combines a directory and a child with a single separator")
    void join() {
        assertThat(RemotePaths.join("/pub", "file.txt")).isEqualTo("/pub/file.txt");
        assertThat(RemotePaths.join("/", "file.txt")).isEqualTo("/file.txt");
        assertThat(RemotePaths.join("/pub", "/file.txt")).isEqualTo("/pub/file.txt");
        assertThat(RemotePaths.join(null, "file.txt")).isEqualTo("/file.txt");
        assertThat(RemotePaths.join("/pub", "  ")).isEqualTo("/pub");
        assertThat(RemotePaths.join("/pub", null)).isEqualTo("/pub");
    }
}
