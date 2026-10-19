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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Resolves the tools a project actually uses, from the project's own tree only.
 * A build tool is found in priority order - a wrapper the project carries that
 * honours {@code JAVA_HOME}, a distribution Espresso downloaded into
 * {@code .espresso/tools/}, then one unpacked at the project root, and only then
 * a wrapper script that ignores its environment; and the JDK is the one the
 * project selected from the manager, recorded in
 * {@code .espresso/toolchain.properties}.
 *
 * <p>Nothing here ever consults the system {@code PATH}. That is the whole
 * point of the hermetic toolchain: Espresso runs the project's Gradle/Maven and
 * the project's chosen JDK, never whatever happens to be installed globally. It
 * also fixes the long-standing "Gradle is not available" report on a project
 * that ships a Gradle distribution at its root, which the old {@code gradlew}-or-
 * PATH lookup could not see.</p>
 */
final class ProjectToolchain {

    /** The per-project Espresso folder holding the toolchain and tools. */
    static final String TOOLCHAIN_DIR = ".espresso";
    /** The sub-folder provisioned build tools are extracted into. */
    static final String TOOLS_DIR = "tools";
    /** The properties file recording what the project selected. */
    static final String TOOLCHAIN_FILE = "toolchain.properties";

    static final String KEY_BUILD_SYSTEM = "buildSystem";
    static final String KEY_TOOL_VERSION = "toolVersion";
    static final String KEY_TOOL_HOME = "toolHome";
    static final String KEY_JDK_ID = "jdkId";

    private static final boolean WINDOWS = JdkRegistry.osName().equals("windows");

    private ProjectToolchain() {
    }

    /**
     * The project's Gradle launcher: {@code <root>/gradlew}, then
     * {@code .espresso/tools/gradle-<ver>/bin/gradle}, then a {@code gradle-<ver>}
     * unpacked at the root, then {@code <root>/gradle/bin/gradle}. Empty when the
     * project has provisioned no Gradle and carries no wrapper.
     */
    static Optional<Path> gradleLauncher(Path root) {
        return launcher(root, BuildSystem.GRADLE);
    }

    /**
     * The project's Maven launcher: {@code <root>/mvnw}, then
     * {@code .espresso/tools/apache-maven-<ver>/bin/mvn}, then an
     * {@code apache-maven-<ver>} unpacked at the root. Empty when none is present.
     */
    static Optional<Path> mavenLauncher(Path root) {
        return launcher(root, BuildSystem.MAVEN);
    }

    private static Optional<Path> launcher(Path root, BuildSystem bs) {
        if (root == null || !bs.needsTool()) {
            return Optional.empty();
        }
        Path wrapper = wrapperOrNull(root, bs);
        if (wrapper != null && honorsJavaHome(wrapper)) {
            return Optional.of(wrapper);
        }
        Path provisioned = firstLauncherUnder(
                root.resolve(TOOLCHAIN_DIR).resolve(TOOLS_DIR), bs);
        if (provisioned != null) {
            return Optional.of(provisioned);
        }
        Path atRoot = firstLauncherUnder(root, bs);
        if (atRoot != null) {
            return Optional.of(atRoot);
        }
        // A hand-unpacked Gradle is sometimes just <root>/gradle/bin/gradle.
        if (bs == BuildSystem.GRADLE) {
            Path bare = launcherIn(root.resolve("gradle"), bs);
            if (bare != null) {
                return Optional.of(bare);
            }
        }
        // Last resort, not first choice: a script that ignores JAVA_HOME. It is
        // still the tool version the project declared, and the build pins the JDK
        // as a Gradle property as well, so it runs on the right JVM anyway.
        return Optional.ofNullable(wrapper);
    }

