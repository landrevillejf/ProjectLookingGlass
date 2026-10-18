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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The central multi-JDK manager. Espresso keeps every JDK it knows about in one
 * shared place rather than per project: JDKs Espresso downloads land in
 * {@code <base>/jdks/<id>}, and JDKs the user already has are <em>registered</em>
 * by folder (persisted in {@code <base>/jdks.properties}). A project then only
 * records the selected {@link JdkEntry#id()}, so two projects can select
 * different JDKs without either one carrying a private copy.
 *
 * <p>The base directory defaults to {@code ~/.espresso} (never the process
 * working directory, which inside the desktop is the lg3d-core source tree) and
 * is injectable so tests run against a {@code @TempDir} with a fake
 * {@link Downloader} - no test touches the real home or the network.</p>
 */
final class JdkRegistry {

    /** The default shared base directory, under the user's home. */
    static final String DEFAULT_BASE = ".espresso";

    private static final String JDKS_DIR = "jdks";
    private static final String PROPS_FILE = "jdks.properties";
    private static final String REGISTERED_PREFIX = "registered.";

    private final Path base;
    private final Downloader downloader;

    /** Production registry: {@code ~/.espresso} with the real HTTP downloader. */
    JdkRegistry() {
        this(Path.of(System.getProperty("user.home", "."), DEFAULT_BASE), new HttpDownloader());
    }

    /** Injectable registry (tests point {@code base} at a temp dir). */
    JdkRegistry(Path base, Downloader downloader) {
        this.base = base;
        this.downloader = downloader;
    }

    /** The shared store directory downloaded JDKs live in. */
    Path storeDir() {
        return base.resolve(JDKS_DIR);
    }

    /**
     * Every JDK known to the manager: the managed ones (folders in the store)
     * followed by the registered ones, each that still exists on disk, sorted by
     * version descending so the newest reads first.
     */
    List<JdkEntry> list() {
        Map<String, JdkEntry> byId = new LinkedHashMap<>();
        Path store = storeDir();
        if (Files.isDirectory(store)) {
            try (Stream<Path> dirs = Files.list(store)) {
                dirs.filter(Files::isDirectory)
                        .filter(JdkRegistry::isJdkHome)
                        .forEach(home -> {
                            String id = home.getFileName().toString();
                            byId.put(id, new JdkEntry(id, readVersion(home), home, true));
                        });
            } catch (IOException ignored) {
                // An unreadable store simply lists nothing managed.
            }
        }
        for (String path : registeredPaths()) {
            Path home = Path.of(path);
            if (isJdkHome(home)) {
                String id = home.getFileName().toString();
                byId.putIfAbsent(id, new JdkEntry(id, readVersion(home), home, false));
            }
        }
        List<JdkEntry> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(JdkRegistry::sortKey).reversed()
                .thenComparing(JdkEntry::id));
        return out;
    }

    /**
     * Registers an existing JDK folder the user already has. The folder must be a
     * JDK home (hold {@code bin/java}); it is persisted so it survives a restart.
     *
     * @return the registered entry
     * @throws IOException when {@code home} is not a usable JDK or cannot be saved
     */
    JdkEntry register(Path home) throws IOException {
        Path abs = home.toAbsolutePath().normalize();
        if (!isJdkHome(abs)) {
            throw new IOException("Not a JDK home (no bin/java): " + abs);
        }
        List<String> paths = registeredPaths();
        String key = abs.toString();
        if (!paths.contains(key)) {
            paths.add(key);
            writeRegistered(paths);
        }
        String id = abs.getFileName().toString();
        return new JdkEntry(id, readVersion(abs), abs, false);
    }

    /**
     * Forgets a JDK: a managed one is deleted from the store, a registered one is
     * only dropped from the list (the user's own folder is never touched).
     */
    void remove(String id) throws IOException {
        Optional<JdkEntry> entry = list().stream()
                .filter(e -> e.id().equals(id)).findFirst();
        if (entry.isEmpty()) {
            return;
        }
        JdkEntry jdk = entry.get();
        if (jdk.managed()) {
            deleteRecursively(jdk.home());
        }
        List<String> paths = registeredPaths();
        if (paths.remove(jdk.home().toAbsolutePath().normalize().toString())) {
            writeRegistered(paths);
        }
    }

    /**
     * Downloads a JDK into the shared store (never into a project) and registers
     * it as managed. The archive is fetched to a staging folder, extracted, and
     * its single top-level JDK home moved to {@code <store>/<id>}.
     *
     * @param feature  the feature version to fetch ("21", "25")
     * @param listener progress milestones
     * @return the newly managed entry
     * @throws IOException when the download or extraction fails
     */
    JdkEntry download(String feature, ProgressListener listener) throws IOException {
        String url = jdkUrl(feature);
        boolean zip = "windows".equals(osName());
        String archiveName = "jdk-" + feature + (zip ? ".zip" : ".tar.gz");
        Path store = storeDir();
        Files.createDirectories(store);
        Path staging = Files.createTempDirectory(store, ".staging-");
        Path archive = staging.resolve(archiveName);
        try {
            listener.onProgress("Downloading JDK " + feature + "\u2026");
            downloader.fetch(url, archive);
            listener.onProgress("Extracting JDK " + feature + "\u2026");
            Path extractDir = staging.resolve("x");
            Archives.extract(archive, extractDir);
            Path home = singleJdkHome(extractDir);
            String id = home.getFileName().toString();
            Path target = store.resolve(id);
            deleteRecursively(target);
            Files.move(home, target);
            Archives.makeExecutable(target.resolve("bin").resolve("java"));
            return new JdkEntry(id, readVersion(target), target, true);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Resolves an id or a version to a JDK home. Matches the id exactly first,
     * then an entry whose version equals or starts with the token, so a project
     * recording either {@code jdk-21.0.4+7} or {@code 21} still finds its JDK.
     */
    Optional<Path> resolve(String idOrVersion) {
        if (idOrVersion == null || idOrVersion.isBlank()) {
            return Optional.empty();
        }
        List<JdkEntry> all = list();
        for (JdkEntry e : all) {
            if (e.id().equals(idOrVersion)) {
                return Optional.of(e.home());
            }
        }
        for (JdkEntry e : all) {
            if (e.version().equals(idOrVersion) || e.version().startsWith(idOrVersion + ".")
                    || e.version().startsWith(idOrVersion + "+")
                    || majorOf(e.version()).equals(idOrVersion)) {
                return Optional.of(e.home());
            }
        }
        return Optional.empty();
    }

    /** The Adoptium binary-redirect URL for a feature version on this platform. */
    String jdkUrl(String feature) {
        return "https://api.adoptium.net/v3/binary/latest/" + feature
                + "/ga/" + osName() + "/" + archName() + "/jdk/hotspot/normal/eclipse";
    }

    /** The Adoptium OS token for the running platform. */
    static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return "linux";
    }

    /** The Adoptium architecture token for the running platform. */
    static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        return "x64";
    }

    // -- internals ---------------------------------------------------------

    /** True when {@code dir} looks like a JDK home (it has a {@code bin/java}). */
    static boolean isJdkHome(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        Path java = dir.resolve("bin").resolve(
                "windows".equals(osName()) ? "java.exe" : "java");
        return Files.isRegularFile(java);
    }

    /** The extracted archive's one top-level JDK home. */
    private static Path singleJdkHome(Path extractDir) throws IOException {
        try (Stream<Path> kids = Files.list(extractDir)) {
            List<Path> dirs = kids.filter(Files::isDirectory).toList();
            if (dirs.size() == 1 && isJdkHome(dirs.get(0))) {
                return dirs.get(0);
            }
            if (isJdkHome(extractDir)) {
                return extractDir;
            }
            for (Path d : dirs) {
                if (isJdkHome(d)) {
                    return d;
                }
            }
        }
        throw new IOException("The downloaded archive held no JDK home");
    }

    /** Reads {@code JAVA_VERSION} from a JDK's {@code release} file, else "". */
    static String readVersion(Path home) {
        Path release = home.resolve("release");
        if (!Files.isRegularFile(release)) {
            return "";
        }
        try {
            for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.startsWith("JAVA_VERSION=")) {
                    return s.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                }
            }
        } catch (IOException ignored) {
            // No readable release file: the version stays unknown.
        }
        return "";
    }

    /** The major feature number of a version string ("21.0.4+7" -> "21"). */
    static String majorOf(String version) {
        if (version == null || version.isEmpty()) {
            return "";
        }
        int cut = version.length();
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '.' || c == '+' || c == '-') {
                cut = i;
                break;
            }
        }
        return version.substring(0, cut);
    }

    /** A coarse numeric key so {@link #list()} sorts newest-version first. */
    private static String sortKey(JdkEntry e) {
        String v = e.version().isEmpty() ? e.id() : e.version();
        StringBuilder digits = new StringBuilder();
        for (char c : v.toCharArray()) {
            digits.append(Character.isDigit(c) ? c : '.');
        }
        return digits.toString();
    }

    private List<String> registeredPaths() {
        List<String> out = new ArrayList<>();
        Path props = base.resolve(PROPS_FILE);
        if (!Files.isRegularFile(props)) {
            return out;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(props, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException ignored) {
            return out;
        }
        for (int i = 0; ; i++) {
            String v = p.getProperty(REGISTERED_PREFIX + i);
            if (v == null) {
                break;
            }
            out.add(v);
        }
        return out;
    }

    private void writeRegistered(List<String> paths) throws IOException {
        Files.createDirectories(base);
        Properties p = new Properties();
        for (int i = 0; i < paths.size(); i++) {
            p.setProperty(REGISTERED_PREFIX + i, paths.get(i));
        }
        try (Writer w = Files.newBufferedWriter(base.resolve(PROPS_FILE),
                StandardCharsets.UTF_8)) {
            p.store(w, "Espresso registered JDK folders");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : all) {
                Files.deleteIfExists(p);
            }
        }
    }
}
