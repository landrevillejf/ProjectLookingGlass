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
import org.jdesktop.lg3d.apps.texteditor.ext.BuildTask;
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
 * Headless tests for the Maven extension. Same contract as
 * {@link GradleBuildToolsTest}: a synchronous fake {@link BuildTools.Runner}
 * records argv and replays the verbatim logs captured from Maven 3.9.11, so no
 * test boots a Maven JVM. The projects are real temp trees because the walk-up,
 * wrapper and {@code target/classes} rules all probe the disk.
 */
class MavenBuildToolsTest {

    /** A POM whose every outline row, and the run action's main class, is pinned. */
    private static final String POM = String.join("\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<project>",
            "  <modelVersion>4.0.0</modelVersion>",
            "  <groupId>com.acme</groupId>",
            "  <artifactId>demo-app</artifactId>",
            "  <version>1.2.3</version>",
            "  <properties>",
            "    <exec.mainClass>com.acme.Main</exec.mainClass>",
            "  </properties>",
            "  <dependencies>",
            "    <dependency>",
            "      <groupId>junit</groupId>",
            "      <artifactId>junit</artifactId>",
            "      <version>4.13.2</version>",
            "      <scope>test</scope>",
            "    </dependency>",
            "  </dependencies>",
            "  <build>",
            "    <plugins>",
            "      <plugin>",
            "        <artifactId>maven-jar-plugin</artifactId>",
            "      </plugin>",
            "    </plugins>",
            "  </build>",
            "</project>",
            "");

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
    private List<String> taskTitles;
    private List<List<BuildTask>> taskBatches;
    private FakeRunner runner;
    private String[] doc;

    /** Builds the provider against a document, granted every permission. */
    private MavenBuildTools wire(Path root, String fileName, String text) {
        return wire(root, fileName, text, EnumSet.allOf(TextEditorPermission.class));
    }

