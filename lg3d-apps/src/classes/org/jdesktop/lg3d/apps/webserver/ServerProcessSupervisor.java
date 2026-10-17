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
package org.jdesktop.lg3d.apps.webserver;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Forks the {@code lg3d-webserver} Spring Boot {@code bootJar} as a child process
 * for the Web Server Console, and tears it down again.
 *
 * <p>This is the load-bearing half of the child-process design: the desktop never
 * puts Spring on its own classpath, so the only way to run a server locally is to
 * spawn {@code java -jar <bootJar> --server.port=<p>} beside the desktop and talk
 * to it over HTTP with {@link ManagementClient}. The console "Start local" button
 * calls {@link #start(int)}; "Stop" calls {@link #stop()}. This mirrors the Swing
 * IDE / OpenAPI editor / PayloadMan launchers, which fork their external jars the
 * same way, and the {@code WonderlandServerLauncher}, which forks the
 * {@code wonderland-server} bootJar.</p>
 *
 * <p>Two hard boundaries keep it safe, both inherited from those precedents:</p>
 * <ul>
 *   <li>It never throws. A missing jar or a spawn failure degrades to a
 *       not-started {@link Result} carrying the reason, so the panel falls back to
 *       a readable "bootJar not found / could not start" pane instead of taking
 *       down the window that hosts it.</li>
 *   <li>The decisions &mdash; where is the jar, what is the command line &mdash;
 *       are pure and run against an injected {@link Spawner}; the default
 *       {@link ProcessSpawner} that pays the real {@link ProcessBuilder} is
 *       compile-checked in CI and verified live on the desktop, never claimed
 *       from a headless run.</li>
 * </ul>
 */
public final class ServerProcessSupervisor {

    /**
     * System property naming the server bootJar to run directly, bypassing the
     * search &mdash; set by {@code :lg3d-core:run} and the release launcher, and
     * the escape hatch for a non-standard layout. Mirrors {@code swingide.jar}.
     */
    public static final String JAR_PROPERTY = "webserver.jar";

    /** System property holding the application codebase as an absolute file URL. */
    public static final String APPCODEBASE_PROPERTY = "lg.appcodebase";

    /** The Spring Boot port the server binds when none is requested. */
    public static final int DEFAULT_PORT = 8080;

    /** The built bootJar's name prefix; the {@code -app} classifier is the executable. */
    static final String JAR_PREFIX = "lg3d-webserver-";

    /** The built bootJar's name suffix (the executable {@code bootJar} classifier). */
    static final String JAR_SUFFIX = "-app.jar";

    /** How long {@link #stop()} waits for a graceful exit before forcing it. */
    private static final long STOP_TIMEOUT_SECONDS = 5L;

    private final List<Path> roots;
    private final Spawner spawner;

    private volatile Child child;
    private volatile int port = -1;

    /** A launched (or to-be-launched) server child; the seam the tests fake. */
    interface Spawner {
        /**
         * Start {@code argv} and return a handle. Implementations may block
         * briefly to spawn but must not wait for readiness (the panel polls
         * {@link ManagementClient#health()} for that).
         */
        Child spawn(List<String> argv) throws IOException;
    }

    /** A running child the supervisor can tear down. */
    interface Child {
        boolean alive();

        void stop();
    }

    /**
     * The outcome of one start attempt: whether a child is now running, why in
     * one line, and the address it serves on when it started.
     */
    public record Result(boolean started, String note, String url, int port) {
    }

    /** The production supervisor: real {@link ProcessBuilder}, real jar search. */
    public ServerProcessSupervisor() {
        this(productionRoots(), new ProcessSpawner());
    }

    ServerProcessSupervisor(List<Path> roots, Spawner spawner) {
        this.roots = (roots == null) ? List.of() : roots;
        this.spawner = (spawner == null) ? new ProcessSpawner() : spawner;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Forks the server on {@code requestedPort} (or {@link #DEFAULT_PORT} when it
     * is not positive). A child that is already running is left as it is. Never
     * throws; see the class boundary above.
     */
    public synchronized Result start(int requestedPort) {
        int target = (requestedPort > 0) ? requestedPort : DEFAULT_PORT;
        if (isRunning()) {
            return new Result(true, "a server child is already running on port " + port,
                    url(port), port);
        }
        Path jar = resolveJar();
        if (jar == null) {
            return new Result(false,
                    "the lg3d-webserver bootJar was not found; build it with "
                    + ":lg3d-webserver:bootJar or set -Dwebserver.jar=<path>",
                    null, target);
        }
        List<String> argv = command(jar, target);
        try {
            child = spawner.spawn(argv);
            port = target;
            return new Result(true, "started " + jar.getFileName() + " on port " + target,
                    url(target), target);
        } catch (IOException | RuntimeException e) {
            child = null;
            String reason = (e.getMessage() == null) ? e.toString() : e.getMessage();
            return new Result(false, "could not start the server: " + reason, null, target);
        }
    }

    /** Stops the forked child, if any. Idempotent; never throws. */
    public synchronized void stop() {
        Child current = child;
        child = null;
        port = -1;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
                // A child that will not stop is not the console's to escalate.
            }
        }
    }

    /** Whether a forked child is alive right now. */
    public boolean isRunning() {
        Child current = child;
        return current != null && current.alive();
    }

    /** The port the running child serves on, or {@code -1} when none is running. */
    public int port() {
        return port;
    }

    /** The base URL the running child serves on, or {@code null} when none is. */
    public String url() {
        return (port > 0) ? url(port) : null;
    }

    /** The resolved bootJar, or {@code null} when it cannot be found. */
    public Path jar() {
        return resolveJar();
    }

    private static String url(int port) {
        return "http://localhost:" + port;
    }

    // ------------------------------------------------------------------
    // Command + jar resolution (pure; headless-tested)
    // ------------------------------------------------------------------

    /**
     * The child-process command: this JVM's own {@code java} binary, {@code -jar},
     * the resolved bootJar's absolute path, and the Spring {@code --server.port}
     * argument. Mirrors {@code WonderlandServerLauncher.command}.
     */
    static List<String> command(Path jar, int port) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> argv = new ArrayList<>(4);
        argv.add(javaBin);
        argv.add("-jar");
        argv.add(jar.toAbsolutePath().toString());
        argv.add("--server.port=" + port);
        return argv;
    }

    /** The {@code java} binary this JVM would fork. */
    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Locate the built {@code lg3d-webserver} bootJar, in precedence order: the
     * {@link #JAR_PROPERTY} system property (absolute), then
     * {@code <lg.appcodebase>/libs/lg3d-webserver-*-app.jar} (the release
     * bundle's flat layout), then each search root's repository layout
     * ({@code <root>/lg3d-webserver/build-gradle/libs/*-app.jar}). Returns
     * {@code null} when nothing matches &mdash; a never-built tree.
     */
    Path resolveJar() {
        // 1. An explicit -Dwebserver.jar wins outright (the launcher escape hatch).
        String direct = System.getProperty(JAR_PROPERTY);
        if (direct != null && !direct.isBlank()) {
            Path named = Path.of(direct.trim());
            return Files.isReadable(named) ? named : null;
        }
        // 2. The release bundle keeps the bootJar beside the desktop's own jars,
        //    under <lg.appcodebase>/libs/.
        Path codebase = appCodeBaseDir();
        if (codebase != null) {
            Path bundled = findAppJarBeside(codebase.resolve("libs"));
            if (bundled != null) {
                return bundled;
            }
        }
        // 3. A source tree keeps it under <root>/lg3d-webserver/build-gradle/libs/.
        for (Path root : roots) {
            Path jar = findServerJar(root);
            if (jar != null) {
                return jar;
            }
        }
        return null;
    }

    /**
     * A {@code lg3d-webserver-*-app.jar} lying directly in {@code dir}: the
     * release bundle's flat layout, where the server ships beside the desktop's
     * own jars (in {@code libs/}) rather than under a module build directory.
     */
    static Path findAppJarBeside(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(dir)) {
            return jars.filter(Files::isRegularFile)
                    .filter(ServerProcessSupervisor::isServerAppJar)
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Search a repository root's {@code lg3d-webserver} build output for the app jar. */
    static Path findServerJar(Path repoRoot) {
        if (repoRoot == null) {
            return null;
        }
        Path libs = repoRoot.resolve("lg3d-webserver").resolve("build-gradle").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(libs)) {
            return jars.filter(Files::isRegularFile)
                    .filter(ServerProcessSupervisor::isServerAppJar)
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Whether {@code path} is the executable {@code lg3d-webserver-*-app.jar}. */
    static boolean isServerAppJar(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(JAR_PREFIX) && name.endsWith(JAR_SUFFIX);
    }

    /**
     * {@code start} and its parents up to the filesystem root (capped, so a deep
     * working directory cannot search the whole machine). Mirrors
     * {@code WonderlandServerLauncher.ancestorsOf}.
     */
    static List<Path> ancestorsOf(Path start) {
        List<Path> out = new ArrayList<>();
        Path p = (start == null) ? null : start.toAbsolutePath().normalize();
        for (int hops = 0; p != null && hops < 6; hops++, p = p.getParent()) {
            out.add(p);
        }
        return out;
    }

    /**
     * The production search: the working directory and its ancestors (a
     * {@code :lg3d-core:run} desktop starts inside the module, one level below the
     * repository root), and this class's own code location and its ancestors (the
     * release bundle runs from an installed tree whose layout no working directory
     * names).
     */
    static List<Path> productionRoots() {
        List<Path> out = new ArrayList<>();
        for (Path p : ancestorsOf(Path.of(System.getProperty("user.dir")))) {
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        Path code = codeSourceDir();
        if (code != null) {
            for (Path p : ancestorsOf(code)) {
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** The directory this class was loaded from: its jar's home or its classes root. */
    static Path codeSourceDir() {
        try {
            Path here = Path.of(ServerProcessSupervisor.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(here) ? here : here.getParent();
        } catch (URISyntaxException | RuntimeException unavailable) {
            return null;                        // e.g. an in-memory loader
        }
    }

    /**
     * Reads {@code lg.appcodebase} (an absolute {@code file:} URL, or a plain
     * path) as a directory. Mirrors {@code SwingIde.appCodeBaseDir}.
     */
    static Path appCodeBaseDir() {
        String value = System.getProperty(APPCODEBASE_PROPERTY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = value.startsWith("file:")
                    ? Path.of(java.net.URI.create(value))
                    : Path.of(value);
            return Files.isDirectory(path) ? path : null;
        } catch (RuntimeException malformed) {
            // A malformed URL, an unsupported scheme or a missing filesystem:
            // the codebase simply names no directory the jar can be found in.
            return null;
        }
    }

    // ------------------------------------------------------------------
    // The live seam (compile-checked in CI, verified on the desktop)
    // ------------------------------------------------------------------

    /** The real spawner: {@link ProcessBuilder}, merged output discarded. */
    static final class ProcessSpawner implements Spawner {
        @Override
        public Child spawn(List<String> argv) throws IOException {
            Process process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new ProcessChild(process);
        }
    }

    /** A real forked child: graceful destroy, forced after a short grace period. */
    static final class ProcessChild implements Child {
        private final Process process;

        ProcessChild(Process process) {
            this.process = process;
        }

        @Override
        public boolean alive() {
            return process.isAlive();
        }

        @Override
        public void stop() {
            process.destroy();
            try {
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
