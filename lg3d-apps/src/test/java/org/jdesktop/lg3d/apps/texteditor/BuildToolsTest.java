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
import java.util.List;
import java.util.Optional;
import org.jdesktop.lg3d.apps.texteditor.ext.BuildTask;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the shared build engine. Every parsing rule is asserted
 * against output captured from the real tools on a real project (Maven 3.9.11,
 * Gradle 8.14 through the wrapper, OpenJDK 21) — the log strings below are
 * verbatim from those runs, with the absolute paths re-based onto a temp
 * directory so the file-resolution rules are exercised against files that
 * really exist.
 */
class BuildToolsTest {

    // -- project and launcher discovery ------------------------------------

    @Test
    @DisplayName("the project root is the nearest ancestor holding a marker, and the wrapper wins")
    void findsProjectAndWrapper(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), "apply plugin: 'java'\n");
        Path wrapper = Files.createFile(root.resolve("gradlew"));
        Path deep = Files.createDirectories(
                root.resolve("src").resolve("main").resolve("java").resolve("com").resolve("acme"));

        assertEquals(Optional.of(root.toAbsolutePath().normalize()),
                BuildTools.projectRoot(deep, BuildTools.GRADLE_MARKERS));
        assertEquals(Optional.of(wrapper), BuildTools.wrapper(root, "gradlew"));
        assertTrue(BuildTools.wrapper(root, "mvnw").isEmpty());
        // The wrapper is preferred, so the PATH lookup never runs here.
        assertEquals(Optional.of(wrapper),
                BuildTools.launcher(root, "gradlew", "definitely-not-a-real-tool"));
    }

    @Test
    @DisplayName("no marker within the walk limit means no project")
    void givesUpBeyondTheWalkLimit(@TempDir Path root) throws Exception {
        Path deep = root;
        for (int i = 0; i < BuildTools.MAX_WALK_UP + 2; i++) {
            deep = Files.createDirectories(deep.resolve("nested" + i));
        }
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        assertTrue(BuildTools.projectRoot(deep, BuildTools.MAVEN_MARKERS).isEmpty(),
                "a pom " + (BuildTools.MAX_WALK_UP + 2) + " levels up is out of reach");
    }

    @Test
    @DisplayName("build-file recognition keys on the exact file name")
    void recognisesBuildFiles() {
        assertTrue(BuildTools.isBuildFile("build.gradle", BuildTools.GRADLE_MARKERS));
        assertTrue(BuildTools.isBuildFile("settings.gradle.kts", BuildTools.GRADLE_MARKERS));
        assertTrue(BuildTools.isBuildFile("pom.xml", BuildTools.MAVEN_MARKERS));
        assertFalse(BuildTools.isBuildFile("my-pom.xml", BuildTools.MAVEN_MARKERS));
        assertFalse(BuildTools.isBuildFile("App.java", BuildTools.GRADLE_MARKERS));
        assertFalse(BuildTools.isBuildFile(null, BuildTools.MAVEN_MARKERS));
    }

    @Test
    @DisplayName("the search starts in the saved document's directory, and in the working directory otherwise")
    void searchStartFollowsTheDocument(@TempDir Path dir) {
        assertEquals(Optional.of(dir),
                BuildTools.searchStart(dir.resolve("App.java").toString()));
        // An unsaved document still has a starting point: the process's own tree.
        assertTrue(BuildTools.searchStart("").isPresent());
        assertTrue(BuildTools.searchStart(null).isPresent());
    }

    // -- command builders and environment ----------------------------------

    @Test
    @DisplayName("the argv is exactly what the tools need to stay machine-readable")
    void commandBuilders() {
        Path gradlew = Path.of("/p/gradlew");
        Path mvn = Path.of("/usr/bin/mvn");
        assertEquals(List.of("/p/gradlew", "--console=plain", "build"),
                BuildTools.gradleCommand(gradlew, List.of("build")));
        assertEquals(List.of("/p/gradlew", "--console=plain", "test", "--tests", "AppTest"),
                BuildTools.gradleCommand(gradlew, List.of("test", "--tests", "AppTest")));
        assertEquals(List.of("/usr/bin/mvn", "-B", "--no-transfer-progress", "package"),
                BuildTools.mavenCommand(mvn, List.of("package")));
        assertEquals(List.of("/jdk/bin/java", "-cp", "/p/target/classes", "com.acme.App"),
                BuildTools.javaClasspathRunCommand(Path.of("/jdk/bin/java"),
                        Path.of("/p/target/classes"), "com.acme.App"));
    }

    @Test
    @DisplayName("the build environment is dumb-terminal and never overrides a user's JAVA_HOME")
    void buildEnvironment() {
        var env = BuildTools.buildEnvironment();
        assertEquals("dumb", env.get("TERM"));
        String userHome = System.getenv("JAVA_HOME");
        if (userHome == null) {
            assertEquals(System.getProperty("java.home"), env.get("JAVA_HOME"),
                    "an unset JAVA_HOME is pinned to the JDK the editor runs on");
        } else {
            assertFalse(env.containsKey("JAVA_HOME"),
                    "the user's own JAVA_HOME is never overwritten");
        }
    }

    // -- Gradle log interpretation (recorded output) -----------------------

    @Test
    @DisplayName("a Gradle compile error is filed once, though the log prints it twice")
    void gradleCompileErrorIsDeduplicated(@TempDir Path root) throws Exception {
        Path broken = write(root, "src/main/java/com/acme/Broken.java", "class Broken {\n}\n");
        // Verbatim shape of `gradlew build` on a broken source: the live javac
        // block, then the same block indented under "* What went wrong:".
        String log = String.join("\n",
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
                "    int x = ;",
                "            ^",
                "  1 error",
                "",
                "* Try:",
                "> Check your code and dependencies to fix the compilation error(s)",
                "",
                "BUILD FAILED in 7s",
                "3 actionable tasks: 3 executed");

        List<Diagnostic> ds = BuildTools.parseDiagnostics(log, root);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(broken.toString(), ds.get(0).path());
        assertEquals(3, ds.get(0).line());
        assertEquals(Diagnostic.Kind.ERROR, ds.get(0).kind());
        assertEquals("illegal start of expression", ds.get(0).message());
        assertEquals(Optional.of("BUILD FAILED in 7s"), BuildTools.verdict(log));
        assertEquals(Optional.of("Execution failed for task ':compileJava'."
                + " \u2014 Compilation failed; see the compiler output below."),
                BuildTools.failureHeadline(log));
    }

    @Test
    @DisplayName("a Gradle test failure resolves the bare file name to its source")
    void gradleTestFailure(@TempDir Path root) throws Exception {
        Path test = write(root, "src/test/java/com/acme/AppTest.java", "class AppTest {\n}\n");
        String log = String.join("\n",
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
                "BUILD FAILED in 7s");

        List<Diagnostic> ds = BuildTools.parseDiagnostics(log, root);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(test.toString(), ds.get(0).path());
        assertEquals(5, ds.get(0).line());
        assertEquals("test failed — java.lang.AssertionError", ds.get(0).message());
        assertTrue(BuildTools.testReportUrl(log).get().endsWith("index.html"));
        assertTrue(BuildTools.failureHeadline(log).get().contains("There were failing tests"));
    }

    @Test
    @DisplayName("a missing application plugin says so in the project's own words")
    void gradleMissingRunTask(@TempDir Path root) {
        String log = String.join("\n",
                "FAILURE: Build failed with an exception.",
                "",
                "* What went wrong:",
                "Task 'run' not found in root project 'demo-app'.",
                "",
                "* Try:",
                "> Run gradlew tasks to get a list of available tasks.",
                "",
                "BUILD FAILED in 6s");
        assertEquals(Optional.of("Task 'run' not found in root project 'demo-app'."),
                BuildTools.failureHeadline(log));
        assertTrue(BuildTools.parseDiagnostics(log, root).isEmpty());
    }

    @Test
    @DisplayName("a successful Gradle build has no headline and its own verdict")
    void gradleSuccess(@TempDir Path root) {
        String log = "> Task :compileJava\n\nBUILD SUCCESSFUL in 7s\n";
        assertEquals(Optional.of("BUILD SUCCESSFUL in 7s"), BuildTools.verdict(log));
        assertTrue(BuildTools.failureHeadline(log).isEmpty());
        assertTrue(BuildTools.parseDiagnostics(log, root).isEmpty());
    }

    // -- Maven log interpretation (recorded output) ------------------------

    @Test
    @DisplayName("a Maven compiler error keeps its column and the goal line becomes the headline")
    void mavenCompileError(@TempDir Path root) throws Exception {
        Path broken = write(root, "src/main/java/com/acme/Broken.java", "class Broken {\n}\n");
        String log = String.join("\n",
                "[INFO] Compiling 2 source files with javac [debug release 21] to target/classes",
                "[INFO] -------------------------------------------------------------",
                "[ERROR] COMPILATION ERROR :",
                "[INFO] -------------------------------------------------------------",
                "[ERROR] " + broken + ":[3,11] illegal start of expression",
                "[INFO] 1 error",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] BUILD FAILURE",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] Total time:  0.983 s",
                "[INFO] ------------------------------------------------------------------------",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-compiler-plugin:3.11.0:compile (default-compile)"
                        + " on project demo-app: Compilation failure",
                "[ERROR] " + broken + ":[3,11] illegal start of expression",
                "[ERROR] -> [Help 1]",
                "[ERROR] ",
                "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.");

        List<Diagnostic> ds = BuildTools.parseDiagnostics(log, root);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(3, ds.get(0).line());
        assertEquals(11, ds.get(0).col());
        assertEquals(Diagnostic.Kind.ERROR, ds.get(0).kind());
        assertEquals(Optional.of("BUILD FAILURE (0.983 s)"), BuildTools.verdict(log));
        assertEquals(Optional.of("maven-compiler-plugin compile failed"
                + " \u2014 Compilation failure"), BuildTools.failureHeadline(log));
    }

    @Test
    @DisplayName("a Maven surefire summary line resolves the class to its test source")
    void mavenTestFailure(@TempDir Path root) throws Exception {
        Path test = write(root, "src/test/java/com/acme/AppTest.java", "class AppTest {\n}\n");
        String log = String.join("\n",
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0,"
                        + " Time elapsed: 0.064 s <<< FAILURE! - in com.acme.AppTest",
                "[ERROR] com.acme.AppTest.fails  Time elapsed: 0.015 s  <<< FAILURE!",
                "java.lang.AssertionError: expected mismatch expected:<1> but was:<2>",
                "\tat org.junit.Assert.fail(Assert.java:89)",
                "\tat com.acme.AppTest.fails(AppTest.java:5)",
                "[INFO] ",
                "[ERROR] Failures: ",
                "[ERROR]   AppTest.fails:5 expected mismatch expected:<1> but was:<2>",
                "[INFO] ",
                "[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0",
                "[INFO] ------------------------------------------------------------------------",
                "[INFO] BUILD FAILURE",
                "[INFO] Total time:  2.522 s",
                "[ERROR] Failed to execute goal org.apache.maven.plugins:"
                        + "maven-surefire-plugin:3.0.0:test (default-test)"
                        + " on project demo-app: There are test failures.");

        List<Diagnostic> ds = BuildTools.parseDiagnostics(log, root);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(test.toString(), ds.get(0).path());
        assertEquals(5, ds.get(0).line());
        assertEquals("test failed — AppTest.fails: expected mismatch expected:<1> but was:<2>",
                ds.get(0).message());
        assertEquals(Optional.of("maven-surefire-plugin test failed"
                + " \u2014 There are test failures."), BuildTools.failureHeadline(log));
    }

    @Test
    @DisplayName("an unresolvable test class still files a finding against the project")
    void mavenTestFailureWithMissingSource(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n");
        String log = "[ERROR]   GhostTest.fails:5 expected mismatch";
        List<Diagnostic> ds = BuildTools.parseDiagnostics(log, root);
        assertEquals(1, ds.size(), ds.toString());
        assertEquals(root.getFileName().toString(), Path.of(ds.get(0).path()).getFileName().toString());
        assertEquals(5, ds.get(0).line());
    }

    @Test
    @DisplayName("a warning-severity line is a WARNING, and a file that is not on disk is dropped")
    void severityAndMissingFiles(@TempDir Path root) throws Exception {
        Path real = write(root, "src/main/java/App.java", "class App {\n}\n");
        List<Diagnostic> warns = BuildTools.parseDiagnostics(
                "[WARNING] " + real + ":2: warning: [deprecation] old() is deprecated", root);
        assertEquals(1, warns.size());
        assertEquals(Diagnostic.Kind.WARNING, warns.get(0).kind());

        assertTrue(BuildTools.parseDiagnostics(
                "[ERROR] " + root.resolve("Gone.java") + ":3:12: cannot find symbol", root).isEmpty(),
                "a finding for a file that does not exist cannot be painted");
    }

    @Test
    @DisplayName("both Kotlin compiler shapes are read out of a Gradle or Maven log")
    void kotlinCompilerShapes(@TempDir Path root) throws Exception {
        Path kt = write(root, "src/main/kotlin/Foo.kt", "fun main() {}\n");
        List<Diagnostic> classic = BuildTools.parseDiagnostics(
                kt + ":7:11: error: unresolved reference: nope", root);
        assertEquals(1, classic.size());
        assertEquals(7, classic.get(0).line());
        assertEquals(11, classic.get(0).col());
        assertEquals(Diagnostic.Kind.ERROR, classic.get(0).kind());

        List<Diagnostic> shortForm = BuildTools.parseDiagnostics(
                "e: " + kt + ": (7, 11): unresolved reference: nope", root);
        assertEquals(1, shortForm.size());
        assertEquals(7, shortForm.get(0).line());
        assertEquals("unresolved reference: nope", shortForm.get(0).message());

        assertTrue(BuildTools.parseDiagnostics(
                "w: " + kt + ": (3, 5): unused variable", root).get(0).kind()
                == Diagnostic.Kind.WARNING);
    }

    @Test
    @DisplayName("a wall of errors is capped so the Problems panel stays usable")
    void capsFindings(@TempDir Path root) throws Exception {
        Path src = write(root, "src/main/java/App.java", "class App {\n}\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            sb.append(src).append(":").append(i).append(": error: line ").append(i).append('\n');
        }
        assertEquals(BuildTools.MAX_DIAGNOSTICS,
                BuildTools.parseDiagnostics(sb.toString(), root).size());
    }

    @Test
    @DisplayName("progress and report lines are never mistaken for findings")
    void ignoresBuildNoise(@TempDir Path root) throws Exception {
        String log = String.join("\n",
                "> Task :compileJava",
                "3 actionable tasks: 3 executed",
                "See the report at: file:///tmp/x/build/reports/tests/test/index.html",
                "\tat com.acme.AppTest.fails(AppTest.java:5)",
                "[INFO] Building demo-app 1.2.3",
                "To honour the JVM settings for this build a single-use Daemon will be forked.");
        assertTrue(BuildTools.parseDiagnostics(log, root).isEmpty(), log);
    }

    // -- status line --------------------------------------------------------

    @Test
    @DisplayName("the status line repeats the tool's verdict, never a bare exit code")
    void statusLines(@TempDir Path root) throws Exception {
        Path src = write(root, "src/main/java/App.java", "class App {\n}\n");
        assertEquals("gradle build — BUILD SUCCESSFUL in 7s",
                BuildTools.statusLine("gradle build",
                        new Toolchain.Result(0, "BUILD SUCCESSFUL in 7s"), List.of()));

        String failing = src + ":2: error: ';' expected\nBUILD FAILED in 7s\n";
        List<Diagnostic> one = BuildTools.parseDiagnostics(failing, root);
        // A compiler finding is not a headline (Problems owns it), and this log
        // has no failure narrative of its own, so the verdict carries the line.
        assertEquals("gradle build \u2014 BUILD FAILED in 7s"
                        + " \u2014 1 error(s), 0 warning(s) in Problems",
                BuildTools.statusLine("gradle build", new Toolchain.Result(1, failing), one));

        assertEquals("mvn clean — failed (exit 1)",
                BuildTools.statusLine("mvn clean", new Toolchain.Result(1, "boom"), List.of()));
        assertEquals("gradle run — still running when the time limit passed; killed",
                BuildTools.statusLine("gradle run",
                        new Toolchain.Result(124, "[timed out after 120s — killed]"), List.of()));

        String longHead = "x".repeat(BuildTools.MAX_STATUS_CHARS);
        assertTrue(BuildTools.statusLine("mvn test",
                new Toolchain.Result(1, "[ERROR] " + longHead), List.of())
                .length() <= BuildTools.MAX_STATUS_CHARS, "the status line stays one line");
    }

    // -- POM outlining ------------------------------------------------------

    private static final String POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.acme</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.2.3</version>
              <packaging>jar</packaging>
              <properties>
                <maven.compiler.release>21</maven.compiler.release>
                <exec.mainClass>com.acme.App</exec.mainClass>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.4.1</version>
                  </plugin>
                </plugins>
              </build>
              <modules>
                <module>child</module>
              </modules>
            </project>
            """;

    @Test
    @DisplayName("the POM outline lists coordinates, properties, dependencies, plugins and modules with their lines")
    void pomOutline() {
        List<StructureSymbol> rows = BuildTools.pomOutline(POM);
        assertTrue(rows.contains(new StructureSymbol("com.acme", "group id", 4)), rows.toString());
        assertTrue(rows.contains(new StructureSymbol("demo-app", "artifact id", 5)), rows.toString());
        assertTrue(rows.contains(new StructureSymbol("1.2.3", "version", 6)), rows.toString());
        assertTrue(rows.contains(new StructureSymbol("jar", "packaging", 7)), rows.toString());
        assertTrue(rows.contains(new StructureSymbol(
                "maven.compiler.release = 21", "property", 9)), rows.toString());
        // A block's row points at the block's opening line, not its closing tag.
        assertTrue(rows.contains(new StructureSymbol(
                "junit:junit:4.13.2 [test]", "dependency", 13)), rows.toString());
        assertTrue(rows.contains(new StructureSymbol(
                "org.apache.maven.plugins:maven-jar-plugin:3.4.1", "plugin", 22)),
                rows.toString());
        assertTrue(rows.contains(new StructureSymbol("child", "module", 30)), rows.toString());
        // The modelVersion and the namespace are noise in an outline.
        assertTrue(rows.stream().noneMatch(r -> r.name().equals("4.0.0")), rows.toString());
    }

    @Test
    @DisplayName("a dependency of a plugin is outlined as a plugin dependency, not a project one")
    void pomOutlinePluginDependency() {
        List<StructureSymbol> rows = BuildTools.pomOutline("""
                <project>
                  <build><plugins><plugin>
                    <artifactId>my-plugin</artifactId>
                    <dependencies><dependency>
                      <groupId>lib</groupId><artifactId>lib-core</artifactId><version>1.0</version>
                    </dependency></dependencies>
                  </plugin></plugins></build>
                </project>
                """);
        assertEquals(List.of("lib:lib-core:1.0"), rows.stream()
                .filter(r -> r.kind().equals("plugin dependency"))
                .map(StructureSymbol::name).toList(), rows.toString());
    }

    @Test
    @DisplayName("a half-typed POM still outlines what was readable, and an entity is never expanded")
    void pomOutlineDegenerateInput() {
        List<StructureSymbol> truncated = BuildTools.pomOutline(
                "<project>\n  <artifactId>demo</artifactId>\n  <dependencies>\n    <dependency>\n");
        assertEquals(List.of("demo"), truncated.stream()
                .filter(r -> r.kind().equals("artifact id"))
                .map(StructureSymbol::name).toList(), truncated.toString());

        assertTrue(BuildTools.pomOutline(null).isEmpty());
        assertTrue(BuildTools.pomOutline("   ").isEmpty());
        // External-entity resolution is switched off: the XXE text must not appear.
        List<StructureSymbol> xxe = BuildTools.pomOutline("""
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY p SYSTEM "file:///etc/passwd">]>
                <project><artifactId>&p;</artifactId></project>
                """);
        assertTrue(xxe.stream().noneMatch(r -> r.name().contains("root:")), xxe.toString());
    }

    @Test
    @DisplayName("the main class comes from exec.mainClass, then from the jar plugin's manifest")
    void mainClassFromPom() {
        assertEquals(Optional.of("com.acme.App"), BuildTools.mainClassFromPom(POM));
        assertEquals(Optional.of("com.acme.Jar"), BuildTools.mainClassFromPom(
                "<project><build><plugins><plugin><archive><manifest>"
                        + "<mainClass>com.acme.Jar</mainClass></manifest></archive>"
                        + "</plugin></plugins></build></project>"));
        assertTrue(BuildTools.mainClassFromPom("<project><artifactId>x</artifactId></project>").isEmpty());
        assertTrue(BuildTools.mainClassFromPom(null).isEmpty());
        assertTrue(BuildTools.mainClassFromPom("").isEmpty());
    }

    @Test
    @DisplayName("a build file on disk is read as UTF-8, and a missing one is not an exception")
    void readBuildFile(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.write(pom, "<project>caf\u00e9</project>".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(BuildTools.readBuildFile(dir.resolve("absent.xml")).isEmpty());
        assertTrue(BuildTools.readBuildFile(dir).isEmpty(), "a directory is not a build file");
        assertTrue(BuildTools.readBuildFile(null).isEmpty());
        Path utf8 = Files.writeString(dir.resolve("build.gradle"), "name = 'caf\u00e9'");
        assertTrue(BuildTools.readBuildFile(utf8).get().contains("caf\u00e9"));
    }

    // -- Gradle build-script outlining -------------------------------------

    @Test
    @DisplayName("the Gradle outline reads plugins, repositories, dependencies and tasks with their lines")
    void gradleBuildOutline() {
        List<StructureSymbol> rows = BuildTools.gradleBuildOutline("""
                plugins {
                    id 'java-library'
                }
                apply plugin: 'application'
                repositories {
                    mavenCentral()
                }
                dependencies {
                    implementation 'org.jogamp.java3d:java3d-core:1.7.2'
                    testImplementation 'junit:junit:4.13.2'
                }
                tasks.register('smokeTest') {
                }
                group = 'org.jdesktop.lg3d'
                // a comment naming junit:junit:4.13.2 is not a dependency
                """);
        assertEquals(List.of("java-library:plugin", "application:plugin",
                        "mavenCentral:repository",
                        "org.jogamp.java3d:java3d-core:1.7.2:implementation",
                        "junit:junit:4.13.2:testImplementation",
                        "smokeTest:task",
                        "group = 'org.jdesktop.lg3d':setting"),
                rows.stream().map(r -> r.name() + ":" + r.kind()).toList(), rows.toString());
        assertEquals(List.of(2, 4, 6, 9, 10, 12, 14),
                rows.stream().map(StructureSymbol::line).toList(), rows.toString());

        assertTrue(BuildTools.gradleBuildOutline(null).isEmpty());
        assertTrue(BuildTools.gradleBuildOutline("").isEmpty());
    }

    // -- task-tree discovery -------------------------------------------------

    @Test
    @DisplayName("the Gradle task tree proposes the base lifecycle, test/run by plugin, declared tasks and modules")
    void gradleTaskTree() {
        List<BuildTask> tasks = BuildTools.gradleTaskTree("""
                plugins {
                    id 'application'
                }
                tasks.register('smokeTest') {
                }
                task legacyCopy
                """, """
                rootProject.name = 'demo'
                include ':app'
                include(":core", ":core:util")
                """);

        // The base lifecycle every build carries.
        List<String> lifecycle = tasks.stream()
                .filter(t -> t.group().equals("Lifecycle"))
                .map(BuildTask::name).toList();
        assertTrue(lifecycle.containsAll(
                List.of("build", "assemble", "check", "clean", "tasks")), lifecycle.toString());
        // 'application' implies the Java plugin (test) and adds run.
        assertTrue(lifecycle.contains("test"), lifecycle.toString());
        assertTrue(lifecycle.contains("run"), lifecycle.toString());

        // The script's own registered tasks, each with its declaration line.
        List<BuildTask> declared = tasks.stream()
                .filter(t -> t.group().equals("Declared tasks")).toList();
        assertEquals(List.of("smokeTest", "legacyCopy"),
                declared.stream().map(BuildTask::name).toList(), declared.toString());
        assertEquals(4, declared.get(0).line());
        assertEquals(6, declared.get(1).line());

        // Each included module gets its own build/test/clean branch.
        assertTrue(tasks.stream().anyMatch(t -> t.group().equals("module :app")
                && t.name().equals(":app:build")), tasks.toString());
        assertTrue(tasks.stream().anyMatch(t -> t.name().equals(":core:util:test")),
                tasks.toString());
        assertEquals(3, tasks.stream().filter(t -> t.group().equals("module :core")).count());

        // Discovery is unwired: the caller attaches the run callback.
        assertTrue(tasks.stream().noneMatch(BuildTask::isRunnable));
    }

    @Test
    @DisplayName("a Gradle script with no JVM plugin proposes no test/run, and null input is safe")
    void gradleTaskTreeWithoutPlugins() {
        List<String> lifecycle = BuildTools.gradleTaskTree("group = 'x'\n", null).stream()
                .filter(t -> t.group().equals("Lifecycle"))
                .map(BuildTask::name).toList();
        assertFalse(lifecycle.contains("test"), lifecycle.toString());
        assertFalse(lifecycle.contains("run"), lifecycle.toString());
        assertTrue(lifecycle.contains("build"), lifecycle.toString());

        // A null build script still yields the base lifecycle; null settings add no modules.
        assertFalse(BuildTools.gradleTaskTree(null, null).isEmpty());
        assertTrue(BuildTools.gradleTaskTree(null, null).stream()
                .noneMatch(t -> t.group().startsWith("module ")));
    }

    @Test
    @DisplayName("a Spring Boot project proposes the plugin-contributed bootRun/bootJar tasks")
    void gradleTaskTreeSpringBoot() {
        List<BuildTask> tasks = BuildTools.gradleTaskTree("""
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.3.0'
                    id 'io.spring.dependency-management' version '1.1.5'
                }
                """, null);
        List<BuildTask> boot = tasks.stream()
                .filter(t -> t.group().equals("Spring Boot")).toList();
        // bootRun is contributed by the plugin, never declared in the script.
        assertEquals(List.of("bootRun", "bootJar", "bootBuildImage"),
                boot.stream().map(BuildTask::name).toList(), boot.toString());
        // bootWar needs the war plugin, which this script does not apply.
        assertFalse(tasks.stream().anyMatch(t -> t.name().equals("bootWar")), tasks.toString());
        // 'java' still yields test; a JVM project also gets the base lifecycle.
        assertTrue(tasks.stream().anyMatch(t -> t.name().equals("test")), tasks.toString());
        assertTrue(tasks.stream().noneMatch(BuildTask::isRunnable));

        // With the war plugin applied, bootWar joins the set.
        assertTrue(BuildTools.gradleTaskTree("""
                plugins {
                    id 'war'
                    id 'org.springframework.boot'
                }
                """, null).stream().anyMatch(t -> t.name().equals("bootWar")));

        // Kotlin-DSL id("...") shape is read the same way.
        assertTrue(BuildTools.gradleTaskTree(
                "plugins { id(\"org.springframework.boot\") }", null).stream()
                .anyMatch(t -> t.name().equals("bootRun")));

        // The shadow plugin contributes shadowJar, deduplicated across its two ids.
        List<BuildTask> shadow = BuildTools.gradleTaskTree("""
                plugins {
                    id 'java'
                    id 'com.gradleup.shadow'
                }
                """, null).stream().filter(t -> t.name().equals("shadowJar")).toList();
        assertEquals(1, shadow.size(), shadow.toString());
        assertEquals("Shadow", shadow.get(0).group());

        // No known plugin: nothing contributed.
        assertTrue(BuildTools.gradleTaskTree("plugins { id 'java' }", null).stream()
                .noneMatch(t -> t.group().equals("Spring Boot") || t.group().equals("Shadow")));
    }

    @Test
    @DisplayName("appliesPlugin reads both the plugins block and the legacy apply form")
    void appliesPlugin() {
        assertTrue(BuildTools.appliesPlugin("plugins { id 'java' }", "java"));
        assertTrue(BuildTools.appliesPlugin("plugins { id(\"java-library\") }", "java-library"));
        assertTrue(BuildTools.appliesPlugin("apply plugin: 'application'", "application"));
        assertFalse(BuildTools.appliesPlugin("plugins { id 'java' }", "application"));
        // An unquoted core id is not matched: the plugins block requires quotes.
        assertFalse(BuildTools.appliesPlugin("id java\n", "java"));
        assertFalse(BuildTools.appliesPlugin(null, "java"));
        assertFalse(BuildTools.appliesPlugin("", "java"));
        assertFalse(BuildTools.appliesPlugin("plugins { id 'java' }", null));
        assertFalse(BuildTools.appliesPlugin("plugins { id 'java' }", ""));
    }

    @Test
    @DisplayName("gradleModules normalises includes to colon-led paths, skipping comments and duplicates")
    void gradleModules() {
        assertEquals(List.of(":app", ":core", ":core:util"), BuildTools.gradleModules("""
                rootProject.name = 'demo'
                include ':app'
                // include ':commented'
                include(":core")
                include ':core', ':core:util'
                include ':app'
                """));
        assertTrue(BuildTools.gradleModules(null).isEmpty());
        assertTrue(BuildTools.gradleModules("   ").isEmpty());
        assertTrue(BuildTools.gradleModules("rootProject.name = 'x'").isEmpty());
    }

    @Test
    @DisplayName("the Maven task tree proposes the lifecycle phases, and only for a real POM")
    void mavenTaskTree() {
        List<String> phases = BuildTools.mavenTaskTree(POM).stream()
                .map(BuildTask::name).toList();
        assertEquals(List.of("clean", "validate", "compile", "test", "package",
                "verify", "install", "site", "deploy"), phases);
        assertTrue(BuildTools.mavenTaskTree(POM).stream()
                .allMatch(t -> t.group().equals("Lifecycle")));
        assertTrue(BuildTools.mavenTaskTree(POM).stream()
                .noneMatch(BuildTask::isRunnable));

        // Not a POM: nothing proposed.
        assertTrue(BuildTools.mavenTaskTree(null).isEmpty());
        assertTrue(BuildTools.mavenTaskTree("").isEmpty());
        assertTrue(BuildTools.mavenTaskTree("<html><body>hi</body></html>").isEmpty());
    }

    // -- helpers ------------------------------------------------------------

    private static Path write(Path root, String relative, String text) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }
}
