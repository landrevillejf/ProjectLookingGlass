/*
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the decisions the Wonderland server launcher makes: which
 * addresses it may start, which port and command line it builds, where it finds
 * the built jar, and how {@code ensureRunning} reports each outcome. The real
 * {@code ProcessBuilder} spawn and the HTTP probe are exercised through the
 * injected seams, so no subprocess and no network is touched &mdash; exactly the
 * split {@link ExternalSoftphoneBackend} uses for its own launcher.
 */
class WonderlandServerLauncherTest {

    /** A spawner that records the argv it was handed and returns a fake child. */
    private static WonderlandServerLauncher.Spawner recording(List<String> sink) {
        return argv -> {
            sink.addAll(argv);
            return new WonderlandServerLauncher.Launched() {
                @Override
                public boolean alive() {
                    return true;
                }

                @Override
                public void stop() {
                    // fake child: nothing to reap
                }
            };
        };
    }

    /** A probe answering a fixed sequence, then its last value forever. */
    private static WonderlandServerLauncher.Probe sequence(boolean... answers) {
        AtomicInteger i = new AtomicInteger();
        return server -> {
            int n = i.getAndIncrement();
            return answers[Math.min(n, answers.length - 1)];
        };
    }

    @Test
    @DisplayName("the port is the address's, else the Spring Boot default")
    void parsesPort() {
        assertEquals(9090, WonderlandServerLauncher.portOf("http://localhost:9090"));
        assertEquals(WonderlandServerLauncher.DEFAULT_PORT,
                WonderlandServerLauncher.portOf("http://localhost"));
    }

    @Test
    @DisplayName("only a localhost address is auto-startable")
    void recognisesLocalAddresses() {
        assertTrue(WonderlandServerLauncher.isLocal("http://localhost:8080"));
        assertTrue(WonderlandServerLauncher.isLocal("http://127.0.0.1:8080"));
        assertTrue(WonderlandServerLauncher.isLocal("http://LOCALHOST:8080"));
        assertFalse(WonderlandServerLauncher.isLocal("http://world.example.org:8080"));
        assertEquals("world.example.org",
                WonderlandServerLauncher.host("http://world.example.org:8080"));
    }

    @Test
    @DisplayName("the command line runs java -jar <app> on the chosen port")
    void buildsCommand() {
        Path jar = Path.of("/tmp/wonderland-server-1.0-app.jar");
        List<String> argv = WonderlandServerLauncher.command(jar, 9090);

        assertEquals("-jar", argv.get(1), String.valueOf(argv));
        assertEquals(jar.toString(), argv.get(2), String.valueOf(argv));
        assertTrue(argv.contains("--server.port=9090"), String.valueOf(argv));
        assertTrue(argv.get(0).endsWith("java"), argv.get(0));
    }

    @Test
    void findsTheAppJarInABuildLayout(@TempDir Path repo) throws IOException {
        Path libs = repo.resolve("wonderland-server").resolve("build-gradle").resolve("libs");
        Files.createDirectories(libs);
        Path app = libs.resolve("wonderland-server-1.69.0-dev-app.jar");
        Files.createFile(app);
        Files.createFile(libs.resolve("wonderland-server-1.69.0-dev.jar")); // not the -app one

        assertEquals(app, WonderlandServerLauncher.findServerJar(repo));
    }

    @Test
    void findsNoJarUnderAnAbsentOrWrongRoot(@TempDir Path repo) {
        assertNull(WonderlandServerLauncher.findServerJar(repo.resolve("absent")));
        assertNull(WonderlandServerLauncher.findServerJar(null));
    }

    @Test
    void resolveJarHonoursTheNamedJarProperty(@TempDir Path repo) throws IOException {
        Path named = repo.resolve("explicit-app.jar");
        Files.createFile(named);
        String before = System.getProperty(WonderlandServerLauncher.JAR_PROPERTY);
        try {
            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, named.toString());
            WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                    repo, argv -> null, server -> false, Duration.ZERO);
            assertEquals(named, launcher.resolveJar());

            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, repo.resolve("gone.jar").toString());
            assertNull(launcher.resolveJar()); // a named jar that is not readable is no jar
        } finally {
            if (before == null) {
                System.clearProperty(WonderlandServerLauncher.JAR_PROPERTY);
            } else {
                System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, before);
            }
        }
    }

    @Test
    void ensureRunningReportsAnAlreadyAnsweringServer() {
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                Path.of("/unused"), argv -> null, sequence(true), Duration.ZERO);

        WonderlandServerLauncher.Result result = launcher.ensureRunning("localhost:8080");

        assertTrue(result.ready());
        assertFalse(result.launched());
        assertTrue(result.note().contains("already answers"), result.note());
    }

    @Test
    void ensureRunningRefusesToStartARemoteServer() {
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                Path.of("/unused"), argv -> null, sequence(false), Duration.ZERO);

        WonderlandServerLauncher.Result result =
                launcher.ensureRunning("http://world.example.org:8080");

        assertFalse(result.ready());
        assertFalse(result.launched());
        assertTrue(result.note().contains("remote server"), result.note());
    }

    @Test
    void ensureRunningFallsBackWhenNoJarIsBuilt(@TempDir Path repo) {
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                repo, argv -> null, sequence(false), Duration.ZERO);

        WonderlandServerLauncher.Result result = launcher.ensureRunning("localhost:8080");

        assertFalse(result.ready());
        assertFalse(result.launched());
        assertTrue(result.note().contains("no wonderland-server jar found"), result.note());
    }

    @Test
    void ensureRunningSpawnsThenWaitsUntilTheProbeAnswers(@TempDir Path repo)
            throws IOException {
        Path jar = repo.resolve("wonderland-server-app.jar");
        Files.createFile(jar);
        String before = System.getProperty(WonderlandServerLauncher.JAR_PROPERTY);
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        try {
            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, jar.toString());
            // pre-check false -> not up; the first await poll true -> it came up
            WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                    repo, recording(argv), sequence(false, true), Duration.ofSeconds(5));

            WonderlandServerLauncher.Result result = launcher.ensureRunning("localhost:8080");

            assertTrue(result.ready());
            assertTrue(result.launched());
            assertTrue(result.note().contains("started wonderland-server"), result.note());
            assertTrue(argv.contains("--server.port=8080"), String.valueOf(argv));
            assertTrue(launcher.isManaging());
            launcher.shutdown();
            assertFalse(launcher.isManaging());
        } finally {
            if (before == null) {
                System.clearProperty(WonderlandServerLauncher.JAR_PROPERTY);
            } else {
                System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, before);
            }
        }
    }

    @Test
    void ensureRunningSurvivesASpawnFailure(@TempDir Path repo) throws IOException {
        Path jar = repo.resolve("wonderland-server-app.jar");
        Files.createFile(jar);
        String before = System.getProperty(WonderlandServerLauncher.JAR_PROPERTY);
        try {
            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, jar.toString());
            WonderlandServerLauncher.Spawner failing = argv -> {
                throw new IOException("cannot exec");
            };
            WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                    repo, failing, sequence(false), Duration.ofSeconds(5));

            WonderlandServerLauncher.Result result = launcher.ensureRunning("localhost:8080");

            assertFalse(result.ready());
            assertFalse(result.launched());
            assertTrue(result.note().contains("could not start"), result.note());
        } finally {
            if (before == null) {
                System.clearProperty(WonderlandServerLauncher.JAR_PROPERTY);
            } else {
                System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, before);
            }
        }
    }
}
