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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
 * walk limit as the west {@link ProjectTreePanel}), and the launcher is the
 * project's {@code ./gradlew} when it exists — the wrapper pins the Gradle
 * version the project was written for — otherwise a {@code gradle} on
 * {@code PATH}. A project with neither gets a status line saying so instead of
 * a stack trace.</p>
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

    private EditorContext editor;
    private DocumentContext currentDoc;
    private BuildTools.Runner runner = BuildTools.ASYNC_RUNNER;
    /**
     * Seam over the wrapper/PATH lookup, so a test can prove the "Gradle is not
     * available" path on a machine that happens to have Gradle installed — the
     * same reason {@link JvmBuildTools} seams its kotlinc lookup.
     */
    private Function<Path, Optional<Path>> launcherSeam =
            root -> BuildTools.launcher(root, "gradlew", "gradle");

    // -- test seams -------------------------------------------------------

    final void setRunnerForTesting(BuildTools.Runner replacement) {
        this.runner = replacement;
    }

    final void setLauncherForTesting(Function<Path, Optional<Path>> replacement) {
        this.launcherSeam = replacement;
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
        Optional<Path> launcher = launcherSeam.apply(root.get());
        if (launcher.isEmpty()) {
            ctx.showMessage(label + ": Gradle is not available \u2014 no ./gradlew in "
                    + root.get() + " and no gradle on PATH");
            return;
        }
        ctx.showMessage("Running " + label + " in " + root.get() + "\u2026");
        Path work = root.get();
        runner.run(BuildTools.gradleCommand(launcher.get(), tasks), work, timeoutMs,
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
}
