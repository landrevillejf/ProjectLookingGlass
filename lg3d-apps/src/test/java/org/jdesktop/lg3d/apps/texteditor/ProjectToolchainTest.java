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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the project-local tool resolver. Each case builds a real
 * temp tree - a wrapper, a provisioned {@code .espresso/tools} distribution, or
 * a distribution unpacked at the project root - and asserts both the resolution
 * priority and the hard contract that nothing falls back to the system PATH. The
 * root-level case is the reported bug: a project shipping {@code gradle-8.10/}
 * used to be told "Gradle is not available". The wrapper cases carry the other
 * reported bug: a hand-written wrapper that resolves {@code java} from PATH
 * discards the JDK Espresso pinned, so it no longer wins on name alone.
 */
class ProjectToolchainTest {

    private static Path file(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "");
    }

    private static void writeToolchainProps(Path root, String buildSystem, String toolVersion,
                                            String jdkId) throws IOException {
        Files.createDirectories(root.resolve(".espresso"));
        Files.writeString(root.resolve(".espresso").resolve("toolchain.properties"),
                "buildSystem=" + buildSystem + "\n"
                        + "toolVersion=" + toolVersion + "\n"
                        + "jdkId=" + jdkId + "\n");
    }

    private static Path makeJdkHome(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    /**
     * A wrapper script, either the one {@code gradle wrapper} generates (which
     * picks its JVM from {@code JAVA_HOME}) or the few-line stub a scaffolder
     * hand-writes (which resolves {@code java} from PATH and so ignores the
     * pinned JDK). That difference is what the priority rules turn on.
     */
    private static Path writeWrapper(Path script, boolean honorsJavaHome) throws IOException {
        Files.createDirectories(script.getParent());
        String body = honorsJavaHome
                ? "#!/bin/sh\nif [ -n \"$JAVA_HOME\" ]; then JAVACMD=\"$JAVA_HOME/bin/java\"; fi\n"
                : "#!/bin/sh\nexec java -jar gradle/wrapper/gradle-wrapper.jar \"$@\"\n";
        return Files.writeString(script, body, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a wrapper that honours JAVA_HOME wins over a provisioned distribution")
    void creditingWrapperBeatsProvisionedTool(@TempDir Path tmp) throws Exception {
        Path wrapper = writeWrapper(tmp.resolve("gradlew"), true);
        file(tmp.resolve(".espresso/tools/gradle-8.10/bin/gradle"));
        assertEquals(Optional.of(wrapper), ProjectToolchain.gradleLauncher(tmp));
    }

    @Test
    @DisplayName("a wrapper that ignores JAVA_HOME yields to the provisioned distribution")
    void javaHomeIgnoringWrapperYieldsToProvisioned(@TempDir Path tmp) throws Exception {
        writeWrapper(tmp.resolve("gradlew"), false);
        Path gradle = file(tmp.resolve(".espresso/tools/gradle-8.10/bin/gradle"));
        assertEquals(Optional.of(gradle), ProjectToolchain.gradleLauncher(tmp));
    }

    @Test
    @DisplayName("a wrapper that ignores JAVA_HOME is still the last resort")
    void stubWrapperIsTheLastResort(@TempDir Path tmp) throws Exception {
        Path wrapper = writeWrapper(tmp.resolve("gradlew"), false);
        assertEquals(Optional.of(wrapper), ProjectToolchain.gradleLauncher(tmp));
    }

    @Test
    @DisplayName("a provisioned tool under .espresso/tools is used when there is no wrapper")
    void provisionedToolUsedWithoutWrapper(@TempDir Path tmp) throws Exception {
        Path gradle = file(tmp.resolve(".espresso/tools/gradle-8.10/bin/gradle"));
        assertEquals(Optional.of(gradle), ProjectToolchain.gradleLauncher(tmp));

        Path mvn = file(tmp.resolve(".espresso/tools/apache-maven-3.9.9/bin/mvn"));
        assertEquals(Optional.of(mvn), ProjectToolchain.mavenLauncher(tmp));
    }

    @Test
    @DisplayName("a distribution unpacked at the project root is found (the reported bug)")
    void rootLevelDistributionIsFound(@TempDir Path tmp) throws Exception {
        Path gradle = file(tmp.resolve("gradle-8.10/bin/gradle"));
        assertEquals(Optional.of(gradle), ProjectToolchain.gradleLauncher(tmp));

        Path mvn = file(tmp.resolve("apache-maven-3.9.9/bin/mvn"));
        assertEquals(Optional.of(mvn), ProjectToolchain.mavenLauncher(tmp));
    }

    @Test
    @DisplayName("a bare <root>/gradle distribution is found as a last resort")
    void bareGradleDirIsFound(@TempDir Path tmp) throws Exception {
        Path gradle = file(tmp.resolve("gradle/bin/gradle"));
        assertEquals(Optional.of(gradle), ProjectToolchain.gradleLauncher(tmp));
    }

    @Test
    @DisplayName("an empty project resolves to nothing - never the system PATH")
    void neverFallsBackToSystemPath(@TempDir Path tmp) {
        // Even when a real `gradle`/`mvn` is installed globally, an unprovisioned
        // project must report no launcher rather than silently using the system one.
        assertTrue(ProjectToolchain.gradleLauncher(tmp).isEmpty());
        assertTrue(ProjectToolchain.mavenLauncher(tmp).isEmpty());
        assertTrue(ProjectToolchain.gradleLauncher(null).isEmpty());
    }

    @Test
    @DisplayName("the selected JDK resolves through the manager, and its metadata reads back")
    void jdkHomeResolvesThroughRegistry(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("store");
        Path home = makeJdkHome(base.resolve("jdks").resolve("jdk-21.0.4+7"), "21.0.4");
        JdkRegistry registry = new JdkRegistry(base, (url, dest) -> {
        });
        Path root = tmp.resolve("proj");
        Files.createDirectories(root);
        writeToolchainProps(root, "GRADLE", "8.10", "jdk-21.0.4+7");

        assertEquals(Optional.of(home), ProjectToolchain.jdkHome(root, registry));
        assertEquals("jdk-21.0.4+7", ProjectToolchain.jdkId(root));
        assertEquals(BuildSystem.GRADLE, ProjectToolchain.buildSystem(root));
        assertEquals("8.10", ProjectToolchain.toolVersion(root));
    }

    @Test
    @DisplayName("no toolchain file, or an unknown jdkId, resolves to no JDK")
    void jdkHomeEmptyWhenUnsetOrUnknown(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = new JdkRegistry(tmp.resolve("store"), (url, dest) -> {
        });
        Path root = tmp.resolve("proj");
        Files.createDirectories(root);
        assertTrue(ProjectToolchain.jdkHome(root, registry).isEmpty(), "no toolchain file");

        writeToolchainProps(root, "NONE", "", "ghost");
        assertTrue(ProjectToolchain.jdkHome(root, registry).isEmpty(), "an unknown id finds nothing");
        assertEquals(BuildSystem.NONE, ProjectToolchain.buildSystem(tmp.resolve("absent")));
        assertEquals("", ProjectToolchain.jdkId(tmp.resolve("absent")));
    }
}
