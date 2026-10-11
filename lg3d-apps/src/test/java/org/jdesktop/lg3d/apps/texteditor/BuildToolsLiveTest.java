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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in live tests for the build extensions: these run a <em>real</em>
 * {@code ./gradlew} and a <em>real</em> {@code mvn} through the production
 * {@link BuildTools#ASYNC_RUNNER} in a throwaway project, so the parsing rules
 * pinned from recorded logs in {@link BuildToolsTest}, {@link
 * GradleBuildToolsTest} and {@link MavenBuildToolsTest} are checked against the
 * tools themselves rather than against a transcript of them. Nothing here is
 * fast (a build is a JVM start-up) and nothing here is portable (a Maven
 * install, a Gradle wrapper and cached plugins are all required), so the suite
 * is <b>skipped unless</b> {@code -Dlg3d.build.integration=true} — the same
 * contract {@code JavaDebugToolsTest} uses for its real JDI attach.
 *
 * <p>Each test also asserts only what a log transcript cannot prove: that the
 * command really runs in the discovered project, that the tool's own failure
 * lands on the file that broke, and that a green build reaches the editor's
 * sinks with the stale findings cleared. When a tool is absent the test is
 * skipped, never failed — a developer machine without Maven must stay green.</p>
 */
@EnabledIfSystemProperty(named = "lg3d.build.integration", matches = "true")
class BuildToolsLiveTest {

    /** How long a real tool chain is allowed to take before the test gives up. */
    private static final long AWAIT_MS = 300_000;

    /** The POM half of the fixture: plugin versions pinned to what the cache holds. */
    private static final String POM = String.join("\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
            "  <modelVersion>4.0.0</modelVersion>",
            "  <groupId>live</groupId>",
            "  <artifactId>espresso-live</artifactId>",
            "  <version>1.0.0</version>",
            "  <properties>",
            "    <maven.compiler.release>21</maven.compiler.release>",
            "    <exec.mainClass>com.acme.App</exec.mainClass>",
            "  </properties>",
            "  <build>",
            "    <plugins>",
            "      <plugin><groupId>org.apache.maven.plugins</groupId>"
                    + "<artifactId>maven-resources-plugin</artifactId>"
                    + "<version>3.3.1</version></plugin>",
            "      <plugin><groupId>org.apache.maven.plugins</groupId>"
                    + "<artifactId>maven-compiler-plugin</artifactId>"
                    + "<version>3.11.0</version></plugin>",
            "      <plugin><groupId>org.apache.maven.plugins</groupId>"
                    + "<artifactId>maven-clean-plugin</artifactId>"
                    + "<version>3.2.0</version></plugin>",
            "    </plugins>",
            "  </build>",
            "</project>",
            "");

    /**
     * Everything one action sent to the editor's sinks. The lists are
     * copy-on-write because the build runs on a worker thread (and reports on
     * the EDT) while this test's thread streams them in its assertions; a
     * {@code Collections.synchronizedList} wrapper would not protect that
     * iteration and could throw {@link java.util.ConcurrentModificationException}.
     */
    private static final class Recorder {
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final List<String> consoles = new CopyOnWriteArrayList<>();
        final List<Diagnostic> diagnostics = new CopyOnWriteArrayList<>();
        final List<String> cleared = new CopyOnWriteArrayList<>();
        /** The action's answer: the first status line that is not a "Running …" notice. */
        private String terminal;

        void record(String message) {
            synchronized (this) {
                statuses.add(message);
                if (!isStart(message)) {
                    terminal = message;
                }
                notifyAll();
            }
        }

        /** Blocks until the terminal status line arrives on the EDT. */
        synchronized String awaitTerminal() throws InterruptedException {
            long deadline = System.currentTimeMillis() + AWAIT_MS;
            while (terminal == null) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new InterruptedException("no status line within " + AWAIT_MS
                            + " ms; " + statuses);
                }
                wait(Math.min(1000, left));
            }
            return terminal;
        }
    }

    private static boolean isStart(String message) {
        return message.startsWith("Running ") || message.startsWith("Compiling ");
    }

    // -- the live runs -------------------------------------------------------

    @Test
    @DisplayName("a real ./gradlew compile failure is filed on the file that broke")
    void gradleCompileFailureEndToEnd(@TempDir Path project) throws Exception {
        Path wrapper = copyRepoWrapper(project);
        assumeTrue(wrapper != null, "no ./gradlew to copy out of the repository");
        Files.writeString(project.resolve("settings.gradle"),
                "rootProject.name = 'espresso-live'\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), "apply plugin: 'java'\n",
                StandardCharsets.UTF_8);
        Path broken = write(project.resolve("src/main/java/com/acme"), "App.java",
                "package com.acme;\npublic class App {\n  int x = ;\n}\n");

        Recorder rec = new Recorder();
        GradleBuildTools tools = new GradleBuildTools();
        tools.onEditorStarted(context(rec));
        Path doc = write(project.resolve("src/main/java/com/acme"), "Clean.java",
                "package com.acme;\npublic class Clean {\n}\n");
        tools.onDocumentOpened(document(doc));
        tools.toolbarContributions().get(0).getAction().run();

        String status = rec.awaitTerminal();
        assertTrue(status.startsWith("gradle build — BUILD FAILED"), status);
        assertTrue(status.contains("Execution failed for task ':compileJava'"), status);
        assertTrue(status.contains("1 error(s), 0 warning(s) in Problems"), status);
        assertEquals(List.of(broken.toString()), rec.diagnostics.stream()
                .map(Diagnostic::path).toList(), rec.diagnostics.toString());
        Diagnostic d = rec.diagnostics.get(0);
        assertEquals(3, d.line(), "the error is on the bad declaration line");
        assertEquals("illegal start of expression", d.message(), d.toString());
        assertTrue(rec.consoles.get(0).startsWith("gradle build output"), rec.toString());
    }

    @Test
    @DisplayName("a real ./gradlew green build clears the stale findings")
    void gradleGreenBuildClears(@TempDir Path project) throws Exception {
        Path wrapper = copyRepoWrapper(project);
        assumeTrue(wrapper != null, "no ./gradlew to copy out of the repository");
        Files.writeString(project.resolve("settings.gradle"),
                "rootProject.name = 'espresso-live'\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"), "apply plugin: 'java'\n",
                StandardCharsets.UTF_8);
        Path doc = write(project.resolve("src/main/java/com/acme"), "App.java",
                "package com.acme;\npublic class App {\n}\n");

        Recorder rec = new Recorder();
        GradleBuildTools tools = new GradleBuildTools();
        tools.onEditorStarted(context(rec));
        tools.onDocumentOpened(document(doc));
        tools.toolbarContributions().get(0).getAction().run();

        String status = rec.awaitTerminal();
        assertTrue(status.startsWith("gradle build — BUILD SUCCESSFUL"), status);
        assertEquals(List.of(doc.toString()), rec.cleared,
                "a green build reports no findings and clears the document's "
                        + "stale squiggles, but got " + rec.diagnostics);
        assertTrue(rec.diagnostics.isEmpty(), rec.diagnostics.toString());
    }

    @Test
    @DisplayName("a real mvn compile failure is filed with Maven's own line and column")
    void mavenCompileFailureEndToEnd(@TempDir Path project) throws Exception {
        assumeTrue(mavenAvailable(project), "neither ./mvnw nor mvn on PATH");
        Files.writeString(project.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        Path broken = write(project.resolve("src/main/java/com/acme"), "App.java",
                "package com.acme;\npublic class App {\n  int x = ;\n}\n");

        Recorder rec = new Recorder();
        MavenBuildTools tools = new MavenBuildTools();
        tools.onEditorStarted(context(rec));
        tools.onDocumentOpened(document(broken));
        tools.toolbarContributions().get(0).getAction().run();

        String status = rec.awaitTerminal();
        assertTrue(status.startsWith("mvn compile — BUILD FAILURE"), status);
        assertTrue(status.contains("maven-compiler-plugin compile failed"), status);
        Diagnostic d = rec.diagnostics.get(0);
        assertEquals(broken.toString(), d.path(), rec.diagnostics.toString());
        assertEquals(3, d.line(), "the error is on the bad declaration line");
        assertEquals(11, d.col(), "Maven reports the column javac faulted at");
        assertEquals("illegal start of expression", d.message(), d.toString());
    }

    @Test
    @DisplayName("a real mvn run compiles, then launches the POM's main class")
    void mavenRunApplicationEndToEnd(@TempDir Path project) throws Exception {
        assumeTrue(mavenAvailable(project), "neither ./mvnw nor mvn on PATH");
        Files.writeString(project.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        Path doc = write(project.resolve("src/main/java/com/acme"), "App.java",
                "package com.acme;\n"
                        + "public class App {\n"
                        + "  public static void main(String[] args) {\n"
                        + "    System.out.println(\"espresso-live-ok\");\n"
                        + "  }\n"
                        + "}\n");

        Recorder rec = new Recorder();
        MavenBuildTools tools = new MavenBuildTools();
        tools.onEditorStarted(context(rec));
        tools.onDocumentOpened(document(doc));
        tools.toolbarContributions().get(3).getAction().run();

        String status = rec.awaitTerminal();
        assertEquals("mvn run — OK", status, rec.statuses.toString());
        assertTrue(rec.statuses.contains("Running com.acme.App…"), rec.statuses.toString());
        assertTrue(rec.consoles.stream().anyMatch(c -> c.contains("espresso-live-ok")),
                "the launched program's stdout belongs to the Output panel: " + rec.consoles);
        assertTrue(Files.isRegularFile(project.resolve("target/classes/com/acme/App.class")),
                "the compile step is Maven's own, writing the standard layout");
    }

    // -- fixtures ------------------------------------------------------------

    /** The launcher the real build will use: {@code mvnw} in the project, else {@code mvn}. */
    private static boolean mavenAvailable(Path project) {
        return BuildTools.launcher(project, "mvnw", "mvn").isPresent();
    }

    /**
     * Copy the repository's own wrapper into a temp project so the live Gradle
     * run uses the pinned Gradle 8.14 the build itself uses, rather than
     * whatever happens to be on {@code PATH}. Returns null (the test then
     * skips) when the wrapper is not where the test task's working directory
     * expects it.
     */
    private static Path copyRepoWrapper(Path project) throws IOException {
        Optional<Path> repo = repoRoot();
        if (repo.isEmpty()) {
            return null;
        }
        Path gradlew = repo.get().resolve("gradlew");
        Files.copy(gradlew, project.resolve("gradlew"));
        Path wrapperJar = repo.get().resolve("gradle").resolve("wrapper");
        if (Files.isDirectory(wrapperJar)) {
            Path into = project.resolve("gradle").resolve("wrapper");
            Files.createDirectories(into);
            for (Path p : Files.list(wrapperJar).toList()) {
                Files.copy(p, into.resolve(p.getFileName()));
            }
        }
        assertTrue(project.resolve("gradlew").toFile().setExecutable(true),
                "the copied wrapper must be executable");
        return project.resolve("gradlew");
    }

    /** Walk up from the test task's directory to the repository root. */
    private static Optional<Path> repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        for (int up = 0; up <= BuildTools.MAX_WALK_UP && dir != null; up++) {
            if (Files.isRegularFile(dir.resolve("gradlew"))
                    && Files.isDirectory(dir.resolve("gradle").resolve("wrapper"))) {
                return Optional.of(dir);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static EditorContext context(Recorder rec) {
        return new EditorContext(EnumSet.allOf(TextEditorPermission.class),
                EditorSinks.builder()
                        .showMessage(rec::record)
                        .showOutput((title, body) -> rec.consoles.add(title + "\n" + body))
                        .reportDiagnostics((path, diags) -> rec.diagnostics.addAll(diags))
                        .clearDiagnostics(rec.cleared::add)
                        .build());
    }

    private static DocumentContext document(Path file) throws IOException {
        return new DocumentContext(file.toString(), file.getFileName().toString(),
                Files.readString(file, StandardCharsets.UTF_8), "", s -> { }, s -> { });
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }
}
