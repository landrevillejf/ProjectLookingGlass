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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
 * The Maven extension: runs the lifecycle goals of the Maven project that owns
 * the current document. Discovery, execution and reporting follow the same
 * contract as {@link GradleBuildTools} (see {@link BuildTools} for the shared
 * rules), because a POM and a {@code build.gradle} fail in different words but
 * need the same handling from an editor.
 *
 * <p><b>Goals.</b> {@code compile}, {@code test}, {@code package} and
 * {@code clean} are plain lifecycle goals run with {@code -B
 * --no-transfer-progress} so the log is the compiler's own text rather than a
 * progress animation. {@code run} is deliberately <em>not</em> {@code
 * exec:java}: the exec plugin is a download that an offline or firewalled build
 * cannot guarantee, so the action compiles with Maven and then launches the
 * main class the POM declares with {@code java -cp target/classes}. That works
 * for a self-contained project without a network; a project that needs its
 * dependency jars on the classpath must declare them itself (the limitation is
 * in the tooltip, not hidden in a failure).</p>
 *
 * <p><b>POM outline.</b> The Structure tab shows the coordinates, packaging,
 * properties, dependencies, build plugins and modules of the POM — parsed with
 * StAX from the live document text, so unsaved edits are reflected and a
 * half-typed POM still outlines everything read before the error.</p>
 */
public final class MavenBuildTools implements TextEditorExtension {

    /** A full build with dependency resolution; Maven's JVM start-up dominates. */
    private static final long BUILD_TIMEOUT_MS = 300_000;
    /** {@code clean} touches no sources but still boots Maven. */
    private static final long QUICK_TIMEOUT_MS = 120_000;
    /** A main that does not terminate is killed at the limit and reported as such. */
    private static final long RUN_TIMEOUT_MS = 120_000;

    /** Where Maven puts compiled classes; the standard layout, not a configurable guess. */
    private static final Path CLASSES = Path.of("target", "classes");

    /**
     * Lifecycle phases that boot Maven but compile nothing, so a task tree run
     * of one gets the short timeout and no diagnostic parsing (the same
     * treatment the toolbar's own {@code clean} action gets).
     */
    private static final Set<String> QUICK_GOALS = Set.of("clean", "validate");

    private EditorContext editor;
    private DocumentContext currentDoc;
    private BuildTools.Runner runner = BuildTools.ASYNC_RUNNER;
    /**
     * Seam over the wrapper/PATH lookup so the "Maven is not available" path is
     * provable on a machine that happens to have Maven installed (see
     * {@link GradleBuildTools} for the same seam and its rationale).
     */
    private Function<Path, Optional<Path>> launcherSeam =
            root -> BuildTools.launcher(root, "mvnw", "mvn");

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
                "lg3d.maven-build",
                "Maven Build",
                "1.0.0",
                "Compile, test, package and run the Maven project owning the current document",
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
        // Re-scan only while the user edits the POM itself, so a phase the POM
        // newly builds updates the tree without re-reading pom.xml on every
        // keystroke in an ordinary source file.
        if (doc != null && BuildTools.isBuildFile(doc.getFileName(), BuildTools.MAVEN_MARKERS)) {
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
        // Ctrl+Alt+Shift letters split with GradleBuildTools: E I P W Q here,
        // G T U C S there. See that class for why the family is used at all.
        return List.of(
                new ToolbarContribution("maven-compile", "Compile",
                        "Run the compile goal; the compiler's errors are listed per file in the Problems panel",
                        this::compile, "control alt shift E"),
                new ToolbarContribution("maven-test", "Run Tests",
                        "Run the test goal through surefire; each failure is filed on its test source line",
                        this::runTests, "control alt shift I"),
                new ToolbarContribution("maven-package", "Package",
                        "Run the package goal, building and testing, then writing the jar to target/",
                        this::packageJar, "control alt shift P"),
                new ToolbarContribution("maven-run", "Run Application",
                        "Compile, then launch the POM's main class with java -cp target/classes"
                                + " (project classes only; declare a wider classpath in the POM if you need one)",
                        this::runApp, "control alt shift W"),
                new ToolbarContribution("maven-clean", "Clean",
                        "Run the clean goal, deleting the target/ directory",
                        this::clean, "control alt shift Q"),
                new ToolbarContribution("maven-outline", "Outline POM",
                        "Outline the POM in the Structure tab: coordinates, properties, dependencies, plugins, modules",
                        this::outlinePom)
        );
    }

