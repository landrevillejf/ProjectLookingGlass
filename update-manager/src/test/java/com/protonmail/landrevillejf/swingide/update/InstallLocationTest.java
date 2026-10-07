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
package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection of the release bundle install root: the explicit property override,
 * the inference from the running jar, and the layout validation.
 */
class InstallLocationTest {

    @TempDir
    Path tempDir;

    /** Builds a minimal valid bundle root: a {@code lib/} dir next to {@code lg3d.sh}. */
    private Path makeBundle(Path root) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lg3d.sh"), "#!/bin/bash\n");
        return root;
    }

    private static Function<String, String> props(String value) {
        return key -> InstallLocation.INSTALL_DIR_PROPERTY.equals(key) ? value : null;
    }

    @Test
    void detectReturnsThePropertyOverrideWhenItIsAValidBundle() throws IOException {
        Path root = makeBundle(tempDir.resolve("bundle"));
        InstallLocation location = new InstallLocation(() -> null, props(root.toString()));

        assertThat(location.detect()).contains(root);
    }

    @Test
    void detectFallsBackOnTheJarPathWhenThePropertyIsNotABundle() throws IOException {
        Path ignored = tempDir.resolve("not-a-bundle");
        Files.createDirectories(ignored);
        Path root = makeBundle(tempDir.resolve("bundle"));
        Path jar = root.resolve("lib").resolve("update-manager-1.0.jar");
        Files.writeString(jar, "jar");

        InstallLocation location = new InstallLocation(() -> jar, props(ignored.toString()));

        assertThat(location.detect()).contains(root);
    }

    @Test
    void detectInfersTheRootFromTheRunningJar() throws IOException {
        Path root = makeBundle(tempDir.resolve("bundle"));
        Path jar = root.resolve("lib").resolve("update-manager-1.0.jar");
        Files.writeString(jar, "jar");

        InstallLocation location = new InstallLocation(() -> jar, props(null));

        assertThat(location.detect()).contains(root);
    }

    @Test
    void detectIsEmptyWhenTheJarIsNotInsideABundle() throws IOException {
        Path plain = tempDir.resolve("plain");
        Files.createDirectories(plain);
        Path jar = plain.resolve("app.jar");
        Files.writeString(jar, "jar");

        InstallLocation location = new InstallLocation(() -> jar, props(null));

        assertThat(location.detect()).isEmpty();
    }

    @Test
    void detectIsEmptyWhenThePropertyIsBlankAndThereIsNoJar() {
        InstallLocation location = new InstallLocation(() -> null, props("   "));

        assertThat(location.detect()).isEmpty();
    }

    @Test
    void detectIsEmptyWhenTheSupplierThrows() {
        Supplier<Path> boom = () -> {
            throw new IllegalStateException("no code source");
        };
        InstallLocation location = new InstallLocation(boom, props(null));

        assertThat(location.detect()).isEmpty();
    }

    @Test
    void detectIsEmptyWhenTheJarHasNoParent() {
        InstallLocation location = new InstallLocation(() -> Path.of("bare.jar"), props(null));

        assertThat(location.detect()).isEmpty();
    }

    @Test
    void detectIgnoresAnInvalidPropertyPath() {
        // A NUL byte is rejected by Path.of, exercising the invalid-path guard.
        InstallLocation location = new InstallLocation(() -> null, props("/bad\0path"));

        assertThat(location.detect()).isEmpty();
    }

    @Test
    void defaultConstructorDetectsNoBundleInATestJvm() {
        Optional<Path> detected = new InstallLocation().detect();

        assertThat(detected).isEmpty();
    }

    @Test
    void isBundleLayoutRejectsNullAndIncompleteRoots() throws IOException {
        Path onlyLauncher = tempDir.resolve("only-launcher");
        Files.createDirectories(onlyLauncher);
        Files.writeString(onlyLauncher.resolve("lg3d.sh"), "#!/bin/bash\n");

        Path onlyLib = tempDir.resolve("only-lib");
        Files.createDirectories(onlyLib.resolve("lib"));

        assertThat(InstallLocation.isBundleLayout(null)).isFalse();
        assertThat(InstallLocation.isBundleLayout(onlyLauncher)).isFalse();
        assertThat(InstallLocation.isBundleLayout(onlyLib)).isFalse();
        assertThat(InstallLocation.isBundleLayout(makeBundle(tempDir.resolve("ok")))).isTrue();
    }
}
