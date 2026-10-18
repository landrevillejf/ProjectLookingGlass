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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jdesktop.lg3d.apps.texteditor.ext.BuildTask;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The Gradle extension: drives the Gradle project that owns the current
 * document and reports what really happened. Where {@link JvmBuildTools}
 * compiles one untitled buffer in a temp directory, this extension runs the
 * project's own build — the document is only a pointer to which project to
 * work in, which is what makes a multi-file Gradle build usable from an editor.
 *
 * <p><b>Discovery.</b> The project root is the nearest ancestor directory
 * holding {@code settings.gradle(.kts)} or {@code build.gradle(.kts)} (same
 * walk limit as the west {@link ProjectTreePanel}), and the launcher is
 * resolved from the project alone — its {@code ./gradlew} when it exists (the
 * wrapper pins the Gradle version the project was written for), else a Gradle
 * the project carries under {@code .espresso/tools/} or unpacked at its root.
 * The system {@code PATH} is never used, so a project with no Gradle of its own
 * gets a status line saying it is not provisioned instead of a stack trace.</p>
 *
 * <p><b>Reporting.</b> The full log goes to the Output panel; compiler and test
 * findings are parsed out of it into the Problems panel (and each open file's
 * own gutter), the status line repeats Gradle's own verdict plus its
 * {@code * What went wrong:} headline, and a failing test run also names the
 * HTML report Gradle wrote. Nothing is written into the document.</p>
 *
 * <p><b>Limits, stated.</b> A build runs in the foreground with a time limit:
 * an app started by {@code gradle run} that never exits is killed when the limit
 * expires and the status line says exactly that. Gradle output is interpreted as
 * text — there is no Tooling API connection, so there is no incremental
 * up-to-date model, no cancellation mid-task and no build-event stream.</p>
 */
public final class GradleBuildTools implements TextEditorExtension {

    /** A full build (compile + test) on a cold daemon; Gradle's own JVM start-up is slow. */
    private static final long BUILD_TIMEOUT_MS = 300_000;
    /** {@code tasks} needs a configured project but compiles nothing. */
    private static final long QUICK_TIMEOUT_MS = 120_000;
    /** A run that does not terminate is killed at the limit and reported as such. */
    private static final long RUN_TIMEOUT_MS = 120_000;

    /**
     * Which of the project's Gradle files the outline reads. Discovery order
     * ({@link BuildTools#GRADLE_MARKERS}) puts {@code settings.gradle} first
     * because that is what makes a directory a project <em>root</em>; for
     * outlining it is the wrong winner, since a settings file declares no
     * plugins, repositories or dependencies and would outline to nothing while
     * the real build script sits next to it. The settings files stay as the
     * fallback for a project that keeps everything there.
     */
    private static final List<String> OUTLINE_MARKERS = List.of(
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts");

    /** The build script the task scan reads applied plugins and declared tasks from. */
    private static final List<String> BUILD_SCRIPT_MARKERS = List.of(
            "build.gradle", "build.gradle.kts");

    /** The settings file the task scan reads subproject {@code include}s from. */
    private static final List<String> SETTINGS_MARKERS = List.of(
            "settings.gradle", "settings.gradle.kts");

    /**
     * Lifecycle tasks that configure the project but compile nothing, so a task
     * tree run of one gets the short timeout and no diagnostic parsing (the same
     * treatment the toolbar's own {@code clean} / {@code tasks} actions get).
     */
    private static final Set<String> QUICK_TASKS = Set.of(
            "clean", "tasks", "help", "dependencies", "projects", "properties");

    /**
     * Tasks that launch a long-running, potentially never-exiting process (the
     * application itself), so they get the run timeout rather than the build
     * timeout: {@code run} from the application plugin and {@code bootRun} from
     * the Spring Boot plugin.
     */
    private static final Set<String> RUN_TASKS = Set.of("run", "bootRun");

    private EditorContext editor;
    private DocumentContext currentDoc;
    private BuildTools.Runner runner = BuildTools.ASYNC_RUNNER;
    /**
     * Seam over launcher resolution, so a test can prove the "Gradle is not
     * provisioned" path without a real project tree. The default resolves the
     * project's own wrapper or a Gradle it carries — never the system PATH.
     */
    private Function<Path, Optional<Path>> launcherSeam = ProjectToolchain::gradleLauncher;
    /**
     * The central JDK manager, used to resolve the JDK this project selected so
     * the build runs under that {@code JAVA_HOME} rather than an ambient one.
     */
    private JdkRegistry registry = new JdkRegistry();

    // -- test seams -------------------------------------------------------

    final void setRunnerForTesting(BuildTools.Runner replacement) {
        this.runner = replacement;
    }

    final void setLauncherForTesting(Function<Path, Optional<Path>> replacement) {
        this.launcherSeam = replacement;
    }

    final void setJdkRegistryForTesting(JdkRegistry replacement) {
        this.registry = replacement;
    }

    // -- SPI ---------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.FILE_IO,
                TextEditorPermission.TOOLBAR,
                TextEditorPermission.DIAGNOSE
        );
        return new TextEditorManifest(
                "lg3d.gradle-build",
                "Gradle Build",
                "1.0.0",
                "Build, test, run and clean the Gradle project owning the current document",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Build";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
        publishTasks();
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
        // Re-scan only when the edited document is itself a Gradle build or
        // settings file, so the tree follows a task the user is adding or
        // removing right now without re-reading the build script on every
        // keystroke in an ordinary source file.
        if (isGradleScript(doc)) {
            publishTasks();
        }
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
        publishTasks();
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        // The build actions share the Ctrl+Alt+Shift family with JVM Build Tools
        // (the plain Ctrl+Alt space is exhausted); Gradle takes G/T/U/C/S and
        // Maven E/I/P/W/Q, so the two providers cannot collide with each other
        // or with the family's earlier claims (A B D J K L M R X). Outlining a
        // build file is a view action and stays unbound.
        return List.of(
                new ToolbarContribution("gradle-build", "Build",
                        "Run the project's build task in its own directory; the log lands in the Output panel and its errors in Problems",
                        this::build, "control alt shift G"),
                new ToolbarContribution("gradle-test", "Run Tests",
                        "Run the test task; failures are filed per file, and the HTML report path is shown when one was written",
                        this::runTests, "control alt shift T"),
                new ToolbarContribution("gradle-run", "Run Application",
                        "Run the run task (needs the application plugin); a process that never exits is killed at the time limit",
                        this::runApp, "control alt shift U"),
                new ToolbarContribution("gradle-clean", "Clean",
                        "Run the clean task, deleting the project's build output",
                        this::clean, "control alt shift C"),
                new ToolbarContribution("gradle-tasks", "List Tasks",
                        "Show the project's runnable tasks in the Output panel",
                        this::listTasks, "control alt shift S"),
                new ToolbarContribution("gradle-outline", "Outline Build File",
                        "Outline the build script in the Structure tab: plugins, repositories, dependencies and registered tasks",
                        this::outlineBuildFile)
        );
    }

