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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the one server decision the 3D walk-in makes: the
 * address it connects to is ensured through exactly the same
 * {@link WonderlandServerLauncher} contract the 2D browser panel uses
 * &mdash; Wonderland must not execute differently per desktop. Constructing
 * {@link WonderlandWorld3D} itself needs the live 3D desktop (it builds the
 * frame through the scene manager), so it stays probe-verified; this static
 * half is the part that is a pure choice and is therefore asserted.
 */
class WonderlandWorld3DTest {

    /** A probe over mutable sets: "up" answers ready, "|module" answers hosting. */
    private static WonderlandServerLauncher.Probe byUrl(
            Set<String> up, Set<String> hosted) {
        return new WonderlandServerLauncher.Probe() {
            @Override
            public boolean ready(String server) {
                return up.contains(server);
            }

            @Override
            public boolean hosting(String server, String module) {
                return up.contains(server) && hosted.contains(server + "|" + module);
            }
        };
    }

    /** The port a recorded argv binds to. */
    private static String boundPort(List<String> argv) {
        return argv.stream().filter(a -> a.startsWith("--server.port="))
                .findFirst().orElseThrow().substring("--server.port=".length());
    }

    @Test
    @DisplayName("a server that already serves the world is left exactly as the panel leaves it")
    void liveWorldIsNotTouched() {
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                Path.of("/unused"),
                argv -> {
                    throw new AssertionError("a live world must not spawn a second server");
                },
                byUrl(Set.of("http://localhost:8080"),
                        Set.of("http://localhost:8080|" + WonderlandServerLauncher.DEFAULT_SEED)),
                Duration.ZERO);

        assertEquals("http://localhost:8080",
                WonderlandWorld3D.liveServer(launcher, "http://localhost:8080"));
    }

    @Test
    @DisplayName("a squatting server with no live world: the walk-in relocates like Start does")
    void relocationIsFollowed(@TempDir Path repo) throws IOException {
        Path jar = repo.resolve("wonderland-server-app.jar");
        Files.createFile(jar);
        String before = System.getProperty(WonderlandServerLauncher.JAR_PROPERTY);
        try {
            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, jar.toString());
            // 8080 answers but serves nothing (the stale-jar zombie), 8081 is
            // somebody else's; the desktop's own server comes up on 8082 and
            // the walk-in must follow it, not stay on the empty 8080.
            Set<String> up = new java.util.HashSet<>(
                    List.of("http://localhost:8080", "http://localhost:8081"));
            WonderlandServerLauncher.Spawner spawnAndRaise = argv -> {
                up.add("http://localhost:" + boundPort(argv));
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
            WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                    repo, spawnAndRaise, byUrl(up, Set.of()), Duration.ofSeconds(5));

            assertEquals("http://localhost:8082",
                    WonderlandWorld3D.liveServer(launcher, "http://localhost:8080"));
            assertTrue(launcher.isManaging());
            launcher.shutdown();
        } finally {
            if (before == null) {
                System.clearProperty(WonderlandServerLauncher.JAR_PROPERTY);
            } else {
                System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, before);
            }
        }
    }

    @Test
    @DisplayName("an address the desktop cannot bring up is still the one it asks for")
    void unstartableLocalAddressFallsThroughToTheSession(@TempDir Path repo) {
        // No jar anywhere and nothing answers: ensureRunning degrades to a
        // not-ready Result, and the walk-in still connects to what was asked,
        // so the session's own failure line (naming bootRun) stays the last
        // word instead of a new dead end.
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                repo, argv -> null, server -> false, Duration.ZERO);

        assertEquals("http://localhost:8080",
                WonderlandWorld3D.liveServer(launcher, "http://localhost:8080"));
    }

    @Test
    @DisplayName("a remote server is never started for the 3D walk-in, just walked into")
    void remoteAddressIsNotOursToStart(@TempDir Path repo) {
        WonderlandServerLauncher launcher = new WonderlandServerLauncher(
                repo,
                argv -> {
                    throw new AssertionError("a remote server is not ours to spawn");
                },
                server -> false, Duration.ZERO);

        assertEquals("http://world.example.org:8080",
                WonderlandWorld3D.liveServer(launcher, "http://world.example.org:8080"));
    }
}
