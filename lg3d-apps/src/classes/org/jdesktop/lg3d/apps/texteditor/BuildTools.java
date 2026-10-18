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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.BuildTask;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;

/**
 * The shared engine behind {@link GradleBuildTools} and
 * {@link MavenBuildTools}: project discovery, launcher resolution, command
 * builders, environment pinning, build-log interpretation and build-file
 * outlining. Everything here is a pure {@code String}/{@code Path} function
 * except {@link #execute}-style process work, which the providers reach only
 * through the injectable {@link Runner} seam — so every rule below is
 * headless-testable and no test ever spawns Gradle or Maven.
 *
 * <p><b>The output formats are recorded, not remembered.</b> Each parsing rule
 * is matched against output captured from the real tools on a real project
 * (Maven 3.9.11, Gradle 8.14 via the wrapper, OpenJDK 21): Maven's
 * {@code [ERROR] /abs/File.java:[3,11] illegal start of expression} and
 * {@code [ERROR]   AppTest.fails:5 expected mismatch}; Gradle's
 * {@code /abs/File.java:3: error: illegal start of expression}, its indented
 * repeat inside the {@code * What went wrong:} block, the console test form
 * {@code java.lang.AssertionError at AppTest.java:5}, and the verdict markers
 * {@code BUILD SUCCESSFUL in 7s} / {@code BUILD FAILED} versus Maven's
 * {@code [INFO] BUILD SUCCESS} + {@code [INFO] Total time:}. The tests quote
 * those strings verbatim.</p>
 *
 * <p>Kotlin compiler lines are handled in the two shapes
 * {@link JavaDiagnosticsTools} already documents ({@code file.kt:L:C: error:
 * msg} and {@code e: file.kt: (L, C): msg}), since a Kotlin build fails
 * through the same Gradle/Maven log. A {@code .gradle.kts} file is scanned with
 * the same best-effort DSL scan as {@code build.gradle} — no Groovy/Kotlin
 * parser exists in the JDK, which is stated in the outline's own limitations.</p>
 */
final class BuildTools {

    /**
     * Seam over the process spawn: run {@code command} in {@code workDir} and
     * hand {@code done} the merged result, in production on the EDT after
     * executing on a virtual thread. Mirrors {@code JvmBuildTools.Runner} so the
     * build tools' actions are testable with a synchronous fake.
     */
    interface Runner {
        void run(List<String> command, Path workDir, long timeoutMs,
                 Consumer<Toolchain.Result> done);
    }

    /** Production runner: virtual thread, pinned environment, EDT delivery. */
    static final Runner ASYNC_RUNNER = (command, workDir, timeoutMs, done) ->
            Thread.ofVirtual().start(() -> {
                Toolchain.Result result = Toolchain.execute(command, workDir,
                        buildEnvironment(), timeoutMs);
                SwingUtilities.invokeLater(() -> done.accept(result));
            });

    /** Build files that make a directory a Gradle project, deepest match first. */
    static final List<String> GRADLE_MARKERS = List.of(
            "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");

    /** Build files that make a directory a Maven project. */
    static final List<String> MAVEN_MARKERS = List.of("pom.xml");

    /**
     * How far up from a document its build file may live before the search gives
     * up. Matches {@link ProjectTreePanel#MAX_WALK_UP} so the west tree and the
     * build actions agree on where a project starts.
     */
    static final int MAX_WALK_UP = ProjectTreePanel.MAX_WALK_UP;

    /** Findings cap handed to the Problems panel; a broken build prints thousands. */
    static final int MAX_DIAGNOSTICS = 100;

    /** Longest status-line message a build action writes (including the label). */
    static final int MAX_STATUS_CHARS = 220;

    /** Directories never descended into when resolving a bare file name. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "build", "target", "out", "node_modules", ".git", ".gradle", ".idea");

    /** Conventional source roots tried, in order, when a log names a bare file. */
    private static final List<String> SOURCE_ROOTS = List.of(
            "src/test/java", "src/main/java", "src/test/kotlin", "src/main/kotlin",
            "src/test/groovy", "src/main/groovy", "src");

    /** Maven/Gradle log decoration a finding line may carry. */
    private static final Pattern LOG_PREFIX =
            Pattern.compile("^\\s*\\[(?:ERROR|WARNING|WARN|INFO)\\]\\s*");

    /** {@code /abs/File.java:[3,11] illegal start of expression} (Maven compiler). */
    private static final Pattern MAVEN_LOC =
            Pattern.compile("^(.*\\.(?:java|kt|kts)):\\[(\\d+),(\\d+)\\]\\s*(.+)$");

    /** {@code /abs/File.java:3: error: illegal start of expression} (javac, kotlinc). */
    private static final Pattern PLAIN_LOC = Pattern.compile(
            "^(.*\\.(?:java|kt|kts)):(\\d+)(?::(\\d+))?:\\s*(?:(error|warning|note):\\s*)?(.+)$");

    /** {@code e: File.kt: (7, 11): Unresolved reference} (newer kotlinc). */
    private static final Pattern KOTLIN_SHORT =
            Pattern.compile("^[ew]:\\s*(.*\\.(?:kt|kts)):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(.+)$");

    /** {@code java.lang.AssertionError at AppTest.java:5} (Gradle console test line). */
    private static final Pattern GRADLE_TEST = Pattern.compile(
            "^([\\w.$]*(?:Exception|Error|Throwable)) at (?:[\\w.$]+\\.)?([\\w$-]+\\.(?:java|kt)):(\\d+)$");