    /**
     * Whether a wrapper script picks its JVM from {@code JAVA_HOME}, the way the
     * script {@code gradle wrapper} generates does. A scaffolder that hand-writes
     * a few lines of {@code exec java -jar gradle-wrapper.jar "$@"} does not: that
     * script resolves {@code java} from {@code PATH}, so the project builds on
     * whatever JDK the host defaults to and an old Gradle meets a class file it
     * cannot read. Such a wrapper loses priority to a distribution Espresso
     * provisioned; an unreadable script is treated the same way, since the
     * fallback paths are the safer ones.
     */
    private static boolean honorsJavaHome(Path script) {
        try {
            return new String(Files.readAllBytes(script), StandardCharsets.UTF_8)
                    .contains("JAVA_HOME");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The home of the JDK this project selected, resolved through the manager.
     * Empty when the project recorded no {@code jdkId} or that JDK is gone; the
     * caller then falls back to the editor's own JDK rather than to a system one.
     */
    static Optional<Path> jdkHome(Path root, JdkRegistry registry) {
        String id = property(root, KEY_JDK_ID);
        if (id == null || id.isBlank() || registry == null) {
            return Optional.empty();
        }
        return registry.resolve(id);
    }

    /** The selected JDK id recorded for this project ("" when none). */
    static String jdkId(Path root) {
        return orEmpty(property(root, KEY_JDK_ID));
    }

    /**
     * A tool ({@code java}, {@code javac}) inside a resolved JDK home, with the
     * Windows {@code .exe} suffix applied. Empty when the home is null or the
     * tool is missing, so a caller can fall back to the editor's own JDK.
     */
    static Optional<Path> jdkTool(Path jdkHome, String tool) {
        if (jdkHome == null) {
            return Optional.empty();
        }
        Path bin = jdkHome.resolve("bin");
        Path unix = bin.resolve(tool);
        if (Files.isRegularFile(unix)) {
            return Optional.of(unix);
        }
        if (WINDOWS) {
            Path exe = bin.resolve(tool + ".exe");
            if (Files.isRegularFile(exe)) {
                return Optional.of(exe);
            }
        }
        return Optional.empty();
    }

    /** The provisioned build-tool version recorded for this project ("" when none). */
    static String toolVersion(Path root) {
        return orEmpty(property(root, KEY_TOOL_VERSION));
    }

    /** The build system recorded for this project, {@link BuildSystem#NONE} if unset. */
    static BuildSystem buildSystem(Path root) {
        String value = property(root, KEY_BUILD_SYSTEM);
        if (value == null) {
            return BuildSystem.NONE;
        }
        try {
            return BuildSystem.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return BuildSystem.NONE;
        }
    }

    /** The project-local wrapper for a build system, if the root carries one. */
    static Optional<Path> wrapper(Path root, BuildSystem bs) {
        return Optional.ofNullable(wrapperOrNull(root, bs));
    }

    private static Path wrapperOrNull(Path root, BuildSystem bs) {
        if (root == null || bs.wrapperName() == null) {
            return null;
        }
        Path script = root.resolve(bs.wrapperName());
        if (Files.isRegularFile(script)) {
            return script;
        }
        if (WINDOWS) {
            Path bat = root.resolve(bs.wrapperName() + ".bat");
            if (Files.isRegularFile(bat)) {
                return bat;
            }
        }
        return null;
    }

    /**
     * The launcher inside the highest-versioned {@code <prefix>*} distribution
     * folder under {@code dir} (so {@code .espresso/tools} or the project root).
     */
    private static Path firstLauncherUnder(Path dir, BuildSystem bs) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        String prefix = bs.dirPrefix();
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> kids = Files.list(dir)) {
            kids.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(matches::add);
        } catch (IOException e) {
            return null;
        }
        matches.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        for (Path match : matches) {
            Path found = launcherIn(match, bs);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The runnable launcher inside one tool home, with the Windows script variant. */
    private static Path launcherIn(Path toolHome, BuildSystem bs) {
        if (toolHome == null || !Files.isDirectory(toolHome)) {
            return null;
        }
        Path unix = toolHome.resolve(bs.launcherRelPath());
        if (Files.isRegularFile(unix)) {
            return unix;
        }
        if (WINDOWS) {
            Path bat = toolHome.resolve(bs.launcherRelPath() + ".bat");
            if (Files.isRegularFile(bat)) {
                return bat;
            }
            Path cmd = toolHome.resolve(bs.launcherRelPath() + ".cmd");
            if (Files.isRegularFile(cmd)) {
                return cmd;
            }
        }
        return null;
    }

    private static String property(Path root, String key) {
        if (root == null) {
            return null;
        }
        Path file = root.resolve(TOOLCHAIN_DIR).resolve(TOOLCHAIN_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            return null;
        }
        return props.getProperty(key);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