    private MavenBuildTools wire(Path root, String fileName, String text,
                                 EnumSet<TextEditorPermission> granted) {
        MavenBuildTools tools = new MavenBuildTools();
        msgs = new ArrayList<>();
        consoleTitles = new ArrayList<>();
        consoleBodies = new ArrayList<>();
        reportPaths = new ArrayList<>();
        reports = new ArrayList<>();
        clearedPaths = new ArrayList<>();
        structures = new LinkedHashMap<>();
        taskTitles = new ArrayList<>();
        taskBatches = new ArrayList<>();
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
                .showTasks((title, ts) -> {
                    taskTitles.add(title);
                    taskBatches.add(ts);
                })
                .build()));
        tools.onDocumentOpened(new DocumentContext(
                (filePath == null) ? null : filePath.toString(),
                (filePath == null) ? null : fileName, text, "", s -> doc[0] = s, s -> { }));
        runner = new FakeRunner();
        tools.setRunnerForTesting(runner);
        return tools;
    }

    /** A Maven project with a wrapper, the pinned POM and one Java source. */
    private void mavenDocOf(Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), POM, StandardCharsets.UTF_8);
        Files.createFile(root.resolve("mvnw"));
        Path src = root.resolve("src").resolve("main").resolve("java")
                .resolve("com").resolve("acme").resolve("App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "class App {\n}\n", StandardCharsets.UTF_8);
    }

    private void act(MavenBuildTools tools, int action) {
        tools.toolbarContributions().get(action).getAction().run();
    }

    // -- SPI ----------------------------------------------------------------

    @Test
    @DisplayName("the manifest declares the Build category and Maven half of the Ctrl+Alt+Shift family")
    void manifestAndContributions() {
        MavenBuildTools tools = new MavenBuildTools();
        TextEditorManifest m = tools.manifest();
        assertEquals("lg3d.maven-build", m.getId());
        assertEquals("Maven Build", m.getName());
        assertEquals("Build", tools.category());
        assertTrue(m.getPermissions().containsAll(List.of(TextEditorPermission.READ,
                TextEditorPermission.FILE_IO, TextEditorPermission.TOOLBAR,
                TextEditorPermission.DIAGNOSE)), m.getPermissions().toString());
        assertFalse(m.getPermissions().contains(TextEditorPermission.WRITE),
                "a build never edits the document");

        List<ToolbarContribution> c = tools.toolbarContributions();
        assertEquals(6, c.size());
        assertEquals(List.of("Compile", "Run Tests", "Package", "Run Application",
                "Clean", "Outline POM"),
                c.stream().map(ToolbarContribution::getLabel).toList());
        assertEquals(List.of("control alt shift E", "control alt shift I",
                "control alt shift P", "control alt shift W", "control alt shift Q"),
                c.subList(0, 5).stream().map(ToolbarContribution::getAccelerator).toList());
        assertEquals("", c.get(5).getAccelerator(), "outlining stays unbound");
    }

    // -- the goal actions ----------------------------------------------------

    @Test
    @DisplayName("compile runs the project's own wrapper in batch mode, in the project directory")
    void compileRunsTheGoalInBatchMode(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(0, String.join("\n",
                "[INFO] Compiling 1 source file with javac [debug release 21] to target/classes",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] BUILD SUCCESS",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] Total time:  0.964 s"));

        act(tools, 0);

        assertEquals(List.of(root.resolve("mvnw").toString(), "-B",
                "--no-transfer-progress", "compile"), runner.commands.get(0));
        assertEquals(List.of(root), runner.workDirs, "the build runs in the project root");
        assertEquals(List.of("mvn compile output"), consoleTitles);
        assertEquals("mvn compile \u2014 BUILD SUCCESS (0.964 s)", msgs.get(1));
        assertEquals("class App {\n}\n", doc[0], "the document is never touched by a build");
        assertTrue(reportPaths.isEmpty(), msgs.toString());
        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                clearedPaths, "a green build wipes the stale squiggles");
    }

    @Test
    @DisplayName("each action names its own lifecycle goal")
    void goalPerAction(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 0);
        act(tools, 1);
        act(tools, 2);
        act(tools, 4);
        assertEquals(List.of("compile", "test", "package", "clean"),
                runner.commands.stream().map(c -> c.get(3)).toList());
    }

    @Test
    @DisplayName("a compile failure files Maven's own [ERROR] line, column included")
    void compileFailureFilesDiagnostics(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Path broken = write(root.resolve("src/main/java/com/acme"), "Broken.java",
                "class Broken {\n  int x = ;\n}\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, String.join("\n",
                "[INFO] --- compiler:3.11.0:compile (default-compile) @ demo-app ---",
                "[ERROR] COMPILATION ERROR : ",
                "[ERROR] " + broken + ":[3,11] illegal start of expression",
                "[INFO] 1 error",
                "[INFO] BUILD FAILURE",
                "[INFO] Total time:  0.983 s",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-compiler-plugin:3.11.0:compile (default-compile)"
                        + " on project demo-app: Compilation failure",
                "[ERROR] " + broken + ":[3,11] illegal start of expression",
                "[ERROR] -> [Help 1]",
                "[ERROR] To see the full stack trace of the errors, re-run Maven"
                        + " with the -e switch."));

        act(tools, 0);

        assertEquals(1, reports.size());
        List<Diagnostic> ds = reports.get(0);
        assertEquals(1, ds.size(), "Maven prints the error twice: " + ds);
        assertEquals(broken.toString(), ds.get(0).path());
        assertEquals(3, ds.get(0).line());
        assertEquals(11, ds.get(0).col());
        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                reportPaths);
        assertEquals("mvn compile \u2014 BUILD FAILURE (0.983 s): maven-compiler-plugin"
                + " compile failed \u2014 Compilation failure \u2014"
                + " 1 error(s), 0 warning(s) in Problems", msgs.get(1));
    }

    @Test
    @DisplayName("a green build clears the stale findings instead of leaving squiggles")
    void successClearsDiagnostics(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(0, "[INFO] BUILD SUCCESS");

        act(tools, 0);

        assertEquals(List.of(root.resolve("src/main/java/com/acme/App.java").toString()),
                clearedPaths, clearedPaths.toString());
        assertTrue(reports.isEmpty());
    }

    @Test
    @DisplayName("clean is console-only: deleting target/ produces no findings worth filing")
    void cleanIsConsoleOnly(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, "[INFO] BUILD FAILURE\n[INFO] Total time:  0.5 s");

        act(tools, 4);

        assertEquals(1, runner.commands.size());
        assertTrue(reports.isEmpty() && clearedPaths.isEmpty());
        assertEquals(List.of("mvn clean output"), consoleTitles);
    }

    @Test
    @DisplayName("a surefire failure is filed on its test source, in Maven's own words")
    void testFailureFilesTheTestSource(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Path test = write(root.resolve("src/test/java/com/acme"), "AppTest.java",
                "class AppTest {\n}\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(1, String.join("\n",
                "[INFO] --- surefire:3.0.0:test (default-test) @ demo-app ---",
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0,"
                        + " Time elapsed: 0.064 s <<< FAILURE! - in com.acme.AppTest",
                "[ERROR] Failures: ",
                "[ERROR]   AppTest.fails:5 expected mismatch expected:<1> but was:<2>",
                "[INFO] BUILD FAILURE",
                "[INFO] Total time:  2.522 s",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-surefire-plugin:3.0.0:test (default-test)"
                        + " on project demo-app: There are test failures."));

        act(tools, 1);

        assertEquals(1, reports.get(0).size());
        Diagnostic d = reports.get(0).get(0);
        assertEquals(test.toString(), d.path());
        assertEquals(5, d.line());
        assertEquals("test failed \u2014 AppTest.fails: expected mismatch"
                + " expected:<1> but was:<2>", d.message());
        assertEquals("mvn test \u2014 BUILD FAILURE (2.522 s): maven-surefire-plugin"
                + " test failed \u2014 There are test failures. \u2014"
                + " 1 error(s), 0 warning(s) in Problems", msgs.get(1));
    }

    // -- run application -----------------------------------------------------

    @Test
    @DisplayName("run compiles with Maven, then launches the POM's main class with plain java")
    void runChainsCompileThenJava(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Path mainClass = write(root.resolve("target/classes/com/acme"), "Main.class", "cafe\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.replies.add(new Toolchain.Result(0,
                "[INFO] Compiling 1 source file with javac to target/classes\n"
                        + "[INFO] BUILD SUCCESS"));
        runner.fallback = new Toolchain.Result(0, "hello from Main\n");

        act(tools, 3);

        assertEquals(2, runner.commands.size(), runner.commands.toString());
        assertEquals(List.of("compile"), runner.commands.get(0).subList(3, 4));
        List<String> java = runner.commands.get(1);
        assertTrue(java.get(0).endsWith("java"), java.toString());
        assertEquals(List.of("-cp", root.resolve("target/classes").toString(),
                "com.acme.Main"), java.subList(1, 4));
        assertEquals(List.of(root, root), runner.workDirs);
        assertTrue(Files.isRegularFile(mainClass));
        assertEquals(List.of("mvn run output"), consoleTitles);
        assertEquals("Running com.acme.Main\u2026", msgs.get(1));
        assertEquals("mvn run \u2014 OK", msgs.get(2));
    }

    @Test
    @DisplayName("a failed compile stops the run: the launch never happens")
    void runStopsWhenTheCompileFails(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Path broken = write(root.resolve("src/main/java/com/acme"), "Broken.java", "x\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.replies.add(new Toolchain.Result(1, String.join("\n",
                "[ERROR] " + broken + ":[1,1] illegal start of expression",
                "[INFO] BUILD FAILURE",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-compiler-plugin:3.11.0:compile (default-compile)"
                        + " on project demo-app: Compilation failure")));

        act(tools, 3);

        assertEquals(1, runner.commands.size(), "no java launch after a failed compile");
        assertEquals(1, reports.get(0).size());
        assertEquals(broken.toString(), reports.get(0).get(0).path());
        assertTrue(msgs.get(1).startsWith("mvn compile \u2014"), msgs.toString());
    }

    @Test
    @DisplayName("a main class the compile did not produce is reported, not launched")
    void runRefusesWhenTheClassFileIsMissing(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.fallback = new Toolchain.Result(0, "[INFO] BUILD SUCCESS");

        act(tools, 3);

        assertEquals(1, runner.commands.size());
        assertTrue(msgs.get(1).startsWith("mvn run: com.acme.Main is not in target/classes"),
                msgs.toString());
    }

    @Test
    @DisplayName("a POM that declares no main class says what to add")
    void runGuardsWithoutAMainClass(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<project>\n  <artifactId>demo-app</artifactId>\n</project>\n",
                StandardCharsets.UTF_8);
        Files.createFile(root.resolve("mvnw"));
        MavenBuildTools tools = wire(root, "pom.xml",
                "<project>\n  <artifactId>demo-app</artifactId>\n</project>\n");

        act(tools, 3);

        assertTrue(runner.commands.isEmpty());
        assertTrue(msgs.get(0).contains("the POM declares no main class \u2014 add an"
                + " <exec.mainClass> property or a <mainClass> to the jar plugin"
                + " configuration"), msgs.toString());
    }

    @Test
    @DisplayName("an unsaved POM edit is the main class that gets launched")
    void runHonoursAnUnsavedPomEdit(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        String edited = POM.replace("<exec.mainClass>com.acme.Main</exec.mainClass>",
                "<exec.mainClass>com.acme.Other</exec.mainClass>");
        write(root.resolve("target/classes/com/acme"), "Other.class", "cafe\n");
        MavenBuildTools tools = wire(root, "pom.xml", edited);

        act(tools, 3);

        assertEquals(2, runner.commands.size(), runner.commands.toString());
        assertEquals("com.acme.Other", runner.commands.get(1).get(3));
    }

    @Test
    @DisplayName("a main that never exits is killed at the limit, and the line says so")
    void timedOutRun(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        write(root.resolve("target/classes/com/acme"), "Main.class", "cafe\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        runner.replies.add(new Toolchain.Result(0, "[INFO] BUILD SUCCESS"));
        runner.fallback = new Toolchain.Result(124, "[timed out after 120s \u2014 killed]");

        act(tools, 3);

        assertEquals("mvn run \u2014 still running when the time limit passed; killed",
                msgs.get(2));
    }

    // -- the guards -----------------------------------------------------------

    @Test
    @DisplayName("outside a Maven project nothing is spawned, and the line explains why")
    void noProject(@TempDir Path root) throws IOException {
        write(root, "App.java", "class App {\n}\n");
        MavenBuildTools tools = wire(root, "App.java", "class App {\n}\n");
        act(tools, 0);
        assertTrue(runner.commands.isEmpty());
        assertTrue(msgs.get(0).contains("no pom.xml within " + BuildTools.MAX_WALK_UP
                + " folders above"), msgs.toString());
        act(tools, 3);
        assertTrue(msgs.get(1).startsWith("mvn run: no pom.xml within"), msgs.toString());
    }

    @Test
    @DisplayName("a project with neither a wrapper nor Maven on PATH is reported, not crashed")
    void noLauncher(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Files.delete(root.resolve("mvnw"));
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        tools.setLauncherForTesting(r -> Optional.empty());
        act(tools, 0);
        assertTrue(runner.commands.isEmpty());
        assertTrue(msgs.get(0).contains("Maven is not available \u2014 no ./mvnw in " + root
                + " and no mvn on PATH"), msgs.toString());
        act(tools, 3);
        assertTrue(msgs.get(1).startsWith("mvn run: Maven is not available"), msgs.toString());
    }

    @Test
    @DisplayName("revoked permissions keep the build's output and findings off the editor surfaces")
    void permissionGating(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        Path broken = write(root.resolve("src/main/java/com/acme"), "Broken.java", "x\n");
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n",
                EnumSet.of(TextEditorPermission.READ, TextEditorPermission.TOOLBAR));
        runner.fallback = new Toolchain.Result(1,
                broken + ":[3,11] illegal start of expression\n[INFO] BUILD FAILURE");

        act(tools, 0);

        assertEquals(1, runner.commands.size(), "the build still ran");
        assertTrue(consoleBodies.isEmpty(), "showOutput is FILE_IO-gated");
        assertTrue(reports.isEmpty(), "reportDiagnostics is DIAGNOSE-gated");
        assertEquals(2, msgs.size(), "the status line is always allowed");
    }

    // -- outlining -------------------------------------------------------------

    @Test
    @DisplayName("the open POM is outlined from the buffer, unsaved edits included")
    void outlinePomFromTheDocument(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "pom.xml", POM);
        act(tools, 5);
        List<StructureSymbol> rows = structures.get(root.resolve("pom.xml").toString());
        assertEquals(List.of("com.acme:group id", "demo-app:artifact id", "1.2.3:version",
                "exec.mainClass = com.acme.Main:property",
                "junit:junit:4.13.2 [test]:dependency", "maven-jar-plugin:plugin"),
                rows.stream().map(r -> r.name() + ":" + r.kind()).toList(), rows.toString());
        assertEquals(List.of(4, 5, 6, 8, 11, 20),
                rows.stream().map(StructureSymbol::line).toList());
        assertTrue(msgs.get(0).startsWith("Outline pom.xml: 6 entries"), msgs.toString());
    }

    @Test
    @DisplayName("another file's outline reads the project's POM from disk")
    void outlineReadsThePomFromDisk(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");
        act(tools, 5);
        List<StructureSymbol> rows = structures.get(root.resolve("pom.xml").toString());
        assertEquals(6, rows.size(), rows.toString());
    }

    @Test
    @DisplayName("with no POM open and none on disk the outline says so")
    void outlineWithoutAPom(@TempDir Path root) throws IOException {
        write(root, "App.java", "class App {\n}\n");
        MavenBuildTools tools = wire(root, "App.java", "class App {\n}\n");
        act(tools, 5);
        assertTrue(structures.isEmpty());
        assertTrue(msgs.get(0).contains("Outline POM: the document is not a pom.xml"
                + " and the project has none to read"), msgs.toString());
    }

    @Test
    @DisplayName("a POM with nothing in it yet outlines to nothing rather than lying")
    void outlineEmptyPom(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project>\n</project>\n",
                StandardCharsets.UTF_8);
        MavenBuildTools tools = wire(root, "pom.xml", "<project>\n</project>\n");
        act(tools, 5);
        assertTrue(structures.get(root.resolve("pom.xml").toString()).isEmpty());
        assertTrue(msgs.get(0).startsWith("Outline pom.xml: no entries"), msgs.toString());
    }

    // -- the task tree ---------------------------------------------------------

    @Test
    @DisplayName("opening a file in a Maven project publishes its lifecycle task tree, wired to run")
    void publishesTaskTreeOnOpen(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        wire(root, "src/main/java/com/acme/App.java", "class App {\n}\n");

        assertEquals(1, taskBatches.size(), "one publish on open");
        assertEquals(root.getFileName().toString(), taskTitles.get(0));
        List<BuildTask> tasks = taskBatches.get(0);
        assertEquals(List.of("clean", "validate", "compile", "test", "package",
                        "verify", "install", "site", "deploy"),
                tasks.stream().map(BuildTask::name).toList());
        assertTrue(tasks.stream().allMatch(BuildTask::isRunnable), "every goal is wired to run");

        // Activating a leaf runs it through the same path as the toolbar action.
        BuildTask compile = tasks.stream()
                .filter(t -> t.name().equals("compile")).findFirst().orElseThrow();
        runner.fallback = new Toolchain.Result(0, "[INFO] BUILD SUCCESS");
        compile.action().run();
        assertEquals(List.of(root.resolve("mvnw").toString(), "-B",
                "--no-transfer-progress", "compile"), runner.commands.get(0));
        assertEquals(List.of(root), runner.workDirs);
        assertEquals(List.of("mvn compile output"), consoleTitles);
    }

    @Test
    @DisplayName("editing the POM re-publishes the tree; another file only does on save")
    void republishesOnPomChange(@TempDir Path root) throws Exception {
        mavenDocOf(root);
        MavenBuildTools tools = wire(root, "pom.xml", POM);
        assertEquals(1, taskBatches.size());
        assertEquals(9, taskBatches.get(0).size());

        // Editing the open POM re-publishes (the phase vocabulary is fixed).
        tools.onDocumentChanged(new DocumentContext(
                root.resolve("pom.xml").toString(), "pom.xml", POM, "", s -> { }, s -> { }));
        assertEquals(2, taskBatches.size(), "a POM edit re-publishes");

        // Editing an ordinary source file does not re-scan the POM.
        tools.onDocumentChanged(new DocumentContext(
                root.resolve("src/main/java/com/acme/App.java").toString(), "App.java",
                "class App {\n}\n", "", s -> { }, s -> { }));
        assertEquals(2, taskBatches.size(), "a non-POM edit does not re-publish");

        // Saving re-publishes, reading the POM from disk when the doc is not one.
        tools.onDocumentSaved(new DocumentContext(
                root.resolve("src/main/java/com/acme/App.java").toString(), "App.java",
                "class App {\n}\n", "", s -> { }, s -> { }));
        assertEquals(3, taskBatches.size(), "a save re-publishes");
        assertEquals(9, taskBatches.get(2).size());
    }

    @Test
    @DisplayName("a document outside any Maven project publishes no task tree")
    void noTaskTreeOutsideAProject(@TempDir Path root) throws Exception {
        write(root.resolve("src"), "App.java", "class App {\n}\n");
        wire(root, "src/App.java", "class App {\n}\n");
        assertTrue(taskBatches.isEmpty(), "nothing is published without a project");
    }

    // -- helpers ---------------------------------------------------------------

    private static Path write(Path dir, String name, String text) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }
}
