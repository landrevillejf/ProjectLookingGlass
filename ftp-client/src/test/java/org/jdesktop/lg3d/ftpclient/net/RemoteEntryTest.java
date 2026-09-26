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

/** Covers the immutable {@link RemoteEntry} value object and its null-safe defaults. */
class RemoteEntryTest {

    @Test
    @DisplayName("the full constructor records every field verbatim")
    void fullConstructor() {
        RemoteEntry e = new RemoteEntry("notes.txt", 2048L, 1_700_000_000_000L,
                false, "rw-r--r--", null);
        assertThat(e.getName()).isEqualTo("notes.txt");
        assertThat(e.getSize()).isEqualTo(2048L);
        assertThat(e.getModifiedMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(e.isDirectory()).isFalse();
        assertThat(e.getPermissions()).isEqualTo("rw-r--r--");
        assertThat(e.getLinkTarget()).isNull();
    }

    @Test
    @DisplayName("a null name and null permissions degrade to empty strings")
    void nullSafe() {
        RemoteEntry e = new RemoteEntry(null, 10L, 0L, true, null, "target");
        assertThat(e.getName()).isEmpty();
        assertThat(e.getPermissions()).isEmpty();
        assertThat(e.getLinkTarget()).isEqualTo("target");
    }

    @Test
    @DisplayName("the four-argument constructor is a plain file/dir with no detail")
    void shortConstructor() {
        RemoteEntry dir = new RemoteEntry("pub", RemoteEntry.UNKNOWN_SIZE, 0L, true);
        assertThat(dir.isDirectory()).isTrue();
        assertThat(dir.getSize()).isEqualTo(RemoteEntry.UNKNOWN_SIZE);
        assertThat(dir.getPermissions()).isEmpty();
        assertThat(dir.getLinkTarget()).isNull();
    }

    @Test
    @DisplayName("isHidden flags a leading dot but not the navigation entries")
    void hidden() {
        assertThat(new RemoteEntry(".bashrc", 1L, 0L, false).isHidden()).isTrue();
        assertThat(new RemoteEntry(".", 1L, 0L, true).isHidden()).isFalse();
        assertThat(new RemoteEntry("..", 1L, 0L, true).isHidden()).isFalse();
        assertThat(new RemoteEntry("visible", 1L, 0L, false).isHidden()).isFalse();
    }

    @Test
    @DisplayName("isSelfOrParent matches only . and ..")
    void selfOrParent() {
        assertThat(new RemoteEntry(".", 0L, 0L, true).isSelfOrParent()).isTrue();
        assertThat(new RemoteEntry("..", 0L, 0L, true).isSelfOrParent()).isTrue();
        assertThat(new RemoteEntry(".hidden", 0L, 0L, false).isSelfOrParent()).isFalse();
    }

    @Test
    @DisplayName("equality is by value across every field")
    void equality() {
        RemoteEntry a = new RemoteEntry("f", 5L, 7L, false, "rwxrwxrwx", "l");
        RemoteEntry b = new RemoteEntry("f", 5L, 7L, false, "rwxrwxrwx", "l");
        RemoteEntry c = new RemoteEntry("f", 6L, 7L, false, "rwxrwxrwx", "l");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("not an entry");
        assertThat(a).isEqualTo(a);
    }

    @Test
    @DisplayName("toString marks directories and files distinctly")
    void stringForm() {
        assertThat(new RemoteEntry("dir", -1L, 0L, true).toString()).startsWith("[D] dir");
        assertThat(new RemoteEntry("file", 42L, 0L, false).toString()).startsWith("[F] file");
    }
}