    /** {@code AppTest.fails:5 expected mismatch ...} (Maven surefire summary line). */
    private static final Pattern MAVEN_TEST =
            Pattern.compile("^([\\w.$]+)\\.([\\w$<>]+):(\\d+)\\s+(.+)$");

    /** Gradle's own verdict line. */
    private static final Pattern GRADLE_VERDICT =
            Pattern.compile("^BUILD (SUCCESSFUL|FAILED)( in .+)?$");

    /** Maven's own verdict line, after the {@code [INFO]} decoration is stripped. */
    private static final Pattern MAVEN_VERDICT = Pattern.compile("^BUILD (SUCCESS|FAILURE)$");

    /** Maven's {@code [INFO] Total time:  0.964 s} line. */
    private static final Pattern MAVEN_TIME =
            Pattern.compile("^Total time:\\s+(.+)$");

    /**
     * Maven's failure sentence: {@code Failed to execute goal
     * org.apache.maven.plugins:maven-surefire-plugin:3.0.0:test (default-test)
     * on project demo-app: There are test failures.}
     */
    private static final Pattern MAVEN_GOAL = Pattern.compile(
            "^Failed to execute goal (\\S+) (?:\\([^)]*\\)\\s+)?on project [\\w.\\-]+:\\s*(.*)$");

    /** {@code <mainClass>} in the jar plugin's manifest configuration. */
    private static final Pattern MAIN_CLASS =
            Pattern.compile("<mainClass>\\s*([^<\\s][^<]*?)\\s*</mainClass>");

    /** {@code <exec.mainClass>} — the property {@code exec:java} would read. */
    private static final Pattern EXEC_MAIN_CLASS =
            Pattern.compile("<exec\\.mainClass>\\s*([^<\\s][^<]*?)\\s*</exec\\.mainClass>");

    /** Gradle's {@code > There were failing tests. See the report at: file:///...}. */
    private static final Pattern REPORT_URL =
            Pattern.compile("See the report at:\\s*(\\S+)");

    /** Maven boilerplate that never belongs in a one-line status message. */
    private static final List<String> MAVEN_NOISE = List.of(
            "To see the full stack trace", "Re-run Maven using",
            "For more information about", "[Help 1]", "MojoFailureException",
            "MojoExecutionException");

    private BuildTools() {
    }

    // ------------------------------------------------------------------
    // Project + launcher discovery
    // ------------------------------------------------------------------