    // -- actions -----------------------------------------------------------

    private void build() {
        runTasks("gradle build", List.of("build"), BUILD_TIMEOUT_MS, true);
    }

    private void runTests() {
        runTasks("gradle test", List.of("test"), BUILD_TIMEOUT_MS, true);
    }

    private void runApp() {
        runTasks("gradle run", List.of("run"), RUN_TIMEOUT_MS, true);
    }

    private void clean() {
        runTasks("gradle clean", List.of("clean"), QUICK_TIMEOUT_MS, false);
    }

    private void listTasks() {
        runTasks("gradle tasks", List.of("tasks"), QUICK_TIMEOUT_MS, false);
    }

    /**
     * Resolve project and launcher, then hand the command to the runner. Every
     * guard reports on the status line and spawns nothing — an editor action
     * must never throw at the user because a directory has no build file.
     */
    private void runTasks(String label, List<String> tasks, long timeoutMs, boolean diagnose) {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        Optional<Path> root = projectRoot();
        if (root.isEmpty()) {
            ctx.showMessage(label + ": no build.gradle within " + BuildTools.MAX_WALK_UP
                    + " folders above the document \u2014 open a file from inside a Gradle project");
            return;
        }
        Path work = root.get();
        Optional<Path> launcher = launcherSeam.apply(work);
        if (launcher.isEmpty()) {
            ctx.showMessage(label + ": Gradle is not provisioned \u2014 no ./gradlew and no"
                    + " project Gradle in " + work + " (create the project with Espresso"
                    + " or add a wrapper)");
            return;
        }
        ctx.showMessage("Running " + label + " in " + work + "\u2026");
        Path jdkHome = ProjectToolchain.jdkHome(work, registry).orElse(null);
        Map<String, String> env = BuildTools.buildEnvironment(jdkHome);
        runner.run(BuildTools.gradleCommand(launcher.get(), tasks), work, env, timeoutMs,
                res -> finished(work, label, res, diagnose));
    }

    /** Files one finished build's output, findings and status line. EDT callback. */
    private void finished(Path root, String label, Toolchain.Result res, boolean diagnose) {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        ctx.showOutput(label + " output", res.output());
        List<Diagnostic> diags = diagnose
                ? BuildTools.parseDiagnostics(res.output(), root) : List.of();
        if (diagnose) {
            String path = (currentDoc == null) ? null : currentDoc.getFilePath();
            if (diags.isEmpty() && res.success()) {
                ctx.clearDiagnostics(path);
            } else {
                ctx.reportDiagnostics(path, diags);
            }
        }
        String status = BuildTools.statusLine(label, res, diags);
        if (!res.success()) {
            Optional<String> report = BuildTools.testReportUrl(res.output());
            if (report.isPresent()) {
                status = status + " \u2014 report: " + report.get();
            }
        }
        ctx.showMessage(status);
    }

