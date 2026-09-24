/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.swingide;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless unit tests for the {@link SwingIde} launcher's pure helpers.
 *
 * <p>Only the jar-path resolution, command construction and display selection
 * are exercised here; {@code main} (which forks the real IDE process) is
 * deliberately never invoked. Every case pins the resolution sources it depends
 * on - the {@code swingide.jar} / {@code lg.appcodebase} / {@code lg.lgserverdisplay}
 * properties are cleared up front and an explicit working directory is passed to
 * {@link SwingIde#resolveJar(File)} - so the result never depends on the
 * developer's real {@code libs/} tree or environment.</p>
 */
class SwingIdeTest {

    private String savedJarProperty;
    private String savedAppCodeBase;
    private String savedServerDisplay;

    @BeforeEach
    void saveAndClearProperties() {
        savedJarProperty = System.getProperty(SwingIde.JAR_PROPERTY);
        savedAppCodeBase = System.getProperty(SwingIde.APPCODEBASE_PROPERTY);
        savedServerDisplay = System.getProperty("lg.lgserverdisplay");
        System.clearProperty(SwingIde.JAR_PROPERTY);
        System.clearProperty(SwingIde.APPCODEBASE_PROPERTY);
        System.clearProperty("lg.lgserverdisplay");
    }

    @AfterEach
    void restoreProperties() {
        restore(SwingIde.JAR_PROPERTY, savedJarProperty);
        restore(SwingIde.APPCODEBASE_PROPERTY, savedAppCodeBase);
        restore("lg.lgserverdisplay", savedServerDisplay);
    }

    private static void restore(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Creates an empty {@code libs/swing-ide.jar} under {@code base}. */
    private static File fakeJar(final Path base) throws IOException {
        Path libs = base.resolve("libs");
        Files.createDirectories(libs);
        Path jar = libs.resolve(SwingIde.JAR_NAME);
        Files.write(jar, new byte[] {'P', 'K'});
        return jar.toFile();
    }

    @Test
    @DisplayName("the swingide.jar property wins over every other source")
    void propertyTakesPrecedence(@TempDir Path temp) throws IOException {
        Path propertyDir = Files.createDirectories(temp.resolve("prop"));
        File propertyJar = propertyDir.resolve("custom-ide.jar").toFile();
        Files.write(propertyJar.toPath(), new byte[] {'P', 'K'});

        // Competing sources that must be ignored while the property is set.
        File codebaseJar = fakeJar(Files.createDirectories(temp.resolve("codebase")));
        File cwdJar = fakeJar(Files.createDirectories(temp.resolve("cwd")));
        System.setProperty(SwingIde.JAR_PROPERTY, propertyJar.getAbsolutePath());
        System.setProperty(SwingIde.APPCODEBASE_PROPERTY,
                codebaseJar.getParentFile().getParentFile().toURI().toString());

        File resolved = SwingIde.resolveJar(cwdJar.getParentFile().getParentFile());

        assertNotNull(resolved);
        assertEquals(propertyJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("a property pointing at a missing file is skipped")
    void missingPropertyFileIsSkipped(@TempDir Path temp) throws IOException {
        System.setProperty(SwingIde.JAR_PROPERTY,
                temp.resolve("does-not-exist.jar").toString());
        Path cwd = Files.createDirectories(temp.resolve("cwd"));
        File cwdJar = fakeJar(cwd);

        File resolved = SwingIde.resolveJar(cwd.toFile());

        assertNotNull(resolved);
        assertEquals(cwdJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("the application codebase libs/ dir is used when the property is unset")
    void appCodeBaseIsUsed(@TempDir Path temp) throws IOException {
        Path codebase = Files.createDirectories(temp.resolve("codebase"));
        File codebaseJar = fakeJar(codebase);
        System.setProperty(SwingIde.APPCODEBASE_PROPERTY, codebase.toUri().toString());
        Path cwd = Files.createDirectories(temp.resolve("cwd"));

        File resolved = SwingIde.resolveJar(cwd.toFile());

        assertNotNull(resolved);
        assertEquals(codebaseJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("a plain (non file:) application codebase path is honoured")
    void plainPathCodeBaseIsUsed(@TempDir Path temp) throws IOException {
        Path codebase = Files.createDirectories(temp.resolve("codebase"));
        File codebaseJar = fakeJar(codebase);
        System.setProperty(SwingIde.APPCODEBASE_PROPERTY, codebase.toString());
        Path cwd = Files.createDirectories(temp.resolve("cwd"));

        File resolved = SwingIde.resolveJar(cwd.toFile());

        assertNotNull(resolved);
        assertEquals(codebaseJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("a malformed application codebase is ignored, not thrown")
    void malformedCodeBaseIsIgnored(@TempDir Path temp) throws IOException {
        System.setProperty(SwingIde.APPCODEBASE_PROPERTY, "file://:not a valid uri:");
        Path root = Files.createDirectories(temp.resolve("root"));
        Path cwd = Files.createDirectories(root.resolve("empty"));

        assertNull(SwingIde.resolveJar(cwd.toFile()));
    }

    @Test
    @DisplayName("the working-directory libs/ fallback resolves the jar")
    void workingDirLibsFallback(@TempDir Path temp) throws IOException {
        Path cwd = Files.createDirectories(temp.resolve("cwd"));
        File cwdJar = fakeJar(cwd);

        File resolved = SwingIde.resolveJar(cwd.toFile());

        assertNotNull(resolved);
        assertEquals(cwdJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("the parent-directory ../libs/ fallback resolves the jar")
    void parentLibsFallback(@TempDir Path temp) throws IOException {
        Path parent = Files.createDirectories(temp.resolve("parent"));
        File parentJar = fakeJar(parent);
        Path child = Files.createDirectories(parent.resolve("child"));

        File resolved = SwingIde.resolveJar(child.toFile());

        assertNotNull(resolved);
        assertEquals(parentJar.getCanonicalFile(), resolved.getCanonicalFile());
    }

    @Test
    @DisplayName("resolution yields null when no source has the jar")
    void returnsNullWhenAbsent(@TempDir Path temp) throws IOException {
        Path root = Files.createDirectories(temp.resolve("root"));
        Path cwd = Files.createDirectories(root.resolve("empty"));

        assertNull(SwingIde.resolveJar(cwd.toFile()));
    }

    @Test
    @DisplayName("the no-argument resolver reads user.dir and never throws")
    void noArgResolverIsSafe() {
        assertDoesNotThrow(() -> SwingIde.resolveJar());
    }

    @Test
    @DisplayName("the child command is this JVM's java, -jar, and the jar path")
    void buildCommandShape(@TempDir Path temp) throws IOException {
        File jar = temp.resolve("swing-ide.jar").toFile();
        Files.write(jar.toPath(), new byte[] {'P', 'K'});

        List<String> command = SwingIde.buildCommand(jar);

        assertEquals(3, command.size());
        String expectedJava = System.getProperty("java.home")
                + File.separator + "bin" + File.separator + "java";
        assertEquals(expectedJava, command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(jar.getAbsolutePath(), command.get(2));
    }

    @Test
    @DisplayName("the lg3d server display is preferred when set")
    void displayPrefersServerDisplay() {
        System.setProperty("lg.lgserverdisplay", ":1");

        assertEquals(":1", SwingIde.resolveDisplay());
    }

    @Test
    @DisplayName("the display falls back to $DISPLAY then :0")
    void displayFallsBack() {
        String env = System.getenv("DISPLAY");
        String expected = (env != null && !env.isBlank()) ? env : SwingIde.DEFAULT_DISPLAY;

        String display = SwingIde.resolveDisplay();

        assertNotNull(display);
        assertTrue(!display.isBlank());
        assertEquals(expected, display);
    }
}
