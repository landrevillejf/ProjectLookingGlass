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

/**
 * The build system a new Espresso project uses. Each value carries everything
 * provisioning and resolution need for that tool - the wrapper script name, the
 * launcher path inside an extracted distribution, the distribution folder
 * prefix and the download URL - so {@link ProjectProvisioner} and
 * {@link ProjectToolchain} share one source of truth rather than each hardcoding
 * "gradle-8.x/bin/gradle" separately.
 *
 * <p>{@link #NONE} is a plain Java project: no build tool is downloaded and the
 * editor compiles and runs it with the selected JDK's own {@code javac}/{@code java}.</p>
 */
enum BuildSystem {

    /** A Gradle project: {@code gradlew}, or a downloaded {@code gradle-<ver>}. */
    GRADLE("Gradle", "gradlew", "bin/gradle", "gradle-",
            "https://services.gradle.org/distributions/gradle-%s-bin.zip"),

    /** A Maven project: {@code mvnw}, or a downloaded {@code apache-maven-<ver>}. */
    MAVEN("Maven", "mvnw", "bin/mvn", "apache-maven-",
            "https://archive.apache.org/dist/maven/maven-3/%s/binaries/apache-maven-%s-bin.zip"),

    /** A plain Java project with no build tool. */
    NONE("Plain Java", null, null, null, null);

    private final String displayName;
    private final String wrapperName;
    private final String launcherRelPath;
    private final String dirPrefix;
    private final String urlTemplate;

    BuildSystem(String displayName, String wrapperName, String launcherRelPath,
                String dirPrefix, String urlTemplate) {
        this.displayName = displayName;
        this.wrapperName = wrapperName;
        this.launcherRelPath = launcherRelPath;
        this.dirPrefix = dirPrefix;
        this.urlTemplate = urlTemplate;
    }

    /** The label shown in the New Project form and the JDK manager. */
    String displayName() {
        return displayName;
    }

    /** True when this system downloads and runs a build tool (not {@link #NONE}). */
    boolean needsTool() {
        return this != NONE;
    }

    /** The project-local wrapper script name ({@code gradlew}/{@code mvnw}), or null. */
    String wrapperName() {
        return wrapperName;
    }

    /** The launcher path inside an extracted distribution ({@code bin/gradle}). */
    String launcherRelPath() {
        return launcherRelPath;
    }

    /** The extracted distribution folder prefix ({@code gradle-}). */
    String dirPrefix() {
        return dirPrefix;
    }

    /** The distribution folder name for {@code version} ({@code gradle-8.10}). */
    String toolDirName(String version) {
        return dirPrefix + version;
    }

    /**
     * The download URL for {@code version}. Maven's path repeats the version
     * (once in the directory, once in the file name), so its template has two
     * placeholders; every template is filled positionally.
     */
    String downloadUrl(String version) {
        if (urlTemplate == null) {
            throw new IllegalStateException(displayName + " has no download URL");
        }
        long placeholders = urlTemplate.chars().filter(c -> c == '%').count();
        return (placeholders >= 2)
                ? String.format(urlTemplate, version, version)
                : String.format(urlTemplate, version);
    }
}
