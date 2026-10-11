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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the Gradle extension. A synchronous fake {@link
 * BuildTools.Runner} records the argv and replays queued results, so no test
 * spawns Gradle; the projects are real temp trees (the walk-up rules need real
 * directories) and the replays are the verbatim logs captured from Gradle 8.14.
 */
class GradleBuildToolsTest {

    /** Records every command and delivers queued results synchronously. */
    private static final class FakeRunner implements BuildTools.Runner {
        final List<List<String>> commands = new ArrayList<>();
        final List<Path> workDirs = new ArrayList<>();
        final Deque<Toolchain.Result> replies = new ArrayDeque<>();
        Toolchain.Result fallback = new Toolchain.Result(0, "");

        @Override
        public void run(List<String> command, Path workDir, long timeoutMs,
                        Consumer<Toolchain.Result> done) {
            commands.add(command);
            workDirs.add(workDir);
            done.accept(replies.isEmpty() ? fallback : replies.poll());
        }
    }

    private List<String> msgs;
    private List<String> consoleTitles;
    private List<String> consoleBodies;
    private List<String> reportPaths;
    private List<List<Diagnostic>> reports;
    private List<String> clearedPaths;
    private Map<String, List<StructureSymbol>> structures;
    private FakeRunner runner;
    private String[] doc;

    /** Builds the provider against a document, granted every permission. */
    private GradleBuildTools wire(Path root, String fileName, String text) {
        return wire(root, fileName, text, EnumSet.allOf(TextEditorPermission.class));
    }

    private GradleBuildTools wire(Path root, String fileName, String text,
                                  EnumSet<TextEditorPermission> granted) {
        GradleBuildTools tools = new GradleBuildTools();
        msgs = new ArrayList<>();
        consoleTitles = new ArrayList<>();
        consoleBodies = new ArrayList<>();
        reportPaths = new ArrayList<>();
        reports = new ArrayList<>();
        clearedPaths = new ArrayList<>();
        structures = new LinkedHashMap<>();
        doc = new String[] {text};
        Path filePath = fileName.isEmpty() ? null : root.resolve(fileName);
        tools.onEditorStarted(new EditorContext(granted, EditorSinks.builder()
                .showMessage(msgs::add)
                .showOutput((t, b) -> {
                    consoleTitles.add(t);
                    consoleBodies.add(b);
                })
                .reportDiagnostics((p, ds) -> {
                    reportPaths.add(String.valueOf(p));
                    reports.add(ds);
                })
                .clearDiagnostics(clearedPaths::add)
                .showStructure((p, ss) -> structures.put(String.valueOf(p), ss))
                .build()));
        tools.onDocumentOpened(new DocumentContext(
                (filePath == null) ? null : filePath.toString(),
                (filePath == null) ? null : fileName, text, "", s -> doc[0] = s, s -> { }));
        runner = new FakeRunner();
        tools.setRunnerForTesting(runner);
        return tools;
    }

    /** A Gradle project with a wrapper and one Java source open in the editor. */
    private Path javaDocOf(Path root) throws IOException {
        Files.writeString(root.resolve("build.gradle"), "apply plugin: 'java'\n",
                StandardCharsets.UTF_8);
        Files.createFile(root.resolve("gradlew"));
        Path src = root.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("acme").resolve("App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class App {\n}\n", StandardCharsets.UTF_8);
        return src;
    }

    private void act(GradleBuildTools tools, int action) {
        tools.toolbarContributions().get(action).getAction().run();
    }

    // -- SPI ----------------------------------------------------------------

    @Test
    @DisplayName("the manifest declares the Build category and the Ctrl+Alt+Shift family")
    void manifestAndContributions() {
        GradleBuildTools tools = new GradleBuildTools();
        TextEditorManifest m = tools.manifest();
        assertEquals("lg3d.gradle-build", m.getId());
        assertEquals("Gradle Build", m.getName());
        assertEquals("Build", tools.category());
        assertTrue(m.getPermissions().containsAll(List.of(TextEditorPermission.READ,
                TextEditorPermission.FILE_IO, TextEditorPermission.TOOLBAR,
                TextEditorPermission.DIAGNOSE)), m.getPermissions().toString());
        assertFalse(m.getPermissions().contains(TextEditorPermission.WRITE),
                "a build never edits the document");

        List<ToolbarContribution> c = tools.toolbarContributions();
        assertEquals(6, c.size());
        assertEquals(List.of("Build", "Run Tests", "Run Application", "Clean",
                "List Tasks", "Outline Build File"),
                c.stream().map(ToolbarContribution::getLabel).toList());
        assertEquals(List.of("control alt shift G", "control alt shift T",
                "control alt shift U", "control alt shift C", "control alt shift S"),
                c.subList(0, 5).stream().map(ToolbarContribution::getAccelerator).toList());
        assertEquals("", c.get(5).getAccelerator(), "outlining stays unbound");
    }

    // -- the task actions ----------------------------------------------------

