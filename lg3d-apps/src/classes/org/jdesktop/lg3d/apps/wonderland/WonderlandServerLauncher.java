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
import java.net.URISyntaxException;
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
     * System property naming a repository root to search <em>first</em> for
     * the built server jar. Unset, the search starts at the JVM's working
     * directory and walks up its ancestors, then this class's own code
     * location and its ancestors &mdash; which is what lets a desktop started
     * inside {@code lg3d-core/} by {@code :lg3d-core:run}, or running from
     * an installed release bundle, still find the server.
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

    /**
     * The world a spawned server seeds when the panel names none: the bundled
     * outpatient clinic, shipped on the server's own classpath and therefore
     * seedable even with no worlds directory. Mirrors the server's
     * {@code WorldSeeder.CLINIC_MODULE} wire value &mdash; the desktop must not
     * import server classes, so the string is stated twice, and the launcher
     * test pins the seed flag's exact spelling.
     */
    static final String DEFAULT_SEED = "wonderland-outpatient-clinic";

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

        /**
         * Whether the server at {@code server} is <em>hosting</em> {@code module}
         * right now &mdash; the seeded live world, not merely a listed one. The
         * default says no, so a fake that only answers {@link #ready} keeps the
         * older "already answers is enough" behavior; {@link HttpProbe} asks the
         * worlds catalog, which carries the {@code hosted} flag.
         */
        default boolean hosting(String server, String module) {
            return false;
        }
    }

    /**
     * The outcome of one ensure attempt: what happened, why in one line, and
     * the address that actually serves the world when it is not the one asked
     * for ({@code url} is {@code null} for "the address you named"). A
     * relocated spawn carries its new base so the panel can follow it.
     */
    public record Result(boolean ready, boolean launched, String note, String url) {

        /** An outcome at the requested address (no relocation happened). */
        public Result(boolean ready, boolean launched, String note) {
            this(ready, launched, note, null);
        }

        /**
         * The address that actually serves the world: {@link #url} when the
         * spawn had to relocate beside a foreign server, else {@code asked}.
         * One rule for every surface that follows a relocation &mdash; the
         * 2D panel's address field and the 3D walk-in's session alike &mdash;
         * so neither can keep pointing at the answering-but-empty server that
         * forced the move. Pure, so the follow-the-world rule is one tested
         * claim instead of two hand-copied ternaries that can drift.
         */
        public String serverUrl(String asked) {
            return (url == null || url.isBlank()) ? asked : url;
        }
    }

    /**
     * The directories the jar search walks, in priority order: the launch
     * root and its ancestors (a desktop started from a module directory
     * still finds the build output one level up), and, in production, this
     * class's own code location and <em>its</em> ancestors (the release
     * bundle keeps the server jar beside the desktop's own jars).
     */
    private final List<Path> roots;
    private final Spawner spawner;
    private final Probe probe;
    private final Duration timeout;

    private volatile Launched launched;

    /** The production launcher: real {@link ProcessBuilder} + real HTTP probe. */
    public WonderlandServerLauncher() {
        this(productionRoots(), new ProcessSpawner(), new HttpProbe(), DEFAULT_START_TIMEOUT);
    }

    WonderlandServerLauncher(Path repoRoot, Spawner spawner, Probe probe, Duration timeout) {
        this(ancestorsOf(repoRoot), spawner, probe, timeout);
    }

    private WonderlandServerLauncher(List<Path> roots, Spawner spawner, Probe probe,
            Duration timeout) {
        this.roots = roots;
        this.spawner = spawner;
        this.probe = probe;
        this.timeout = timeout;
    }

    /**
     * Ensure a server answers at {@code server}, starting a local one if none is
     * and this machine can. A server that already answers is left exactly as its
     * owner started it &mdash; this is the polite form the auto-start-on-failed-
     * read uses; the panel's explicit Start press uses
     * {@link #ensureRunning(String, String)}, which guarantees a <em>world</em>,
     * not just a process. A spawned server seeds {@link #DEFAULT_SEED}, so a
     * freshly started world hosts content instead of an empty Environment root.
     * Never throws; see the class boundary above.
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
        return spawnAt(base, DEFAULT_SEED);
    }

    /**
     * As {@link #ensureRunning(String)}, but guarantees the world the panel
     * selected: the spawned server seeds it ({@code seedModule} null or blank
     * falls back to {@link #DEFAULT_SEED}), and &mdash; if some <em>other</em>
     * server already answers on the requested address without hosting it
     * &mdash; this launcher starts its own on the next free local port and
     * names that address in {@link Result#url()}. That is the difference
     * between "a server exists" and "the world I chose is running": pressing
     * Start on a machine whose port 8080 was squatted by an older, unseeded
     * server used to resolve into a polite no-op; now the chosen world comes
     * up beside it. A server that already hosts the selected world is left
     * exactly as it is &mdash; re-seeding a live world would mean killing
     * somebody else's process.
     */
    public Result ensureRunning(String server, String seedModule) {
        if (server == null || server.isBlank()) {
            return new Result(false, false, "no server address given");
        }
        String base = WorldCatalog.normalizeServer(server);
        if (!isLocal(base)) {
            return new Result(false, false,
                    "a remote server (" + host(base) + ") is not started by the desktop");
        }
        String module = seedFor(seedModule);
        if (probe.ready(base)) {
            if (probe.hosting(base, module)) {
                return new Result(true, false,
                        "world '" + module + "' is already live on " + base);
            }
            return spawnBeside(base, module);
        }
        return spawnAt(base, module);
    }

    /** Spawn ours on the requested address's own port, seeded with {@code module}. */
    private Result spawnAt(String base, String module) {
        Path jar = resolveJar();
        if (jar == null) {
            return new Result(false, false,
                    "no wonderland-server jar found (looked under "
                            + (roots.isEmpty() ? "nowhere" : roots.get(0)) + "); "
                            + "build it or run: " + WorldCatalog.startCommand());
        }
        try {
            Launched child = spawner.spawn(command(jar, portOf(base),
                    worldsDirFor(jar), module));
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

    /**
     * The requested port carries somebody else's server that does not host
     * {@code module}: start ours on the next local port nothing answers on,
     * seeded with that world. Honest by construction &mdash; no foreign
     * process is touched, and the note names both addresses so the status line
     * explains why the field just moved.
     */
    private Result spawnBeside(String base, String module) {
        Path jar = resolveJar();
        if (jar == null) {
            return new Result(false, false,
                    "no wonderland-server jar found (looked under "
                            + (roots.isEmpty() ? "nowhere" : roots.get(0)) + "); "
                            + "build it or run: " + WorldCatalog.startCommand());
        }
        int port = portOf(base);
        for (int candidate = port + 1; candidate <= port + MAX_PORT_SCAN; candidate++) {
            String url = withPort(base, candidate);
            if (probe.ready(url)) {
                continue; // something already answers there; not ours to move onto
            }
            try {
                Launched child = spawner.spawn(command(jar, candidate,
                        worldsDirFor(jar), module));
                launched = child;
                boolean ready = awaitReady(url);
                return new Result(ready, true,
                        ready ? base + " does not host '" + module + "'; "
                                + "started ours on " + url
                                : "wonderland-server spawned on " + url
                                        + " but never answered within " + timeout,
                        ready ? url : null);
            } catch (IOException spawnFailed) {
                return new Result(false, false,
                        "could not start wonderland-server: " + spawnFailed.getMessage());
            }
        }
        return new Result(false, false,
                "no free port beside " + base + " to host '" + module + "'");
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

    /** How many ports beside the requested one {@link #spawnBeside} will scan. */
    static final int MAX_PORT_SCAN = 10;

    /**
     * The same URL with a different port: {@code http://localhost:8080} and a
     * candidate {@code 8081} give {@code http://localhost:8081}, a bare
     * {@code http://localhost} gains its port, and any path after the authority
     * is carried over untouched. An IPv6 host keeps its brackets. Pure, so the
     * relocation's address arithmetic is a tested claim.
     */
    static String withPort(String base, int port) {
        int scheme = base.indexOf("://");
        if (scheme < 0) {
            throw new IllegalArgumentException("not a URL: " + base);
        }
        int authorityStart = scheme + 3;
        int path = base.indexOf('/', authorityStart);
        String authority = (path < 0) ? base.substring(authorityStart)
                : base.substring(authorityStart, path);
        String host;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            host = authority.substring(0, close + 1);
        } else {
            int colon = authority.lastIndexOf(':');
            host = (colon < 0) ? authority : authority.substring(0, colon);
        }
        return base.substring(0, authorityStart) + host + ":" + port
                + ((path < 0) ? "" : base.substring(path));
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
        return command(jar, port, null, DEFAULT_SEED);
    }

    /**
     * The command line that runs the built server jar on {@code port}, and on
     * the installed-worlds directory when the layout carries one: without
     * {@code --wonderland.world.dir} the server only ever sees the bundled
     * classpath worlds, which is why the catalog listed just the clinic.
     */
    static List<String> command(Path jar, int port, Path worldsDir) {
        return command(jar, port, worldsDir, DEFAULT_SEED);
    }

    /**
     * The full spawn command. Three flags, each the answer to one empty
     * surface: {@code --server.port} says where the server listens,
     * {@code --wonderland.world.dir} says which installed worlds to list, and
     * {@code --wonderland.world.seed} says which world to actually host &mdash;
     * without it the server boots opt-in-empty (the seed's default is
     * {@code none}) and a walking-in client sees nothing.
     *
     * @param seed the module to seed, or null/blank to leave the server's own
     *             default (an empty world) in place
     */
    static List<String> command(Path jar, int port, Path worldsDir, String seed) {
        String javaBinary = Path.of(System.getProperty("java.home"),
                "bin", "java").toString();
        List<String> argv = new ArrayList<>();
        argv.add(javaBinary);
        argv.add("-jar");
        argv.add(jar.toString());
        argv.add("--server.port=" + port);
        if (worldsDir != null) {
            argv.add("--wonderland.world.dir=" + worldsDir.toAbsolutePath());
        }
        if (seed != null && !seed.isBlank()) {
            argv.add("--wonderland.world.seed=" + seed.trim());
        }
        return argv;
    }

    /** The seed one spawn carries: the panel's selection, else the default. */
    static String seedFor(String seedModule) {
        return (seedModule == null || seedModule.isBlank())
                ? DEFAULT_SEED : seedModule.trim();
    }

    /**
     * Whether a server's catalog says it is <em>really</em> serving {@code module}:
     * an entry marks it {@code hosted}, names it, AND carries a non-empty cell
     * count. A hosted-but-empty world is a process built from an earlier jar
     * squatting the port &mdash; the desktop spawns only when nothing answers, so
     * a rebuild never dislodges it &mdash; and its zero cells are exactly the
     * "the world has no cells" the browser then shows. Reading "hosted" without
     * reading the count would trust that zombie; requiring cells is the difference
     * between a server that claims a world and one that serves it. Pure, so the
     * whole rule is a tested claim rather than a property of the live HTTP probe.
     */
    static boolean servesWorld(List<WorldCatalog.WorldEntry> worlds, String module) {
        if (worlds == null || module == null) {
            return false;
        }
        for (WorldCatalog.WorldEntry entry : worlds) {
            if (entry.hosted() && module.equals(entry.module())
                    && entry.cellCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The extracted world jars beside a built server jar: {@code extractWorlds}
     * unpacks the stock {@code *-world.jar} modules into
     * {@code <repo>/wonderland-server/wonderland-worlds}, three directories up
     * from the jar's own {@code build-gradle/libs}. Returns {@code null} when
     * the jar sits in no such layout (a flat release bundle) or nothing was
     * extracted &mdash; then the server simply serves its bundled worlds.
     */
    static Path worldsDirFor(Path appJar) {
        Path moduleDir = appJar.toAbsolutePath().normalize().resolve("../../..");
        Path worlds = moduleDir.resolve("wonderland-worlds").normalize();
        return Files.isDirectory(worlds) ? worlds : null;
    }

    /**
     * Locate the built Spring Boot application jar, honouring
     * {@link #JAR_PROPERTY} first, then walking {@link #roots}: each is tried
     * as a repository layout ({@code <root>/wonderland-server/build-gradle/
     * libs/*-app.jar}) and as a bundle layout (the app jar sitting directly
     * in the directory). Returns {@code null} when nothing matches &mdash; a
     * never-built tree, or a repository the launcher was not pointed at.
     */
    Path resolveJar() {
        String direct = System.getProperty(JAR_PROPERTY);
        if (direct != null && !direct.isBlank()) {
            Path named = Path.of(direct.trim());
            return Files.isReadable(named) ? named : null;
        }
        for (Path root : roots) {
            Path jar = findServerJar(root);
            if (jar == null) {
                jar = findAppJarBeside(root);
            }
            if (jar != null) {
                return jar;
            }
        }
        return null;
    }

    /**
     * A {@code wonderland-server-*-app.jar} lying directly in {@code dir}:
     * the release bundle's flat layout, where the server ships beside the
     * desktop's own jars rather than under a module build directory.
     */
    static Path findAppJarBeside(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(dir)) {
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

    /**
     * {@code start} and its parents up to the filesystem root (capped, so a
     * deep working directory cannot search the whole machine). Empty for a
     * null start &mdash; the tests that pin "no jar under this root" stay
     * honest because no ancestor of a temp or absent directory carries a
     * {@code wonderland-server} build.
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
     * The production search: {@link #REPO_PROPERTY} when set, the working
     * directory and its ancestors (a {@code :lg3d-core:run} desktop starts
     * inside the module, one level below the repository root), and this
     * class's own code location and its ancestors (the release bundle runs
     * from an installed tree whose layout no working directory names).
     */
    static List<Path> productionRoots() {
        List<Path> out = new ArrayList<>();
        String configured = System.getProperty(REPO_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            out.add(Path.of(configured.trim()).toAbsolutePath().normalize());
        }
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
            Path here = Path.of(WonderlandServerLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(here) ? here : here.getParent();
        } catch (URISyntaxException | RuntimeException unavailable) {
            return null;                        // e.g. an in-memory loader
        }
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

        /**
         * Ask the same catalog the browser reads: one of its entries is
         * {@code hosted}, names {@code module} AND actually carries cells. A
         * server too old to carry the flag answers "not hosting", which is the
         * safe reading &mdash; a stale server is exactly the one that must not be
         * trusted to be running the chosen world. So is a server that marks the
         * world hosted yet lists zero of them: that is a process built from an
         * earlier jar squatting the port (the desktop only spawns when none
         * answers, so a rebuild does not dislodge it), and its empty world is
         * what the browser sees as "no cells". Requiring a non-empty cell count
         * is the whole difference between "a server claims this world" and "this
         * world is really being served", and it makes the desktop spawn a fresh,
         * correctly-seeded server beside the zombie instead of trusting it.
         */
        @Override
        public boolean hosting(String server, String module) {
            try {
                return servesWorld(
                        WorldCatalog.loadWorlds(server, WorldCatalog.HTTP_FETCHER), module);
            } catch (IOException | RuntimeException unreachable) {
                return false;
            }
        }
    }
}