    // -- actions -----------------------------------------------------------

    private void compile() {
        runGoals("mvn compile", List.of("compile"), BUILD_TIMEOUT_MS, true);
    }

    private void runTests() {
        runGoals("mvn test", List.of("test"), BUILD_TIMEOUT_MS, true);
    }

    private void packageJar() {
        runGoals("mvn package", List.of("package"), BUILD_TIMEOUT_MS, true);
    }

    private void clean() {
        runGoals("mvn clean", List.of("clean"), QUICK_TIMEOUT_MS, false);
    }

    private void runGoals(String label, List<String> goals, long timeoutMs, boolean diagnose) {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        Optional<Path> root = projectRoot();
        if (root.isEmpty()) {
            ctx.showMessage(label + ": no pom.xml within " + BuildTools.MAX_WALK_UP
                    + " folders above the document \u2014 open a file from inside a Maven project");
            return;
        }
        Optional<Path> launcher = launcherSeam.apply(root.get());
        if (launcher.isEmpty()) {
            ctx.showMessage(label + ": Maven is not available \u2014 no ./mvnw in "
                    + root.get() + " and no mvn on PATH");
            return;
        }
        ctx.showMessage("Running " + label + " in " + root.get() + "\u2026");
        Path work = root.get();
        runner.run(BuildTools.mavenCommand(launcher.get(), goals), work, timeoutMs,
                res -> finished(work, label, res, diagnose));
    }

    /**
     * Compile, then launch the declared main class from the classes Maven just
     * wrote. Two runner calls, chained through the callback exactly like JVM
     * Build Tools' Kotlin run, so a compile failure never reaches the launch and
     * both steps report on the status line.
     */
    private void runApp() {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        Optional<Path> root = projectRoot();
        if (root.isEmpty()) {
            ctx.showMessage("mvn run: no pom.xml within " + BuildTools.MAX_WALK_UP
                    + " folders above the document \u2014 open a file from inside a Maven project");
            return;
        }
        Path work = root.get();
        Optional<Path> launcher = launcherSeam.apply(work);
        if (launcher.isEmpty()) {
            ctx.showMessage("mvn run: Maven is not available \u2014 no ./mvnw in " + work
                    + " and no mvn on PATH");
            return;
        }
        String mainClass = mainClass(work).orElse(null);
        if (mainClass == null) {
            ctx.showMessage("mvn run: the POM declares no main class \u2014 add an"
                    + " <exec.mainClass> property or a <mainClass> to the jar plugin configuration");
            return;
        }
        Optional<Path> java = Toolchain.jdkTool("java");
        if (java.isEmpty()) {
            ctx.showMessage("mvn run: the java launcher was not found in this JDK");
            return;
        }
        ctx.showMessage("Compiling " + work.getFileName() + " for " + mainClass + "\u2026");
        runner.run(BuildTools.mavenCommand(launcher.get(), List.of("compile")), work,
                BUILD_TIMEOUT_MS, cres -> {
            if (!cres.success()) {
                finished(work, "mvn compile", cres, true);
                return;
            }
            if (!Files.isRegularFile(work.resolve(CLASSES).resolve(
                    mainClass.replace('.', '/') + ".class"))) {
                ctx.showMessage("mvn run: " + mainClass + " is not in target/classes"
                        + " \u2014 check the main class against the sources the compile produced");
                return;
            }
            ctx.showMessage("Running " + mainClass + "\u2026");
            runner.run(BuildTools.javaClasspathRunCommand(java.get(),
                            work.resolve(CLASSES), mainClass), work, RUN_TIMEOUT_MS,
                    rres -> finished(work, "mvn run", rres, false));
        });
    }

