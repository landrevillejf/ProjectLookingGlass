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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Creates a new project on disk the way Espresso wants it: sources and build
 * descriptor scaffolded from {@link ProjectTemplates}, the chosen build tool
 * downloaded and extracted into the project's own {@code .espresso/tools/} (so
 * this project - and only this project - uses that exact Gradle or Maven), and
 * the selected JDK recorded by id in {@code .espresso/toolchain.properties}.
 *
 * <p>No JDK is ever copied into the project: JDKs live in the shared manager
 * ({@link JdkRegistry}) and are only referenced here by id, so two projects can
 * select different JDKs without either carrying a private copy. The heavy work
 * (download + extract) runs off the EDT on a virtual thread in production; the
 * {@link Downloader} is a seam so tests provision a whole project headlessly
 * from canned archive bytes, with no network.</p>
 */
final class ProjectProvisioner {

    private final Downloader downloader;

    /** Production provisioner: real HTTP downloads. */
    ProjectProvisioner() {
        this(new HttpDownloader());
    }

    /** Injectable provisioner (tests pass a fake {@link Downloader}). */
    ProjectProvisioner(Downloader downloader) {
        this.downloader = downloader;
    }

    /**
     * Provisions {@code root} as a new project described by {@code req}. The
     * selected JDK is validated against the manager <em>before</em> anything is
     * written, so a bad selection leaves the disk untouched rather than a
     * half-created project.
     *
     * @return the entry-point source file, for the host to open
     * @throws IOException when the directory is not empty, the selected JDK is
     *                     missing from the manager, or a download/extract fails
     */
    Path provision(Path root, NewProjectRequest req, JdkRegistry registry, ProgressListener pl)
            throws IOException {
        int release = computeRelease(req.jdkId(), registry);
        requireEmpty(root);

        BuildSystem buildSystem = req.buildSystem();
        pl.onProgress("Creating project " + req.name() + "\u2026");
        Path entry = ProjectTemplates.writeMainSource(root, buildSystem, req.name());
        ProjectTemplates.writeReadme(root, req.name(), buildSystem);
        ProjectTemplates.writeGitignore(root);
        switch (buildSystem) {
            case GRADLE -> ProjectTemplates.writeGradle(root, req.name(), release);
            case MAVEN -> ProjectTemplates.writeMavenPom(root, req.name(), release);
            case NONE -> {
                // A plain Java project has no build descriptor.
            }
        }

        String toolHomeRel = "";
        if (buildSystem.needsTool()) {
            toolHomeRel = provisionTool(root, buildSystem, req.toolVersion(), pl);
        }
        writeToolchain(root, buildSystem, req.toolVersion(), toolHomeRel, req.jdkId());
        pl.onProgress("Project ready");
        return entry;
    }

    // -- internals ---------------------------------------------------------

    /** Refuses an existing non-empty directory; creates {@code root} if absent. */
    private static void requireEmpty(Path root) throws IOException {
        if (Files.exists(root)) {
            if (!Files.isDirectory(root)) {
                throw new IOException("Not a directory: " + root);
            }
            try (Stream<Path> kids = Files.list(root)) {
                if (kids.findAny().isPresent()) {
                    throw new IOException("Directory is not empty: " + root);
                }
            }
        } else {
            Files.createDirectories(root);
        }
    }

    /**
     * Downloads and extracts the build tool into {@code <root>/.espresso/tools/},
     * marking its launcher executable, and returns the tool home relative to the
     * project root.
     */
    private String provisionTool(Path root, BuildSystem buildSystem, String version,
                                 ProgressListener pl) throws IOException {
        if (version == null || version.isBlank()) {
            throw new IOException(buildSystem.displayName() + " needs a version to download");
        }
        String url = buildSystem.downloadUrl(version);
        String archiveName = url.substring(url.lastIndexOf('/') + 1);
        Path tools = root.resolve(ProjectToolchain.TOOLCHAIN_DIR)
                .resolve(ProjectToolchain.TOOLS_DIR);
        Files.createDirectories(tools);
        Path staging = Files.createTempDirectory(tools, ".staging-");
        try {
            Path archive = staging.resolve(archiveName);
            pl.onProgress("Downloading " + buildSystem.displayName() + " " + version + "\u2026");
            downloader.fetch(url, archive);
            pl.onProgress("Extracting " + buildSystem.displayName() + " " + version + "\u2026");
            Archives.extract(archive, tools);
            Path launcher = tools.resolve(buildSystem.toolDirName(version))
                    .resolve(buildSystem.launcherRelPath());
            Archives.makeExecutable(launcher);
            return ProjectToolchain.TOOLCHAIN_DIR + "/" + ProjectToolchain.TOOLS_DIR + "/"
                    + buildSystem.toolDirName(version);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * The compiler release to bake into the build descriptor: the major version
     * of the selected JDK, else the feature version the editor itself runs on.
     */
    private static int computeRelease(String jdkId, JdkRegistry registry) throws IOException {
        if (jdkId != null && !jdkId.isBlank()) {
            Optional<Path> home = (registry == null) ? Optional.empty() : registry.resolve(jdkId);
            if (home.isEmpty()) {
                throw new IOException("Selected JDK is not in the manager: " + jdkId
                        + " (download or register it under Manage JDKs first)");
            }
            String major = JdkRegistry.majorOf(JdkRegistry.readVersion(home.get()));
            try {
                return Integer.parseInt(major);
            } catch (NumberFormatException ignored) {
                // An unreadable version falls back to the editor's own feature.
            }
        }
        return Runtime.version().feature();
    }

    private static void writeToolchain(Path root, BuildSystem buildSystem, String version,
                                       String toolHomeRel, String jdkId) throws IOException {
        Path dir = root.resolve(ProjectToolchain.TOOLCHAIN_DIR);
        Files.createDirectories(dir);
        Properties props = new Properties();
        props.setProperty(ProjectToolchain.KEY_BUILD_SYSTEM, buildSystem.name());
        props.setProperty(ProjectToolchain.KEY_TOOL_VERSION, version == null ? "" : version.trim());
        props.setProperty(ProjectToolchain.KEY_TOOL_HOME, toolHomeRel == null ? "" : toolHomeRel);
        props.setProperty(ProjectToolchain.KEY_JDK_ID, jdkId == null ? "" : jdkId.trim());
        try (Writer w = Files.newBufferedWriter(
                dir.resolve(ProjectToolchain.TOOLCHAIN_FILE), StandardCharsets.UTF_8)) {
            props.store(w, "Espresso project toolchain");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
