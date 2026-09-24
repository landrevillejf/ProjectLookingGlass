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
package com.protonmail.landrevillejf.swingide.update.privilege;

import com.protonmail.landrevillejf.swingide.update.UpdateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the writability guard wired in front of an installation.
 */
class InstallLocationGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void writableTargetNeedsNoEscalation() throws IOException {
        Path jar = tempDir.resolve("swing-ide.jar");
        Files.writeString(jar, "current");

        AtomicInteger prompts = new AtomicInteger();
        InstallLocationGuard guard = new InstallLocationGuard(true, () -> {
            prompts.incrementAndGet();
            return true;
        });

        assertThat(guard.isWritable(jar)).isTrue();
        assertThatCode(() -> guard.ensureWritable(jar)).doesNotThrowAnyException();
        assertThat(prompts).hasValue(0);
    }

    @Test
    void missingTargetInMissingDirectoryIsNotWritable() {
        Path jar = tempDir.resolve("missing-dir").resolve("swing-ide.jar");

        InstallLocationGuard guard = new InstallLocationGuard(false, () -> true);

        assertThat(guard.isWritable(jar)).isFalse();
    }

    @Test
    void nullTargetIsNotWritable() {
        InstallLocationGuard guard = new InstallLocationGuard(true, () -> true);

        assertThat(guard.isWritable(null)).isFalse();
    }

    @Test
    void escalationGrantedAllowsTheInstall() {
        Path jar = tempDir.resolve("missing-dir").resolve("swing-ide.jar");

        AtomicInteger prompts = new AtomicInteger();
        InstallLocationGuard guard = new InstallLocationGuard(true, () -> {
            prompts.incrementAndGet();
            return true;
        });

        assertThatCode(() -> guard.ensureWritable(jar)).doesNotThrowAnyException();
        assertThat(prompts).hasValue(1);
    }

    @Test
    void declinedEscalationFailsWithAnActionableMessage() {
        Path jar = tempDir.resolve("missing-dir").resolve("swing-ide.jar");

        InstallLocationGuard guard = new InstallLocationGuard(true, () -> false);

        assertThatThrownBy(() -> guard.ensureWritable(jar))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("cannot write to its installation location")
            .hasMessageContaining(jar.toString());
    }

    @Test
    void escalationDisabledNeverPrompts() {
        Path jar = tempDir.resolve("missing-dir").resolve("swing-ide.jar");

        AtomicInteger prompts = new AtomicInteger();
        InstallLocationGuard guard = new InstallLocationGuard(false, () -> {
            prompts.incrementAndGet();
            return true;
        });

        assertThatThrownBy(() -> guard.ensureWritable(jar)).isInstanceOf(UpdateException.class);
        assertThat(prompts).hasValue(0);
    }

    @Test
    void failingEscalationPromptIsHandledGracefully() {
        Path jar = tempDir.resolve("missing-dir").resolve("swing-ide.jar");

        InstallLocationGuard guard = new InstallLocationGuard(true, () -> {
            throw new IllegalStateException("no desktop");
        });

        assertThatThrownBy(() -> guard.ensureWritable(jar)).isInstanceOf(UpdateException.class);
    }
}