    /**
     * Walks up from {@code startDir} (inclusive) to the nearest directory
     * holding one of {@code markers}. Pure given a real directory tree (tests
     * use {@code @TempDir}).
     *
     * @return the project root, empty when no marker appears within
     *         {@link #MAX_WALK_UP} levels
     */
    static Optional<Path> projectRoot(Path startDir, List<String> markers) {
        Path candidate = startDir;
        for (int i = 0; i < MAX_WALK_UP && candidate != null; i++) {
            for (String marker : markers) {
                if (Files.isRegularFile(candidate.resolve(marker))) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }

    /**
     * The project's own build launcher ({@code ./gradlew} / {@code ./mvnw}) —
     * the wrapper pins the tool version the project was built with, so it wins
     * over whatever happens to be on {@code PATH}.
     *
     * @return the wrapper inside {@code root} when it is a regular file
     */
    static Optional<Path> wrapper(Path root, String name) {
        if (root == null) {
            return Optional.empty();
        }
        Path p = root.resolve(name);
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /**
     * A build tool on {@code PATH} (the system {@code gradle} / {@code mvn}).
     *
     * @return the executable, empty when the tool is not installed
     */
    static Optional<Path> cliTool(String name) {
        return Toolchain.findOnPath(name, System.getenv("PATH"));
    }

    /**
     * The launcher a build action should use: the project wrapper first, then
     * the tool on {@code PATH}.
     *
     * @param wrapperName the wrapper file name ({@code gradlew} / {@code mvnw})
     * @param cliName     the {@code PATH} command ({@code gradle} / {@code mvn})
     */
    static Optional<Path> launcher(Path root, String wrapperName, String cliName) {
        Optional<Path> wrapped = wrapper(root, wrapperName);
        return wrapped.isPresent() ? wrapped : cliTool(cliName);
    }

    /** @return true when the document's own file name is this tool's build file. */
    static boolean isBuildFile(String fileName, List<String> markers) {
        if (fileName == null) {
            return false;
        }
        for (String marker : markers) {
            if (fileName.equals(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The directory a search should start from for the current document: its own
     * parent when the document is saved, else the editor process's working
     * directory (which the desktop launcher sets to the project's own tree).
     */
    static Optional<Path> searchStart(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            Path parent = Path.of(filePath).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                return Optional.of(parent);
            }
        }
        return Optional.of(Path.of("").toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------
    // Command builders (pure)
    // ------------------------------------------------------------------

    /**
     * {@code gradlew --console=plain <tasks>}. Plain console (no ANSI spinner)
     * because the log is read back as text into the Output panel, and the tasks
     * are passed verbatim so a failure names the task that failed.
     */
    static List<String> gradleCommand(Path launcher, List<String> tasks) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher.toString());
        cmd.add("--console=plain");
        cmd.addAll(tasks);
        return List.copyOf(cmd);
    }

    /**
     * {@code mvn -B --no-transfer-progress <goals>}. Batch mode keeps the run
     * non-interactive and uncoloured; {@code --no-transfer-progress} (Maven
     * 3.6.1+) suppresses the per-artifact download spam that would otherwise
     * push the real error out of the console's line budget.
     */
    static List<String> mavenCommand(Path launcher, List<String> goals) {
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher.toString());
        cmd.add("-B");
        cmd.add("--no-transfer-progress");
        cmd.addAll(goals);
        return List.copyOf(cmd);
    }

    /**
     * {@code java -cp <classesDir> <mainClass>} — Maven's run path. There is no
     * {@code exec-maven-plugin} guarantee in an offline build, so the run uses
     * the classes the compile goal already produced and the main class the POM
     * declares: no plugin download, no network.
     */
    static List<String> javaClasspathRunCommand(Path java, Path classesDir, String mainClass) {
        return List.of(java.toString(), "-cp", classesDir.toString(), mainClass);
    }

    /**
     * The environment handed to a build: {@code JAVA_HOME} pinned to the JDK the
     * desktop itself runs on <em>when the user has not set one</em> (so a build
     * never silently compiles against a different JDK than the editor's own
     * compile/run actions use), and {@code TERM=dumb} so neither tool emits
     * terminal control codes into the console.
     */
    static Map<String, String> buildEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        if (System.getenv("JAVA_HOME") == null) {
            String home = System.getProperty("java.home", "");
            if (!home.isEmpty()) {
                env.put("JAVA_HOME", home);
            }
        }
        env.put("TERM", "dumb");
        return Map.copyOf(env);
    }

    // ------------------------------------------------------------------
    // Build-log interpretation (pure)
    // ------------------------------------------------------------------

    /**
     * Extracts the build's compiler and test findings from merged tool output
     * and files them in the Problems panel. Findings that repeat (Gradle prints
     * every javac error once live and once inside {@code * What went wrong:},
     * Maven once in the {@code COMPILATION ERROR} block and once in the final
     * summary) are deduplicated by file, line and message; the first occurrence
     * keeps its position, so the panel order matches the log.
     *
     * @param output      merged stdout+stderr of the build
     * @param projectRoot the directory the build ran in, used to resolve the
     *                    relative and bare file names the logs print
     * @return up to {@link #MAX_DIAGNOSTICS} diagnostics, in log order
     */
    static List<Diagnostic> parseDiagnostics(String output, Path projectRoot) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        Map<String, Diagnostic> byKey = new LinkedHashMap<>();
        Map<String, Optional<Path>> nameCache = new LinkedHashMap<>();
        for (String raw : output.split("\n")) {
            if (byKey.size() >= MAX_DIAGNOSTICS) {
                break;
            }
            Diagnostic d = parseLine(raw, projectRoot, nameCache);
            if (d != null) {
                byKey.putIfAbsent(d.path() + ":" + d.line() + ":" + d.message(), d);
            }
        }
        return List.copyOf(byKey.values());
    }

    /**
     * One log line to one diagnostic, or null when the line carries no finding.
     * Package-private and exercised line-by-line by the tests with recorded
     * output.
     */
    static Diagnostic parseLine(String raw, Path projectRoot, Map<String, Optional<Path>> cache) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.strip();
        boolean warned = line.startsWith("[WARNING]") || line.startsWith("[WARN]");
        boolean errored = line.startsWith("[ERROR]");
        String body = LOG_PREFIX.matcher(line).replaceFirst("");

        Matcher m = KOTLIN_SHORT.matcher(body);
        if (m.matches()) {
            return build(body, m.group(1), projectRoot, cache,
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    line.startsWith("w:") ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR,
                    m.group(4));
        }
        m = MAVEN_LOC.matcher(body);
        if (m.matches()) {
            return build(body, m.group(1), projectRoot, cache,
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    warned ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR, m.group(4));
        }
        m = PLAIN_LOC.matcher(body);
        if (m.matches()) {
            Diagnostic.Kind kind = severity(m.group(4), warned, errored);
            int col = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            return build(body, m.group(1), projectRoot, cache,
                    Integer.parseInt(m.group(2)), col, kind, m.group(5));
        }
        m = GRADLE_TEST.matcher(body);
        if (m.matches()) {
            return build(body, m.group(2), projectRoot, cache,
                    Integer.parseInt(m.group(3)), 0, Diagnostic.Kind.ERROR,
                    "test failed — " + m.group(1));
        }
        m = MAVEN_TEST.matcher(body);
        if (m.matches()) {
            String file = qualifiedFile(m.group(1));
            Diagnostic d = build(body, file, projectRoot, cache,
                    Integer.parseInt(m.group(3)), 0, Diagnostic.Kind.ERROR,
                    "test failed — " + m.group(1) + "." + m.group(2) + ": " + m.group(4));
            if (d != null) {
                return d;
            }
            // The class did not resolve to a source file; report against the
            // project so the finding is still listed and clickable to the log.
            return Diagnostic.of(nameOf(projectRoot), Integer.parseInt(m.group(3)), 0,
                    Diagnostic.Kind.ERROR,
                    "test failed — " + m.group(1) + "." + m.group(2) + ": " + m.group(4));
        }
        return null;
    }

    /** Assembles one diagnostic, dropping lines whose file cannot be located. */
    private static Diagnostic build(String sourceLine, String rawPath, Path projectRoot,
            Map<String, Optional<Path>> cache, int line, int col,
            Diagnostic.Kind kind, String message) {
        Optional<Path> located = locate(rawPath, projectRoot, cache);
        if (located.isEmpty()) {
            return null;
        }
        // A line that also carries the file name (the log prints the whole line)
        // is kept verbatim: it is the compiler's own wording.
        return Diagnostic.of(located.get().toString(), line, col, kind, message.strip());
    }

    /**
     * Resolves the path a log line names. Absolute paths win if they exist;
     * relative paths resolve against the project root; a bare file name (the
     * Gradle/Maven test forms) is searched under the conventional source roots,
     * and the answer is cached so a thousand findings walk the tree once.
     */
    static Optional<Path> locate(String rawPath, Path projectRoot,
            Map<String, Optional<Path>> cache) {
        if (rawPath == null || rawPath.isBlank()) {
            return Optional.empty();
        }
        String text = rawPath.strip();
        if (text.startsWith("file:")) {
            text = stripUri(text);
        }
        // Kotlin/javac print "pkg/Foo.kt" with the root stripped; strip quotes
        // and any trailing caret marker line that some logs append.
        text = text.replace("'", "").replace("\"", "");
        Path p = Path.of(text);
        if (p.isAbsolute()) {
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        if (projectRoot != null) {
            Path resolved = projectRoot.resolve(p).normalize();
            if (Files.isRegularFile(resolved)) {
                return Optional.of(resolved);
            }
        }
        String name = p.getFileName().toString();
        if (name.indexOf('.') < 0) {
            return Optional.empty(); // not a file name we can search for
        }
        String key = (projectRoot == null ? "" : projectRoot) + "#" + name;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Optional<Path> found = (projectRoot == null)
                ? Optional.empty() : findSourceByName(projectRoot, name);
        cache.put(key, found);
        return found;
    }

    /** Turns {@code file:///tmp/x/App.kt} (or {@code file:/tmp/x}) into a path. */
    private static String stripUri(String text) {
        String rest = text.substring("file:".length());
        if (rest.startsWith("//")) {
            rest = rest.substring(2); // file://host/path — host is always local here
            int slash = rest.indexOf('/');
            rest = (slash < 0) ? "" : rest.substring(slash);
        }
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            rest = rest.substring(0, hash);
        }
        return rest;
    }

    /**
     * Bounded search for a source file by name under the conventional roots.
     * Never descends into {@link #SKIP_DIRS}, stops after {@code MAX_SCAN_FILES}
     * entries so an enormous tree cannot stall the EDT hand-off, and prefers the
     * shallowest (then lexicographically first) match for determinism.
     */
    private static Optional<Path> findSourceByName(Path root, String fileName) {
        final int maxFiles = 20_000;
        for (String sourceRoot : SOURCE_ROOTS) {
            Path dir = root.resolve(sourceRoot);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            Optional<Path> best = scanFor(dir, fileName, maxFiles);
            if (best.isPresent()) {
                return best;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> scanFor(Path dir, String fileName, int maxFiles) {
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 12)) {
            stream.filter(BuildTools::isSourceFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .limit(maxFiles)
                    .forEach(matches::add);
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return Optional.empty();
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        matches.sort(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString));
        return Optional.of(matches.get(0).toAbsolutePath().normalize());
    }

    /** True for a readable regular file, skipping build output directories. */
    private static boolean isSourceFile(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        Path relativeToBuild = p;
        for (Path part : relativeToBuild) {
            if (SKIP_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    /** {@code com.acme.AppTest} to {@code com/acme/AppTest.java}. */
    private static String qualifiedFile(String qualifiedClass) {
        String head = qualifiedClass.contains(".")
                ? qualifiedClass.substring(0, qualifiedClass.lastIndexOf('.')) : "";
        String simple = qualifiedClass.substring(
                head.isEmpty() ? 0 : head.length() + 1);
        String rel = head.isEmpty() ? simple : head.replace('.', '/') + "/" + simple;
        return rel + ".java";
    }

    private static Diagnostic.Kind severity(String word, boolean warned, boolean errored) {
        if (word != null) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.equals("warning")) {
                return Diagnostic.Kind.WARNING;
            }
            if (w.equals("note")) {
                return Diagnostic.Kind.INFO;
            }
            return Diagnostic.Kind.ERROR;
        }
        if (warned) {
            return Diagnostic.Kind.WARNING;
        }
        return errored ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
    }

    /**
     * The tool's own verdict, so the status line can repeat what the build said
     * rather than an exit code: {@code "BUILD SUCCESSFUL in 7s"}, {@code "BUILD
     * FAILURE (2.522 s)"}.
     *
     * @return the verdict, empty when the log carries none (killed, crashed)
     */
    static Optional<String> verdict(String output) {
        if (output == null) {
            return Optional.empty();
        }
        String gradle = null;
        String maven = null;
        String mavenTime = null;
        for (String raw : output.split("\n")) {
            String line = LOG_PREFIX.matcher(raw.strip()).replaceFirst("");
            Matcher g = GRADLE_VERDICT.matcher(line);
            if (g.matches()) {
                gradle = raw.strip();
            }
            if (MAVEN_VERDICT.matcher(line).matches()) {
                maven = line;
            }
            Matcher t = MAVEN_TIME.matcher(line);
            if (t.matches()) {
                mavenTime = t.group(1).strip();
            }
        }
        if (gradle != null) {
            return Optional.of(gradle);
        }
        if (maven != null) {
            return Optional.of((mavenTime == null) ? maven : maven + " (" + mavenTime + ")");
        }
        return Optional.empty();
    }

    /**
     * The build's headline failure: Gradle's first line under
     * {@code * What went wrong:} (plus its {@code > } detail when there is one),
     * or Maven's first {@code [ERROR] Failed to execute goal ...} — the two
     * strings that actually explain a red build. Maven's help/stack-trace
     * boilerplate is skipped, and a compiler-error line is not a headline
     * (those go to Problems).
     *
     * @return the headline, empty when the log has no failure narrative
     */
    static Optional<String> failureHeadline(String output) {
        if (output == null || output.isEmpty()) {
            return Optional.empty();
        }
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals("* What went wrong:")) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < lines.length && j <= i + 4; j++) {
                    String next = lines[j].strip();
                    if (next.isEmpty()) {
                        continue;
                    }
                    if (next.startsWith(">")) {
                        sb.append(" \u2014 ").append(next.replaceFirst("^>\\s*", ""));
                        break;
                    }
                    if (next.startsWith("*") || sb.length() > 0) {
                        break;
                    }
                    sb.append(next);
                }
                String headline = sb.toString().strip();
                return headline.isEmpty() ? Optional.empty() : Optional.of(headline);
            }
        }
        for (String raw : lines) {
            Matcher goal = MAVEN_GOAL.matcher(LOG_PREFIX.matcher(raw.strip()).replaceFirst(""));
            if (goal.matches()) {
                return Optional.of(mavenGoalHeadline(goal.group(1), goal.group(2)));
            }
        }
        for (String raw : lines) {
            String line = LOG_PREFIX.matcher(raw.strip()).replaceFirst("");
            if (line.isBlank() || PLAIN_LOC.matcher(line).matches()
                    || MAVEN_LOC.matcher(line).matches()) {
                continue; // compiler findings belong to the Problems panel
            }
            if (raw.strip().startsWith("[ERROR]") && !containsAny(line, MAVEN_NOISE)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /**
     * Compresses Maven's goal specification to what a status line can show:
     * {@code maven-surefire-plugin test failed \u2014 There are test failures.} —
     * the plugin's artifact id and goal, dropping the {@code org.apache.maven
     * .plugins} group and the version the user did not ask about.
     */
    private static String mavenGoalHeadline(String goalSpec, String reason) {
        String[] parts = goalSpec.split(":");
        String artifact = (parts.length >= 2) ? parts[1] : goalSpec;
        String goal = parts[parts.length - 1];
        return artifact + " " + goal + " failed \u2014 " + reason;
    }

    /**
     * The main class a Maven run should launch, read from the POM text (so an
     * unsaved edit to the POM is what gets launched): the {@code
     * exec.mainClass} property first, then the {@code mainClass} the jar plugin
     * is configured to write into the manifest.
     *
     * @return the fully qualified class name, empty when the POM declares none
     */
    static Optional<String> mainClassFromPom(String pomText) {
        if (pomText == null || pomText.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = EXEC_MAIN_CLASS.matcher(pomText);
        if (m.find()) {
            return Optional.of(m.group(1).strip());
        }
        m = MAIN_CLASS.matcher(pomText);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    /**
     * Reads a build file the editor may not have open. Any failure (missing,
     * unreadable, no permission) yields empty — the caller reports it as a
     * status message, never as an exception on the EDT.
     */
    static Optional<String> readBuildFile(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(new String(Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The HTML report a test task wrote, when the log names one — the fastest
     * way to read a stack trace that the console truncated.
     */
    static Optional<String> testReportUrl(String output) {
        if (output == null) {
            return Optional.empty();
        }
        Matcher m = REPORT_URL.matcher(output);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * The one line a finished build leaves on the status bar: the tool's own
     * verdict when the log carries one (a build explains itself, an exit code
     * explains nothing), the failure headline when it does not, and how many
     * findings landed in the Problems panel.
     *
     * @param label  the action's name, e.g. {@code "gradle build"}
     * @param result the finished run
     * @param diags  the findings filed for this run
     */
    static String statusLine(String label, Toolchain.Result result, List<Diagnostic> diags) {
        String verdict = verdict(result.output()).orElse("");
        StringBuilder sb = new StringBuilder(label);
        if (result.exitCode() == 124) {
            sb.append(" \u2014 still running when the time limit passed; killed");
        } else if (result.success()) {
            sb.append(verdict.isEmpty() ? " \u2014 OK" : " \u2014 " + verdict);
        } else {
            sb.append(verdict.isEmpty()
                    ? " \u2014 failed (exit " + result.exitCode() + ")"
                    : " \u2014 " + verdict);
            Optional<String> head = failureHeadline(result.output());
            if (head.isPresent()) {
                sb.append(": ").append(head.get());
            }
        }
        int errors = 0;
        int warnings = 0;
        for (Diagnostic d : diags) {
            if (d.kind() == Diagnostic.Kind.ERROR) {
                errors++;
            } else if (d.kind() == Diagnostic.Kind.WARNING) {
                warnings++;
            }
        }
        if (errors + warnings > 0) {
            sb.append(" \u2014 ").append(errors).append(" error(s), ")
                    .append(warnings).append(" warning(s) in Problems");
        }
        return clamp(sb.toString(), MAX_STATUS_CHARS);
    }

    /** Status lines are one line; a runaway headline gets an ellipsis. */
    private static String clamp(String text, int max) {
        return (text.length() <= max) ? text : text.substring(0, max - 1) + "\u2026";
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(Path root) {
        if (root == null) {
            return "";
        }
        Path name = root.getFileName();
        return (name == null) ? root.toString() : name.toString();
    }

    // ------------------------------------------------------------------
    // Build-file outlining
    // ------------------------------------------------------------------

    /**
     * Outlines a {@code pom.xml} with a real StAX parse (line numbers included,
     * so a row jumps to its declaration): the project coordinates, every
     * property, every dependency ({@code groupId:artifactId:version[:scope]}),
     * every build plugin and every module. A truncated or malformed POM — the
     * normal state of a file being typed into — yields the part read before the
     * error, so the outline never goes blank on a syntax slip.
     *
     * @param pomText the document text
     * @return outline rows in document order
     */
    static List<StructureSymbol> pomOutline(String pomText) {
        List<StructureSymbol> rows = new ArrayList<>();
        if (pomText == null || pomText.isBlank()) {
            return rows;
        }
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            XMLStreamReader r = factory.createXMLStreamReader(
                    new java.io.StringReader(pomText));
            parsePom(r, rows);
            r.close();
        } catch (XMLStreamException expected) {
            // A half-written POM: keep whatever was parsed before the error.
        } catch (RuntimeException | StackOverflowError e) {
            // Defensive: a reader that cannot even be constructed yields nothing.
            return List.of();
        }
        return rows;
    }

    /**
     * Streaming POM walk. Every decision happens at {@code END_ELEMENT}, where
     * the element's own text is finally known and its start line is still on the
     * stack — so each outline row jumps to the declaration the user actually
     * wrote. Depth is a stack of local names (the POM namespace is ignored), and
     * a {@code <dependency>}, {@code <plugin>} or {@code <parent>} block is
     * accumulated into one {@link PomEntry} so its group/artifact/version/scope
     * children collapse into a single readable row.
     */
    private static void parsePom(XMLStreamReader r, List<StructureSymbol> rows)
            throws XMLStreamException {
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        PomEntry entry = null;

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                String parent = parent(names);
                names.add(name);
                starts.add(r.getLocation().getLineNumber());
                text.setLength(0);
                if (name.equals("dependency") && parent.equals("dependencies")) {
                    entry = new PomEntry(names.contains("plugin")
                            ? "plugin dependency" : "dependency");
                } else if (name.equals("plugin") && parent.equals("plugins")) {
                    entry = new PomEntry("plugin");
                } else if (name.equals("parent") && parent.equals("project")) {
                    entry = new PomEntry("parent");
                }
                continue;
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                String name = names.isEmpty()
                        ? "" : names.remove(names.size() - 1);
                int line = starts.isEmpty() ? 1 : starts.remove(starts.size() - 1);
                String value = text.toString().strip();
                text.setLength(0);
                String parent = parent(names);
                String grandparent = grandparent(names);
                emitPomRow(rows, parent, grandparent, name, line, value, entry);
                if (entry != null && closesEntry(entry.kind, name)) {
                    rows.add(new StructureSymbol(entry.coordinate(), entry.kind, line));
                    entry = null;
                }
                continue;
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                text.append(r.getText());
            }
        }
    }

    /**
     * True when {@code name} is the element that closes an accumulating block of
     * kind {@code kind} — a {@code plugin dependency} is closed by its own
     * {@code <dependency>} tag, not by the enclosing {@code <plugin>}.
     */
    private static boolean closesEntry(String kind, String name) {
        String expected = kind.equals("plugin dependency") ? "dependency" : kind;
        return expected.equals(name);
    }

    /** Files one closed POM element: block fields, project header, properties… */
    private static void emitPomRow(List<StructureSymbol> rows, String parent,
            String grandparent, String name, int line, String value, PomEntry entry) {
        if (value.isEmpty()) {
            return;
        }
        if (entry != null && (parent.equals("dependency") || parent.equals("plugin")
                || parent.equals("parent"))) {
            switch (name) {
                case "groupId" :
                    entry.groupId = value;
                    break;
                case "artifactId" :
                    entry.artifactId = value;
                    break;
                case "version" :
                    entry.version = value;
                    break;
                case "scope" :
                    entry.scope = value;
                    break;
                default :
                    break;
            }
            return;
        }
        if (parent.equals("project")) {
            String label = projectChildLabel(name);
            if (label != null) {
                rows.add(new StructureSymbol(value, label, line));
            }
            return;
        }
        if (parent.equals("properties") && grandparent.equals("project")) {
            rows.add(new StructureSymbol(name + " = " + value, "property", line));
            return;
        }
        if (parent.equals("modules") && name.equals("module")) {
            rows.add(new StructureSymbol(value, "module", line));
        }
    }

    /** @return the outline label for a top-level POM child, null to skip it. */
    private static String projectChildLabel(String name) {
        switch (name) {
            case "groupId" :
                return "group id";
            case "artifactId" :
                return "artifact id";
            case "version" :
                return "version";
            case "packaging" :
                return "packaging";
            case "name" :
                return "name";
            default :
                return null;
        }
    }

    /** One open {@code <dependency>}/{@code <plugin>}/{@code <parent>} block. */
    private static final class PomEntry {
        private final String kind;
        private String groupId = "";
        private String artifactId = "";
        private String version = "";
        private String scope = "";

        PomEntry(String kind) {
            this.kind = kind;
        }

        /** {@code group:artifact:version [scope]}; a plugin without a group reads {@code artifact:version}. */
        String coordinate() {
            StringBuilder sb = new StringBuilder();
            if (!groupId.isEmpty()) {
                sb.append(groupId).append(':');
            }
            sb.append(artifactId.isEmpty() ? "?" : artifactId);
            if (!version.isEmpty()) {
                sb.append(':').append(version);
            }
            if (!scope.isEmpty()) {
                sb.append(" [").append(scope).append(']');
            }
            return sb.toString();
        }
    }

    /** The innermost open element (the parent of the one just closed). */
    private static String parent(List<String> names) {
        return names.isEmpty() ? "" : names.get(names.size() - 1);
    }

    private static String grandparent(List<String> names) {
        return names.size() < 2 ? "" : names.get(names.size() - 2);
    }

    /**
     * Best-effort outline of a Gradle build script: applied plugins, declared
     * repositories, dependency coordinates (with their configuration) and
     * registered tasks, each with its line. There is no Groovy or Kotlin parser
     * in the JDK, so this is a documented text scan of the idioms the DSL
     * actually uses — same trade-off {@link JavaStructureTools} makes for Kotlin.
     */
    static List<StructureSymbol> gradleBuildOutline(String text) {
        List<StructureSymbol> rows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rows;
        }
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            int lineNo = i + 1;
            Matcher apply = Pattern
                    .compile("apply\\s+plugin:\\s*['\"]([^'\"]+)['\"]").matcher(line);
            if (apply.find()) {
                rows.add(new StructureSymbol(apply.group(1), "plugin", lineNo));
                continue;
            }
            Matcher id = Pattern
                    .compile("\\bid\\s*\\(?\\s*['\"]([^'\"]+)['\"]").matcher(line);
            if (id.find()) {
                rows.add(new StructureSymbol(id.group(1), "plugin", lineNo));
                continue;
            }
            Matcher repo = Pattern
                    .compile("\\b(mavenLocal|mavenCentral|google|jcenter|maven\\s*\\{)")
                    .matcher(line);
            if (repo.find() && (line.startsWith("repositories")
                    || line.contains("()") || line.contains("url"))) {
                rows.add(new StructureSymbol(repo.group(1)
                        .replace("maven {", "maven"), "repository", lineNo));
                continue;
            }
            Matcher dep = Pattern.compile(
                    "\\b(implementation|api|compileOnly|runtimeOnly|testImplementation"
                            + "|testRuntimeOnly|testCompileOnly|annotationProcessor"
                            + "|classpath|localGroovy|dependencies)\\s*\\(?\\s*"
                            + "['\"]([\\w.\\-]+:[\\w.\\-]+:[^'\\\"]+)['\\$]").matcher(line);
            if (dep.find()) {
                rows.add(new StructureSymbol(dep.group(2), dep.group(1), lineNo));
                continue;
            }
            Matcher task = Pattern.compile(
                    "(?:^|\\s)(task\\s+([A-Za-z_][\\w]*)|tasks\\.register\\s*\\(\\s*['\"]"
                            + "([^'\"]+)['\"]|register\\s*\\(\\s*['\"]([^'\"]+)['\"])")
                    .matcher(line);
            if (task.find()) {
                String tname = task.group(2) != null ? task.group(2)
                        : (task.group(3) != null ? task.group(3) : task.group(4));
                rows.add(new StructureSymbol(tname, "task", lineNo));
                continue;
            }
            if (line.startsWith("group") || line.startsWith("version")
                    || line.startsWith("sourceCompatibility")) {
                rows.add(new StructureSymbol(line.replaceAll("\\s+", " "), "setting", lineNo));
            }
        }
        return dedupeSymbols(rows);
    }

    /** Drops identical (name, kind, line) rows the multi-regex scan can produce. */
    private static List<StructureSymbol> dedupeSymbols(List<StructureSymbol> rows) {
        Set<String> seen = new LinkedHashSet<>();
        List<StructureSymbol> out = new ArrayList<>();
        for (StructureSymbol s : rows) {
            if (seen.add(s.name() + "|" + s.kind() + "|" + s.line())) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Task-tree discovery (pure)
    // ------------------------------------------------------------------

    /**
     * The base lifecycle every Gradle build carries, name + one-line blurb.
     * These are the {@code base} plugin's own tasks, so they are proposed for
     * any project whether or not the build script names them.
     */
    private static final String[][] GRADLE_BASE_LIFECYCLE = {
            {"build", "Assemble the project and run all of its checks"},
            {"assemble", "Assemble the project's outputs (jars, distributions)"},
            {"check", "Run every verification task, tests included"},
            {"clean", "Delete the project's build output directory"},
            {"tasks", "List every task this project declares"},
    };

    /**
     * The plugins whose presence means the project compiles JVM sources, and so
     * has a {@code test} task worth proposing. {@code application} implies the
     * Java plugin, and is listed again here for the {@code run} detection.
     */
    private static final List<String> JVM_PLUGINS = List.of(
            "java", "java-library", "application", "groovy", "scala",
            "org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.android", "kotlin");

    /**
     * Maven's lifecycle phases, in their canonical order, name + blurb. Unlike
     * Gradle a POM never "declares" these — they are the fixed vocabulary of the
     * default lifecycle — so the whole set is proposed for any Maven project and
     * the scan's only job is to confirm the POM is real enough to build.
     */
    private static final String[][] MAVEN_LIFECYCLE = {
            {"clean", "Remove the target/ directory"},
            {"validate", "Validate the project is correct and complete"},
            {"compile", "Compile the main source code"},
            {"test", "Run the unit tests through surefire"},
            {"package", "Build and test, then write the jar/war to target/"},
            {"verify", "Run integration-test and quality checks"},
            {"install", "Install the package into the local repository"},
            {"site", "Generate the project site documentation"},
            {"deploy", "Deploy the package to the remote repository"},
    };

    /** The task-registration idioms {@link #gradleBuildOutline} already reads. */
    private static final Pattern TASK_DECL = Pattern.compile(
            "(?:^|\\s)(task\\s+([A-Za-z_][\\w]*)|tasks\\.register\\s*\\(\\s*['\"]"
                    + "([^'\"]+)['\"]|register\\s*\\(\\s*['\"]([^'\"]+)['\"])");

    /**
     * The task tree a Gradle project proposes, discovered by scanning its build
     * script and settings file rather than by invoking Gradle: the base
     * lifecycle every build carries, {@code test} when a JVM plugin is applied,
     * {@code run} when the {@code application} plugin is, every task the script
     * itself registers (with its declaration line), and — for a multi-project
     * build — each {@code include}d module's own {@code build}/{@code test}/
     * {@code clean}. Every returned task is unwired ({@link BuildTask#action()}
     * is null); the caller attaches the run callback.
     *
     * @param buildText    the {@code build.gradle(.kts)} text (may be null)
     * @param settingsText the {@code settings.gradle(.kts)} text (may be null)
     * @return the proposed tasks, grouped and in a stable order
     */
    static List<BuildTask> gradleTaskTree(String buildText, String settingsText) {
        List<BuildTask> tasks = new ArrayList<>();
        String build = (buildText == null) ? "" : buildText;
        for (String[] row : GRADLE_BASE_LIFECYCLE) {
            tasks.add(new BuildTask("Lifecycle", row[0], row[1], 0));
        }
        boolean jvm = appliesAnyPlugin(build, JVM_PLUGINS);
        if (jvm) {
            tasks.add(new BuildTask("Lifecycle", "test", "Run the unit tests", 0));
        }
        if (appliesAnyPlugin(build, List.of("application"))) {
            tasks.add(new BuildTask("Lifecycle", "run",
                    "Run the application (application plugin)", 0));
        }
        tasks.addAll(declaredGradleTasks(build));
        for (String module : gradleModules(settingsText)) {
            String group = "module " + module;
            tasks.add(new BuildTask(group, module + ":build", "Build this subproject", 0));
            tasks.add(new BuildTask(group, module + ":test", "Test this subproject", 0));
            tasks.add(new BuildTask(group, module + ":clean", "Clean this subproject", 0));
        }
        return tasks;
    }

    /**
     * The task tree a Maven project proposes: the default lifecycle phases,
     * offered only when {@code pomText} looks like a POM at all (so a project
     * with no POM proposes nothing). Every task is unwired; the caller attaches
     * the run callback. Multi-module reactors need no per-module rows — a phase
     * run at the root already fans out through the reactor.
     *
     * @param pomText the {@code pom.xml} text (may be null)
     * @return the lifecycle goals, in canonical order
     */
    static List<BuildTask> mavenTaskTree(String pomText) {
        if (pomText == null || !pomText.contains("<project")) {
            return List.of();
        }
        List<BuildTask> tasks = new ArrayList<>(MAVEN_LIFECYCLE.length);
        for (String[] row : MAVEN_LIFECYCLE) {
            tasks.add(new BuildTask("Lifecycle", row[0], row[1], 0));
        }
        return tasks;
    }

    /** True when {@code text} applies any of {@code pluginIds}. */
    private static boolean appliesAnyPlugin(String text, List<String> pluginIds) {
        for (String id : pluginIds) {
            if (appliesPlugin(text, id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code text} applies {@code id}, in either of the two shapes the
     * DSL uses: {@code plugins { id 'x' }} / {@code id("x")} or the legacy
     * {@code apply plugin: 'x'}. Unquoted core-plugin ids are not matched — the
     * {@code plugins} block requires quotes, so a quoted scan is the correct one.
     */
    static boolean appliesPlugin(String text, String id) {
        if (text == null || text.isEmpty() || id == null || id.isEmpty()) {
            return false;
        }
        String q = Pattern.quote(id);
        return Pattern.compile("id\\s*\\(?\\s*['\"]" + q + "['\"]").matcher(text).find()
                || Pattern.compile("apply\\s+plugin:\\s*['\"]" + q + "['\"]")
                        .matcher(text).find();
    }

    /**
     * The tasks a build script registers itself, each with its declaration line,
     * deduplicated by name (the first registration wins). Grouped under
     * {@code "Declared tasks"} so they read as the project's own vocabulary
     * beside the built-in lifecycle.
     */
    private static List<BuildTask> declaredGradleTasks(String buildText) {
        List<BuildTask> out = new ArrayList<>();
        if (buildText == null || buildText.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = buildText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            Matcher m = TASK_DECL.matcher(line);
            if (m.find()) {
                String name = m.group(2) != null ? m.group(2)
                        : (m.group(3) != null ? m.group(3) : m.group(4));
                if (name != null && seen.add(name)) {
                    out.add(new BuildTask("Declared tasks", name,
                            "Declared in the build script", i + 1));
                }
            }
        }
        return out;
    }

    /**
     * The subprojects a settings file {@code include}s, each normalised to its
     * colon-led task path ({@code :app}, {@code :app:core}). Reads both the
     * Groovy {@code include ':a', ':b'} and the Kotlin {@code include(":a")}
     * shapes; comment lines are skipped. Order is document order, deduplicated.
     */
    static List<String> gradleModules(String settingsText) {
        List<String> out = new ArrayList<>();
        if (settingsText == null || settingsText.isBlank()) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        Matcher quoted = Pattern.compile("['\"]([^'\"]+)['\"]").matcher("");
        for (String raw : settingsText.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("//") || !line.contains("include")) {
                continue;
            }
            quoted.reset(line);
            while (quoted.find()) {
                String name = quoted.group(1).strip();
                if (name.isEmpty()) {
                    continue;
                }
                String path = name.startsWith(":") ? name : ":" + name;
                if (seen.add(path)) {
                    out.add(path);
                }
            }
        }
        return out;
    }
}