    @Test
    @DisplayName("build runs the project's own wrapper with a plain console, in the project directory")
    void buildUsesTheWrapperAndPlainConsole(@TempDir Path root) throws Exception {
        Path src = javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(0, "\nBUILD SUCCESSFUL in 7s\n"
                + "2 actionable tasks: 2 executed\n");

        act(tools, 0);

        assertEquals(List.of(root.resolve("gradlew").toString(), "--console=plain", "build"),
                runner.commands.get(0));
        assertEquals(List.of(root), runner.workDirs, "the build runs in the project root");
        assertEquals(List.of("gradle build output"), consoleTitles);
        assertTrue(consoleBodies.get(0).contains("BUILD SUCCESSFUL"), consoleBodies.toString());
        assertEquals("class App {\n}\n", doc[0], "the document is never touched by a build");
        assertTrue(msgs.get(0).startsWith("Running gradle build in " + root), msgs.toString());
        assertEquals("gradle build \u2014 BUILD SUCCESSFUL in 7s", msgs.get(1));
        assertTrue(reportPaths.isEmpty(), msgs.toString());
        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                clearedPaths, "a green build wipes the stale squiggles");
        assertTrue(Files.exists(src));
    }

    @Test
    @DisplayName("each action names its own task")
    void taskPerAction(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 1);
        act(tools, 2);
        act(tools, 3);
        act(tools, 4);
        assertEquals(List.of("test", "run", "clean", "tasks"),
                runner.commands.stream().map(c -> c.get(2)).toList());
    }

    @Test
    @DisplayName("a compile failure files Problems and repeats Gradle's own headline")
    void compileFailureFilesDiagnostics(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Path broken = write(root.resolve("src/main/java/com/acme"), "Broken.java",
                "class Broken {\n  int x = ;\n}\n");
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, String.join("\n",
                broken + ":3: error: illegal start of expression",
                "  int x = ;",
                "          ^",
                "1 error",
                "",
                "FAILURE: Build failed with an exception.",
                "",
                "* What went wrong:",
                "Execution failed for task ':compileJava'.",
                "> Compilation failed; see the compiler output below.",
                "  " + broken + ":3: error: illegal start of expression",
                "",
                "BUILD FAILED in 7s"));

        act(tools, 0);

        assertEquals(1, reports.size());
        List<Diagnostic> ds = reports.get(0);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(broken.toString(), ds.get(0).path());
        assertEquals(3, ds.get(0).line());
        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                reportPaths);
        assertTrue(msgs.get(1).startsWith("gradle build \u2014 BUILD FAILED in 7s: "
                + "Execution failed for task ':compileJava'"), msgs.toString());
        assertTrue(msgs.get(1).endsWith("1 error(s), 0 warning(s) in Problems"), msgs.toString());
    }

    @Test
    @DisplayName("a green build clears the stale findings instead of leaving squiggles")
    void successClearsDiagnostics(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(0, "BUILD SUCCESSFUL in 7s");

        act(tools, 0);

        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                clearedPaths, clearedPaths.toString());
        assertTrue(reports.isEmpty());
    }

    @Test
    @DisplayName("clean and tasks are console-only: a build's Problems list is not their business")
    void nonDiagnosticActions(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 3);
        act(tools, 4);
        assertTrue(reports.isEmpty() && clearedPaths.isEmpty());
        assertEquals(2, consoleTitles.size());
    }

    @Test
    @DisplayName("a failing test run points at the HTML report Gradle wrote")
    void testFailureAdvertisesTheReport(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Path test = write(root.resolve("src/test/java/com/acme"), "AppTest.java",
                "class AppTest {\n}\n");
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, String.join("\n",
                "> Task :test FAILED",
                "",
                "AppTest > fails() FAILED",
                "    java.lang.AssertionError at AppTest.java:5",
                "",
                "* What went wrong:",
                "Execution failed for task ':test'.",
                "> There were failing tests. See the report at: file://" + root
                        + "/build/reports/tests/test/index.html",
                "",
                "BUILD FAILED in 7s"));

        act(tools, 1);

        assertEquals(1, reports.get(0).size());
        assertEquals(test.toString(), reports.get(0).get(0).path());
        assertTrue(msgs.get(1).endsWith("\u2014 report: file://" + root
                + "/build/reports/tests/test/index.html"), msgs.toString());
    }

    @Test
    @DisplayName("a run without the application plugin says so in the project's own words")
    void runWithoutApplicationPlugin(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, String.join("\n",
                "FAILURE: Build failed with an exception.",
                "",
                "* What went wrong:",
                "Task 'run' not found in root project 'demo-app'.",
                "",
                "BUILD FAILED in 6s"));

        act(tools, 2);

        assertTrue(msgs.get(1).contains("Task 'run' not found in root project 'demo-app'."),
                msgs.toString());
    }

    @Test
    @DisplayName("an app that never exits is killed at the limit, and the line says so")
    void timedOutRun(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(124, "[timed out after 120s \u2014 killed]");

        act(tools, 2);

        assertEquals("gradle run \u2014 still running when the time limit passed; killed",
                msgs.get(1));
    }

    // -- the guards -----------------------------------------------------------

    @Test
    @DisplayName("outside a Gradle project nothing is spawned, and the line explains why")
    void noProject(@TempDir Path root) throws IOException {
        Path src = write(root, "App.java", "class App {\n}\n");
        GradleBuildTools tools = wire(root, "App.java", "class App {\n}\n");
        act(tools, 0);
        assertTrue(runner.commands.isEmpty());
        assertTrue(msgs.get(0).contains("no build.gradle within " + BuildTools.MAX_WALK_UP
                + " folders above"), msgs.toString());
        assertTrue(Files.exists(src));
    }

    @Test
    @DisplayName("a project with neither a wrapper nor a Gradle on PATH is reported, not crashed")
    void noLauncher(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Files.delete(root.resolve("gradlew"));
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        tools.setLauncherForTesting(r -> Optional.empty());
        act(tools, 0);
        assertTrue(runner.commands.isEmpty());
        assertTrue(msgs.get(0).contains("Gradle is not available \u2014 no ./gradlew in " + root
                + " and no gradle on PATH"), msgs.toString());
    }

    @Test
    @DisplayName("revoked permissions keep the build's output and findings off the editor surfaces")
    void permissionGating(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Path broken = write(root.resolve("src/main/java/com/acme"), "Broken.java", "x\n");
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n",
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.TOOLBAR));
        runner.fallback = new Toolchain.Result(1,
                broken + ":3: error: illegal start of expression\nBUILD FAILED in 7s");

        act(tools, 0);

        assertEquals(1, runner.commands.size(), "the build still ran");
        assertTrue(consoleBodies.isEmpty(), "showOutput is FILE_IO-gated");
        assertTrue(reports.isEmpty(), "reportDiagnostics is DIAGNOSE-gated");
        assertEquals(2, msgs.size(), "the status line is always allowed");
    }

    // -- outlining -------------------------------------------------------------

    @Test
    @DisplayName("the open build script is outlined from the buffer, unsaved edits included")
    void outlineOpenBuildFile(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "build.gradle",
                "apply plugin: 'java'\nrepositories {\n    mavenCentral()\n}\n");
        act(tools, 5);
        List<StructureSymbol> rows = structures.get(root.resolve("build.gradle").toString());
        assertEquals(List.of("java:plugin", "mavenCentral:repository"),
                rows.stream().map(r -> r.name() + ":" + r.kind()).toList(), rows.toString());
        assertEquals(List.of(1, 3), rows.stream().map(StructureSymbol::line).toList());
        assertTrue(msgs.get(0).startsWith("Outline build.gradle: 2 entries"), msgs.toString());
    }

    @Test
    @DisplayName("another file's outline reads the project's build script from disk")
    void outlineReadsFromDisk(@TempDir Path root) throws Exception {
        javaDocOf(root);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 5);
        List<StructureSymbol> rows = structures.get(root.resolve("build.gradle").toString());
        assertEquals(List.of("java:plugin"),
                rows.stream().map(r -> r.name() + ":" + r.kind()).toList(), rows.toString());
    }

    @Test
    @DisplayName("a project that has both files outlines build.gradle, not the settings file")
    void outlinePrefersTheBuildScript(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'demo'\n",
                StandardCharsets.UTF_8);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 5);
        assertEquals(List.of(root.resolve("build.gradle").toString()),
                new ArrayList<>(structures.keySet()),
                "a settings file declares nothing worth outlining, so it must win the root "
                        + "lookup but never the outline");
    }

    @Test
    @DisplayName("a settings-only project still outlines its settings file")
    void outlineFallsBackToSettings(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Files.delete(root.resolve("build.gradle"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'demo'\n",
                StandardCharsets.UTF_8);
        GradleBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 5);
        assertEquals(List.of(root.resolve("settings.gradle").toString()),
                new ArrayList<>(structures.keySet()));
    }

    @Test
    @DisplayName("with no script open and none on disk the outline says so")
    void outlineWithoutAScript(@TempDir Path root) throws IOException {
        write(root, "App.java", "class App {\n}\n");
        GradleBuildTools tools = wire(root, "App.java", "class App {\n}\n");
        act(tools, 5);
        assertTrue(structures.isEmpty());
        assertTrue(msgs.get(0).contains("Outline Build File: the document is not a Gradle"
                + " build script and the project has none to read"), msgs.toString());
    }

    @Test
    @DisplayName("an unrecognisable build script outlines to nothing rather than lying")
    void outlineEmptyScript(@TempDir Path root) throws Exception {
        javaDocOf(root);
        Files.writeString(root.resolve("build.gradle"), "// nothing here yet\n",
                StandardCharsets.UTF_8);
        GradleBuildTools tools = wire(root, "build.gradle", "// nothing here yet\n");
        act(tools, 5);
        assertTrue(structures.get(root.resolve("build.gradle").toString()).isEmpty());
        assertTrue(msgs.get(0).startsWith("Outline build.gradle: nothing recognised"),
                msgs.toString());
    }

    // -- helpers ---------------------------------------------------------------

    private static Path write(Path dir, String name, String text) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }
}
