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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The files Espresso writes into a freshly provisioned project: the build
 * descriptor for the chosen {@link BuildSystem} (a Gradle {@code build.gradle}
 * with the {@code application} plugin, or a Maven {@code pom.xml}), a {@code Main}
 * entry point, a {@code README.md} and a {@code .gitignore} that keeps the
 * project-local {@code .espresso/} toolchain out of version control.
 *
 * <p>Everything is pure text templating over {@code java.nio} - no build tool
 * runs here - so the whole scaffold is headless-testable. The compiler release
 * is taken from the JDK the project selected, so a Java 21 project emits
 * {@code release = 21} and a Java 25 project emits {@code 25}.</p>
 */
final class ProjectTemplates {

    /** The generated entry-point class name (default package). */
    static final String MAIN_CLASS = "Main";

    private ProjectTemplates() {
    }

    /**
     * Writes the {@code Main.java} entry point at the layout the build system
     * expects - {@code src/main/java} for Gradle/Maven, {@code src} for a plain
     * Java project - and returns its path so the host can open it.
     */
    static Path writeMainSource(Path root, BuildSystem buildSystem, String projectName)
            throws IOException {
        Path dir = root.resolve(sourceDir(buildSystem));
        Files.createDirectories(dir);
        Path main = dir.resolve(MAIN_CLASS + ".java");
        Files.writeString(main, mainJava(projectName), StandardCharsets.UTF_8);
        return main;
    }

    /** The source folder for a build system: standard layout, or {@code src}. */
    static String sourceDir(BuildSystem buildSystem) {
        return buildSystem == BuildSystem.NONE ? "src" : "src/main/java";
    }

    /**
     * Writes the Gradle descriptor pair: {@code settings.gradle} naming the
     * project and {@code build.gradle} applying the {@code application} plugin
     * with {@code mainClass} set and the compiler {@code release} pinned to the
     * selected JDK (no Gradle toolchain auto-provisioning - Espresso owns the
     * JDK, so the build must not download another one).
     */
    static void writeGradle(Path root, String projectName, int release) throws IOException {
        Files.writeString(root.resolve("settings.gradle"),
                settingsGradle(projectName), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle"),
                buildGradle(release), StandardCharsets.UTF_8);
    }

    /** Writes {@code pom.xml} with the compiler release and jar main class. */
    static void writeMavenPom(Path root, String projectName, int release) throws IOException {
        Files.writeString(root.resolve("pom.xml"),
                pomXml(projectName, release), StandardCharsets.UTF_8);
    }

    /** Writes a short {@code README.md} describing how to build and run. */
    static void writeReadme(Path root, String projectName, BuildSystem buildSystem)
            throws IOException {
        Files.writeString(root.resolve("README.md"),
                readme(projectName, buildSystem), StandardCharsets.UTF_8);
    }

    /** Writes {@code .gitignore}, keeping {@code .espresso/} and build output out. */
    static void writeGitignore(Path root) throws IOException {
        Files.writeString(root.resolve(".gitignore"), GITIGNORE, StandardCharsets.UTF_8);
    }

    // -- templates ---------------------------------------------------------

    private static String settingsGradle(String projectName) {
        return """
                rootProject.name = '%s'
                """.formatted(projectName);
    }

    private static String buildGradle(int release) {
        return """
                plugins {
                    id 'application'
                }

                repositories {
                    mavenCentral()
                }

                application {
                    mainClass = '%s'
                }

                tasks.withType(JavaCompile).configureEach {
                    options.release = %d
                }
                """.formatted(MAIN_CLASS, release);
    }

    private static String pomXml(String projectName, int release) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 \
                http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <groupId>espresso.app</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <packaging>jar</packaging>

                    <properties>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                        <maven.compiler.release>%d</maven.compiler.release>
                    </properties>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-jar-plugin</artifactId>
                                <version>3.4.1</version>
                                <configuration>
                                    <archive>
                                        <manifest>
                                            <mainClass>%s</mainClass>
                                        </manifest>
                                    </archive>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """.formatted(projectName, release, MAIN_CLASS);
    }

    private static String mainJava(String projectName) {
        return """
                /**
                 * Entry point for %1$s, scaffolded by Espresso.
                 */
                public class %2$s {
                    public static void main(String[] args) {
                        System.out.println("Hello from %1$s!");
                    }
                }
                """.formatted(projectName, MAIN_CLASS);
    }

    private static String readme(String projectName, BuildSystem buildSystem) {
        String how = switch (buildSystem) {
            case GRADLE -> "Run `./gradlew run` (or the project Gradle under `.espresso/tools/`).";
            case MAVEN -> "Run `./mvnw package` (or the project Maven under `.espresso/tools/`).";
            case NONE -> "Compile with `javac src/Main.java` and run with `java -cp src Main`.";
        };
        return """
                # %s

                Created with Espresso (%s).

                ## Build and run

                %s
                """.formatted(projectName, buildSystem.displayName(), how);
    }

    /** Kept as a constant: the same ignore set applies to every new project. */
    private static final String GITIGNORE = """
            # Espresso per-project toolchain and downloaded build tools
            .espresso/

            # Gradle
            .gradle/
            build/

            # Maven
            target/

            # Plain Java output
            out/
            *.class
            """;
}