    /** Files one finished goal's output, findings and status line. EDT callback. */
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
        ctx.showMessage(BuildTools.statusLine(label, res, diags));
    }

    /** Outline the POM: the open document when it is a pom.xml, else the project's. */
    private void outlinePom() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        String text = doc.getFullText();
        String path = doc.getFilePath();
        String label = doc.getFileName();
        if (!BuildTools.isBuildFile(label, BuildTools.MAVEN_MARKERS)) {
            Optional<Path> pom = projectRoot().map(r -> r.resolve("pom.xml"));
            Optional<String> onDisk = pom.flatMap(BuildTools::readBuildFile);
            if (onDisk.isEmpty()) {
                ctx.showMessage("Outline POM: the document is not a pom.xml and the"
                        + " project has none to read");
                return;
            }
            text = onDisk.get();
            path = pom.get().toString();
            label = "pom.xml";
        }
        List<StructureSymbol> rows = BuildTools.pomOutline(text);
        ctx.showStructure(path, rows);
        ctx.showMessage(rows.isEmpty()
                ? "Outline " + label + ": no entries \u2014 the POM has nothing to outline yet"
                : "Outline " + label + ": " + rows.size()
                        + " entries (coordinates, properties, dependencies, plugins, modules)");
    }

    // -- helpers -------------------------------------------------------------

    private Optional<Path> projectRoot() {
        String filePath = (currentDoc == null) ? null : currentDoc.getFilePath();
        return BuildTools.searchStart(filePath)
                .flatMap(start -> BuildTools.projectRoot(start, BuildTools.MAVEN_MARKERS));
    }

    // -- task tree -----------------------------------------------------------

    /**
     * Scans the open project's {@code pom.xml} and publishes the lifecycle
     * goals it proposes to the editor's Tasks tree, each wired to the same
     * {@link #runGoals} path the toolbar actions use — so a goal run from the
     * tree logs, files findings and reports on the status line exactly like its
     * button. A directory that is not a Maven project publishes nothing, which
     * leaves any other extension's tree (a Gradle project's) untouched. Called
     * on the EDT from the document hooks; the scan reads one small file at most.
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
        List<BuildTask> discovered = BuildTools.mavenTaskTree(pomText(project));
        List<BuildTask> wired = new ArrayList<>(discovered.size());
        for (BuildTask task : discovered) {
            String name = task.name();
            wired.add(task.withAction(() -> runGoals("mvn " + name, List.of(name),
                    timeoutFor(name), diagnoseFor(name))));
        }
        Path fileName = project.getFileName();
        ctx.showTasks((fileName == null) ? project.toString() : fileName.toString(), wired);
    }

    /**
     * The POM text: the open document when it <em>is</em> a {@code pom.xml} (so
     * unsaved edits are scanned), else the project's own from disk, else empty.
     */
    private String pomText(Path root) {
        DocumentContext doc = currentDoc;
        if (doc != null && BuildTools.isBuildFile(doc.getFileName(), BuildTools.MAVEN_MARKERS)) {
            return doc.getFullText();
        }
        return BuildTools.readBuildFile(root.resolve("pom.xml")).orElse("");
    }

    /** The run timeout for a goal: quick goals only configure, the rest compile. */
    private static long timeoutFor(String goal) {
        return QUICK_GOALS.contains(goal) ? QUICK_TIMEOUT_MS : BUILD_TIMEOUT_MS;
    }

    /** True when a goal's output should be parsed for compiler/test findings. */
    private static boolean diagnoseFor(String goal) {
        return !QUICK_GOALS.contains(goal);
    }

    /**
     * The main class to launch: the POM in the editor when one is open (so an
     * unsaved edit is honoured), otherwise the project's {@code pom.xml}.
     */
    private Optional<String> mainClass(Path root) {
        DocumentContext doc = currentDoc;
        if (doc != null && BuildTools.isBuildFile(doc.getFileName(), BuildTools.MAVEN_MARKERS)) {
            Optional<String> fromDoc = BuildTools.mainClassFromPom(doc.getFullText());
            if (fromDoc.isPresent()) {
                return fromDoc;
            }
        }
        return BuildTools.readBuildFile(root.resolve("pom.xml"))
                .flatMap(BuildTools::mainClassFromPom);
    }
}
