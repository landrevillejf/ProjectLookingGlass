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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless provisioning tests. A fake {@link Downloader} serves canned tool
 * archives (no network) and the JDK manager is a {@code @TempDir} store holding
 * one JDK. The assertions pin the design contract: the build tool is downloaded
 * into the project's own {@code .espresso/tools/}, the selected JDK is recorded
 * by id and resolved from the shared store, and no JDK is ever copied into the
 * project.
 */
class ProjectProvisionerTest {

    /** Serves a canned distribution zip named after the requested URL. */
    private static final class ToolDownloader implements Downloader {
        final List<String> urls = new ArrayList<>();

        @Override
        public void fetch(String url, Path dest) throws IOException {
            urls.add(url);
            String file = url.substring(url.lastIndexOf('/') + 1);
            String dir = file.replace("-bin.zip", "");
            String launcher = dir.startsWith("gradle") ? "gradle" : "mvn";
            CannedArchives.writeToolZip(dest, dir, launcher);
        }
    }

    private static Path makeJdkHome(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    /** A manager holding one JDK 21 in a store that is a sibling of the project. */
    private static JdkRegistry registryWithJdk21(Path jdkBase) throws IOException {
        makeJdkHome(jdkBase.resolve("jdks").resolve("jdk-21.0.4+7"), "21.0.4");
        return new JdkRegistry(jdkBase, (url, dest) -> {
        });
    }

    private static Properties toolchainProps(Path root) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(
                root.resolve(".espresso").resolve("toolchain.properties"), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    @Test
    @DisplayName("a Gradle project downloads its own Gradle, scaffolds the build, and selects the JDK")
    void provisionsGradleProject(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        Path jdkHome = registry.resolve("jdk-21.0.4+7").orElseThrow();
        ToolDownloader downloader = new ToolDownloader();
        ProjectProvisioner provisioner = new ProjectProvisioner(downloader);
        Path root = tmp.resolve("demo");
        List<String> progress = new ArrayList<>();

        NewProjectRequest req =
                new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "jdk-21.0.4+7");
        Path entry = provisioner.provision(root, req, registry, progress::add);

        // Sources and descriptors.
        assertEquals(root.resolve("src/main/java/Main.java"), entry);
        assertTrue(Files.isRegularFile(entry));
        assertTrue(Files.readString(entry).contains("class Main"));
        assertTrue(Files.readString(root.resolve("settings.gradle")).contains("rootProject.name = 'demo'"));
        String build = Files.readString(root.resolve("build.gradle"));
        assertTrue(build.contains("id 'application'"), build);
        assertTrue(build.contains("mainClass = 'Main'"), build);
        assertTrue(build.contains("options.release = 21"), build);
        assertTrue(Files.readString(root.resolve(".gitignore")).contains(".espresso/"));
        assertTrue(Files.isRegularFile(root.resolve("README.md")));

        // The project's own Gradle, runnable.
        Path launcher = root.resolve(".espresso/tools/gradle-8.10/bin/gradle");
        assertTrue(Files.isExecutable(launcher), "the downloaded launcher is executable");
        assertEquals(Optional.of(launcher), ProjectToolchain.gradleLauncher(root));
        assertEquals(List.of("https://services.gradle.org/distributions/gradle-8.10-bin.zip"),
                downloader.urls);

        // The toolchain record.
        Properties props = toolchainProps(root);
        assertEquals("GRADLE", props.getProperty("buildSystem"));
        assertEquals("8.10", props.getProperty("toolVersion"));
        assertEquals(".espresso/tools/gradle-8.10", props.getProperty("toolHome"));
        assertEquals("jdk-21.0.4+7", props.getProperty("jdkId"));

        // The JDK is selected from the shared store, never copied into the project.
        assertEquals(Optional.of(jdkHome), ProjectToolchain.jdkHome(root, registry));
        assertTrue(jdkHome.startsWith(tmp.resolve("jdkstore")), "the JDK lives in the store");
        assertFalse(jdkHome.startsWith(root), "no JDK is copied into the project");
        assertFalse(Files.exists(root.resolve(".espresso/jdks")));
        try (Stream<Path> walk = Files.walk(root)) {
            assertTrue(walk.filter(Files::isRegularFile)
                            .noneMatch(p -> p.getFileName().toString().equals("java")),
                    "the project tree holds no java binary");
        }
        assertTrue(progress.stream().anyMatch(m -> m.contains("Downloading Gradle 8.10")),
                progress.toString());
    }

    @Test
    @DisplayName("a Maven project downloads its own Maven and bakes the release into pom.xml")
    void provisionsMavenProject(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        ToolDownloader downloader = new ToolDownloader();
        ProjectProvisioner provisioner = new ProjectProvisioner(downloader);
        Path root = tmp.resolve("demo");

        NewProjectRequest req =
                new NewProjectRequest("demo", BuildSystem.MAVEN, "3.9.9", "jdk-21.0.4+7");
        provisioner.provision(root, req, registry, ProgressListener.SILENT);

        String pom = Files.readString(root.resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>demo</artifactId>"), pom);
        assertTrue(pom.contains("<maven.compiler.release>21</maven.compiler.release>"), pom);
        assertTrue(pom.contains("<mainClass>Main</mainClass>"), pom);
        Path launcher = root.resolve(".espresso/tools/apache-maven-3.9.9/bin/mvn");
        assertEquals(Optional.of(launcher), ProjectToolchain.mavenLauncher(root));
        assertEquals(List.of("https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/"
                + "apache-maven-3.9.9-bin.zip"), downloader.urls);
        assertEquals("MAVEN", toolchainProps(root).getProperty("buildSystem"));
    }

    @Test
    @DisplayName("a plain Java project provisions no tool, uses src/, and still records its JDK")
    void provisionsPlainJavaWithoutTool(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        ToolDownloader downloader = new ToolDownloader();
        ProjectProvisioner provisioner = new ProjectProvisioner(downloader);
        Path root = tmp.resolve("demo");

        NewProjectRequest req =
                new NewProjectRequest("demo", BuildSystem.NONE, "", "jdk-21.0.4+7");
        Path entry = provisioner.provision(root, req, registry, ProgressListener.SILENT);

        assertEquals(0, downloader.urls.size(), "no build tool is downloaded");
        assertFalse(Files.exists(root.resolve(".espresso/tools")));
        assertFalse(Files.exists(root.resolve("pom.xml")));
        assertFalse(Files.exists(root.resolve("build.gradle")));
        assertEquals(root.resolve("src/Main.java"), entry);
        assertTrue(Files.isRegularFile(entry), "the plain-Java layout uses src/");
        Properties props = toolchainProps(root);
        assertEquals("NONE", props.getProperty("buildSystem"));
        assertEquals("", props.getProperty("toolHome"));
        assertEquals("jdk-21.0.4+7", props.getProperty("jdkId"));
    }

    @Test
    @DisplayName("an existing non-empty directory is refused and left untouched")
    void refusesNonEmptyDir(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        ProjectProvisioner provisioner = new ProjectProvisioner(new ToolDownloader());
        Path root = tmp.resolve("demo");
        Files.createDirectories(root);
        Files.writeString(root.resolve("existing.txt"), "x");

        NewProjectRequest req = new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "");
        IOException ex = assertThrows(IOException.class,
                () -> provisioner.provision(root, req, registry, ProgressListener.SILENT));
        assertTrue(ex.getMessage().contains("not empty"), ex.getMessage());
        assertFalse(Files.exists(root.resolve("build.gradle")), "nothing was scaffolded");
        assertTrue(Files.isRegularFile(root.resolve("existing.txt")));
    }

    @Test
    @DisplayName("a JDK missing from the manager is rejected before the directory is created")
    void refusesUnknownJdk(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        ToolDownloader downloader = new ToolDownloader();
        ProjectProvisioner provisioner = new ProjectProvisioner(downloader);
        Path root = tmp.resolve("demo");

        NewProjectRequest req =
                new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "ghost-jdk");
        IOException ex = assertThrows(IOException.class,
                () -> provisioner.provision(root, req, registry, ProgressListener.SILENT));
        assertTrue(ex.getMessage().contains("not in the manager"), ex.getMessage());
        assertFalse(Files.exists(root), "validated before anything is written");
        assertEquals(0, downloader.urls.size(), "no download is attempted");
    }

    @Test
    @DisplayName("with no JDK selected the release falls back to the editor's own feature version")
    void releaseDefaultsToEditorJdk(@TempDir Path tmp) throws Exception {
        JdkRegistry registry = registryWithJdk21(tmp.resolve("jdkstore"));
        ProjectProvisioner provisioner = new ProjectProvisioner(new ToolDownloader());
        Path root = tmp.resolve("demo");

        NewProjectRequest req = new NewProjectRequest("demo", BuildSystem.GRADLE, "8.10", "");
        provisioner.provision(root, req, registry, ProgressListener.SILENT);

        String build = Files.readString(root.resolve("build.gradle"));
        assertTrue(build.contains("options.release = " + Runtime.version().feature()), build);
        assertEquals("", toolchainProps(root).getProperty("jdkId"));
    }
}
