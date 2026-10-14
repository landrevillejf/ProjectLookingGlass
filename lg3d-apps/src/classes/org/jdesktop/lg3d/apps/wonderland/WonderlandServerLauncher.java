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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Brings up a local {@code wonderland-server} for the Wonderland World browser.
 *
 * <p>The desktop has always left {@code wonderland-server} a separate process
 * (see {@code lg3d-core/build.gradle}: the desktop talks to a server, it is not
 * one), and the panel used to dead-end on a bare "cannot reach" with the
 * {@code bootRun} command for the human to run. That is honest but tedious: the
 * common case is simply that no server is up yet, on this machine, on the
 * default port. This launcher closes that last mile &mdash; when the browser
 * points at a <em>local</em> address that answers nothing, it finds the
 * {@code -app.jar} the build already produced, spawns {@code java -jar} on that
 * port, and waits until the REST surface responds.</p>
 *
 * <p>Two hard boundaries keep it safe:</p>
 * <ul>
 *   <li>It only ever starts a server for a <em>localhost</em> address. A remote
 *       server is somebody else's process; the launcher says so rather than
 *       trying to spawn one on your behalf.</li>
 *   <li>It never throws. A missing jar, a spawn failure or a server that will
 *       not come up all degrade to a not-ready {@link Result} carrying the
 *       reason, so the panel falls back to the same manual {@code bootRun} hint
 *       it used before this existed &mdash; auto-start is a convenience, not a
 *       new dependency the browser can hang on.</li>
 * </ul>
 *
 * <p>Split for testability exactly like {@link ExternalSoftphoneBackend}: the
 * decisions &mdash; is this local, which port, where is the jar, what is the
 * command line, is it ready yet &mdash; are pure and run against an injected
 * {@link Spawner} and {@link Probe}; the default {@link ProcessSpawner} and
 * {@link HttpProbe} that pay the real {@link ProcessBuilder} and socket are
 * compile-checked in CI and verified live on the desktop, never claimed from a
 * headless run.</p>
 */
public final class WonderlandServerLauncher {

    /**
     * System property naming the repository root the launcher searches for the
     * built server jar. Defaults to the JVM's working directory, which is the
     * repository root under {@code :lg3d-core:run} and {@code ./run-lg3d.sh}.
     */
    public static final String REPO_PROPERTY = "lg.wonderland.repo";

    /**
     * System property naming the server jar to run directly, bypassing the
     * search &mdash; the escape hatch for a non-standard layout, mirroring the
     * {@code lg.wonderland.*} vocabulary {@link WorldCatalog} already uses.
     */
    public static final String JAR_PROPERTY = "lg.wonderland.server.jar";

    /** The default Spring Boot port, when the address names none. */
    static final int DEFAULT_PORT = 8080;

    /** How long to wait, after spawning, for the server to answer its REST root. */
    static final Duration DEFAULT_START_TIMEOUT = Duration.ofSeconds(60);

    /** The gap between two readiness polls. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /** A launched (or to-be-launched) server process; the seam the tests fake. */
    interface Spawner {
        /**
         * Start {@code argv} and return a handle. Implementations may block
         * briefly to spawn but must not wait for readiness (that is
         * {@link Probe}'s job on a poll).
         */
        Launched spawn(List<String> argv) throws IOException;
    }

    /** A running child the launcher can tear down. */
    interface Launched {
        boolean alive();

        void stop();
    }

    /** Whether a server already answers at a URL; the seam the tests fake. */
    interface Probe {
        boolean ready(String server);
    }

    /** The outcome of one ensure attempt: what happened, and why in one line. */
    public record Result(boolean ready, boolean launched, String note) {
    }

    private final Path repoRoot;
    private final Spawner spawner;
    private final Probe probe;
    private final Duration timeout;

    private volatile Launched launched;

    /** The production launcher: real {@link ProcessBuilder} + real HTTP probe. */
    public WonderlandServerLauncher() {
        this(repoRoot(), new ProcessSpawner(), new HttpProbe(), DEFAULT_START_TIMEOUT);
    }

    WonderlandServerLauncher(Path repoRoot, Spawner spawner, Probe probe, Duration timeout) {
        this.repoRoot = repoRoot;
        this.spawner = spawner;
        this.probe = probe;
        this.timeout = timeout;
    }