    /**
     * Outline the build script: the open document when it <em>is</em> a Gradle
     * build file (so unsaved edits are outlined), otherwise the project's own
     * {@code build.gradle(.kts)} read from disk.
     */
    private void outlineBuildFile() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        String text = doc.getFullText();
        String path = doc.getFilePath();
        String label = doc.getFileName();
        if (!BuildTools.isBuildFile(label, BuildTools.GRADLE_MARKERS)) {
            Optional<Path> root = projectRoot();
            Optional<Path> file = root.flatMap(r -> firstBuildFile(r));
            if (file.isEmpty()) {
                ctx.showMessage("Outline Build File: the document is not a Gradle build"
                        + " script and the project has none to read");
                return;
            }
            Optional<String> onDisk = BuildTools.readBuildFile(file.get());
            if (onDisk.isEmpty()) {
                ctx.showMessage("Outline Build File: could not read " + file.get());
                return;
            }
            text = onDisk.get();
            path = file.get().toString();
            label = file.get().getFileName().toString();
        }
        List<StructureSymbol> rows = BuildTools.gradleBuildOutline(text);
        ctx.showStructure(path, rows);
        ctx.showMessage(rows.isEmpty()
                ? "Outline " + label + ": nothing recognised \u2014 the scan reads"
                        + " apply plugin, plugins { id }, repositories, dependencies and task registration"
                : "Outline " + label + ": " + rows.size()
                        + " entries (plugins, repositories, dependencies, tasks, settings)");
    }

    // -- helpers -------------------------------------------------------------

    /** The Gradle project root for the current document, if there is one. */
    private Optional<Path> projectRoot() {
        String filePath = (currentDoc == null) ? null : currentDoc.getFilePath();
        return BuildTools.searchStart(filePath)
                .flatMap(start -> BuildTools.projectRoot(start, BuildTools.GRADLE_MARKERS));
    }

    /** The project's own build script, preferring the one that can be outlined. */
    private static Optional<Path> firstBuildFile(Path root) {
        for (String marker : OUTLINE_MARKERS) {
            Path p = root.resolve(marker);
            if (java.nio.file.Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    // -- task tree -----------------------------------------------------------

    /**
     * Scans the open project's build script and settings file and publishes the
     * tasks it proposes to the editor's Tasks tree, each wired to the same
     * {@link #runTasks} path the toolbar actions use — so a task run from the
     * tree logs, files findings and reports on the status line exactly like its
     * button. A directory that is not a Gradle project publishes nothing, which
     * leaves any other extension's tree (a Maven project's) untouched. Called on
     * the EDT from the document hooks; the scan reads two small files at most.
     */
    private void publishTasks() {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        Optional<Path> root = projectRoot();
        if (root.isEmpty()) {
            return;
        }
        Path project = root.get();
        List<BuildTask> discovered = BuildTools.gradleTaskTree(
                scriptText(project, BUILD_SCRIPT_MARKERS),
                scriptText(project, SETTINGS_MARKERS));
        List<BuildTask> wired = new ArrayList<>(discovered.size());
        for (BuildTask task : discovered) {
            String name = task.name();
            wired.add(task.withAction(() -> runTasks("gradle " + name, List.of(name),
                    timeoutFor(name), diagnoseFor(name))));
        }
        Path fileName = project.getFileName();
        ctx.showTasks((fileName == null) ? project.toString() : fileName.toString(), wired);
    }

    /** True when {@code doc} is a Gradle build or settings file. */
    private static boolean isGradleScript(DocumentContext doc) {
        if (doc == null) {
            return false;
        }
        return BuildTools.isBuildFile(doc.getFileName(), BUILD_SCRIPT_MARKERS)
                || BuildTools.isBuildFile(doc.getFileName(), SETTINGS_MARKERS);
    }

    /**
     * The text of the first of {@code markers} to exist: the open document when
     * it <em>is</em> that file (so unsaved edits are scanned), else the
     * project's own copy from disk, else empty.
     */
    private String scriptText(Path root, List<String> markers) {
        DocumentContext doc = currentDoc;
        if (doc != null && BuildTools.isBuildFile(doc.getFileName(), markers)) {
            return doc.getFullText();
        }
        for (String marker : markers) {
            Optional<String> text = BuildTools.readBuildFile(root.resolve(marker));
            if (text.isPresent()) {
                return text.get();
            }
        }
        return "";
    }

    /** The run timeout for a task: app-launching tasks stream, quick tasks only configure. */
    private static long timeoutFor(String task) {
        String leaf = leafOf(task);
        if (RUN_TASKS.contains(leaf)) {
            return RUN_TIMEOUT_MS;
        }
        return QUICK_TASKS.contains(leaf) ? QUICK_TIMEOUT_MS : BUILD_TIMEOUT_MS;
    }

    /** True when a task's output should be parsed for compiler/test findings. */
    private static boolean diagnoseFor(String task) {
        return !QUICK_TASKS.contains(leafOf(task));
    }

    /** The last colon-delimited segment of a task path ({@code :app:run} -> {@code run}). */
    private static String leafOf(String task) {
        int colon = task.lastIndexOf(':');
        return (colon >= 0) ? task.substring(colon + 1) : task;
    }
}