    /**
     * Ensure a server answers at {@code server}, starting a local one if none is
     * and this machine can. Never throws; see the class boundary above.
     */
    public Result ensureRunning(String server) {
        if (server == null || server.isBlank()) {
            return new Result(false, false, "no server address given");
        }
        String base = WorldCatalog.normalizeServer(server);
        if (!isLocal(base)) {
            return new Result(false, false,
                    "a remote server (" + host(base) + ") is not started by the desktop");
        }
        if (probe.ready(base)) {
            return new Result(true, false, "a server already answers on " + base);
        }
        Path jar = resolveJar();
        if (jar == null) {
            return new Result(false, false,
                    "no wonderland-server jar found; build it or run: "
                            + WorldCatalog.startCommand());
        }
        try {
            Launched child = spawner.spawn(command(jar, portOf(base)));
            launched = child;
            boolean ready = awaitReady(base);
            return new Result(ready, true,
                    ready ? "started wonderland-server (pid " + jar.getFileName() + ")"
                            : "wonderland-server spawned but never answered within " + timeout);
        } catch (IOException spawnFailed) {
            return new Result(false, false,
                    "could not start wonderland-server: " + spawnFailed.getMessage());
        }
    }

    /** Stop the server this launcher started, if any. Idempotent; never throws. */
    public void shutdown() {
        Launched child = launched;
        if (child != null) {
            launched = null;
            try {
                child.stop();
            } catch (RuntimeException ignored) {
                // tearing down our own child must not surface to the closing panel
            }
        }
    }

    /** Whether this launcher currently owns a live child it started. */
    public boolean isManaging() {
        Launched child = launched;
        return child != null && child.alive();
    }

    /** Poll the probe until it answers or the timeout elapses. */
    private boolean awaitReady(String base) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (probe.ready(base)) {
                return true;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return probe.ready(base);
            }
        }
        return probe.ready(base);
    }

    // ------------------------------------------------------------------
    // Pure decisions (headless-tested)
    // ------------------------------------------------------------------

    /** The port to run the server on: the address's, else {@link #DEFAULT_PORT}. */
    static int portOf(String base) {
        int port = URI.create(base).getPort();
        return port > 0 ? port : DEFAULT_PORT;
    }

    /** The host of a normalised base URL, lower-cased ("" when it will not parse). */
    static String host(String base) {
        String host = URI.create(base).getHost();
        return host == null ? "" : host.toLowerCase();
    }

    /**
     * Whether {@code base} names this machine: only such an address may be
     * auto-started. A LAN or internet host is somebody else's server.
     */
    static boolean isLocal(String base) {
        String host = host(base);
        return host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("0.0.0.0") || host.equals("::1") || host.equals("[::1]");
    }

    /** The command line that runs the built server jar on {@code port}. */
    static List<String> command(Path jar, int port) {
        String javaBinary = Path.of(System.getProperty("java.home"),
                "bin", "java").toString();
        List<String> argv = new ArrayList<>();
        argv.add(javaBinary);
        argv.add("-jar");
        argv.add(jar.toString());
        argv.add("--server.port=" + port);
        return argv;
    }

    /**
     * Locate the built Spring Boot application jar, honouring
     * {@link #JAR_PROPERTY} first, then searching
     * {@code <repoRoot>/wonderland-server/build-gradle/libs} for the
     * {@code -app.jar}. Returns {@code null} when nothing matches &mdash; a
     * never-built tree, or a repository the launcher was not pointed at.
     */
    Path resolveJar() {
        String direct = System.getProperty(JAR_PROPERTY);
        if (direct != null && !direct.isBlank()) {
            Path named = Path.of(direct.trim());
            return Files.isReadable(named) ? named : null;
        }
        return findServerJar(repoRoot);
    }

    /** Search a repository root's {@code wonderland-server} build output for the app jar. */
    static Path findServerJar(Path repoRoot) {
        if (repoRoot == null) {
            return null;
        }
        Path libs = repoRoot.resolve("wonderland-server").resolve("build-gradle").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(libs)) {
            return jars.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("wonderland-server-") && n.endsWith("-app.jar");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The repository root: {@link #REPO_PROPERTY} when set, else the working directory. */
    private static Path repoRoot() {
        String configured = System.getProperty(REPO_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------
    // The live seams (compile-checked in CI, verified on the desktop)
    // ------------------------------------------------------------------

    /** The real launcher: {@link ProcessBuilder}, output discarded (readiness is polled over HTTP). */
    static final class ProcessSpawner implements Spawner {
        @Override
        public Launched spawn(List<String> argv) throws IOException {
            Process process = new ProcessBuilder(argv)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            ProcessHandle handle = process.toHandle();
            return new Launched() {
                @Override
                public boolean alive() {
                    return handle.isAlive();
                }

                @Override
                public void stop() {
                    handle.destroy();
                    process.destroyForcibly();
                }
            };
        }
    }

    /** The real readiness check: a short GET of the server's worlds catalog. */
    static final class HttpProbe implements Probe {
        private static final HttpClient HTTP = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        @Override
        public boolean ready(String server) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(WorldCatalog.worldsUrl(server)))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                return resp.statusCode() / 100 == 2;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException | RuntimeException notYet) {
                return false; // refused / no route / half-up: not ready yet
            }
        }
    }
}
