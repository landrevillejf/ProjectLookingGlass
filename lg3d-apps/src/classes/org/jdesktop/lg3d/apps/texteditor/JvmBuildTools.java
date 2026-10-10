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
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "JVM Build Tools" extension: compiles, runs and debug-checks
 * the current document with the real JDK toolchain, filed under the
 * {@code Java/Kotlin} category next to {@link JavaDevTools}.
 *
 * <p><b>Compile / run</b> shell out to the running JDK's own {@code javac}
 * and to the JDK 11+ single-file source launcher, so a document works
 * whether or not it was ever saved: the current text is staged into a fresh
 * temp directory and built there, never touching the user's file.
 * <b>Debug</b> is a crash-analysis run: assertions are enabled ({@code -ea}),
 * the output is scanned for the exception heading and the first stack frame
 * that lands in this file, and the status line reports
 * {@code "Debug: <exception> at Foo.java:<line>"} with the full trace kept in
 * the output console. (An interactive breakpoint debugger is a separate
 * future feature; this is the honest in-editor subset.)</p>
 *
 * <p><b>Kotlin</b> actions drive {@code kotlinc}/{@code kotlin} from
 * {@code PATH}; when the compiler is not installed the action degrades to a
 * clear status message and starts no process.</p>
 *
 * <p>Processes run on a virtual thread and the result is delivered back on
 * the EDT, so the editor never blocks; a package-private {@link Runner} seam
 * keeps every action headless-testable with a synchronous fake. Every run,
 * success included, writes a block to the editor's south {@link OutputConsole}
 * (through the FILE_IO-gated {@code EditorContext.showOutput} capability) so
 * the console always reflects the latest build &mdash; the document itself is
 * never written to, and a legacy {@code // ---- <tool> output ----} comment
 * block left in a document by earlier builds is stripped automatically (WRITE)
 * on the next action. Progress and exit codes always land on the status
 * line.</p>
 */
public final class JvmBuildTools implements TextEditorExtension {

    /** How this provider fires a tool command and receives its result. */
    interface Runner {
        void run(List<String> command, Path workDir, long timeoutMs,
                 Consumer<Toolchain.Result> done);
    }

    /** Production runner: execute on a virtual thread, deliver on the EDT. */
    private static final Runner ASYNC_RUNNER = (command, workDir, timeoutMs, done) ->
            Thread.ofVirtual().start(() -> {
                Toolchain.Result result = Toolchain.execute(command, workDir, timeoutMs);
                SwingUtilities.invokeLater(() -> done.accept(result));
            });

    private static final long COMPILE_TIMEOUT_MS = 60_000;
    private static final long RUN_TIMEOUT_MS = 30_000;

    private DocumentContext currentDoc;
    private EditorContext editor;
    private Runner runner = ASYNC_RUNNER;
    /** Seam over the kotlinc lookup so tests never depend on a real install. */
    private Supplier<Optional<Path>> kotlinToolSeam =
            () -> Toolchain.findOnPath("kotlinc", System.getenv("PATH"));

    // -- test seams -------------------------------------------------------

    final void setRunnerForTesting(Runner replacement) {
        this.runner = replacement;
    }

    final void setKotlinToolForTesting(Supplier<Optional<Path>> replacement) {
        this.kotlinToolSeam = replacement;
    }

    // -- SPI ---------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.FILE_IO,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.jvm-build-tools",
                "JVM Build Tools",
                "1.0.0",
                "Compile, run and debug-check Java and Kotlin sources with the JDK toolchain",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Java/Kotlin";
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
        // The plain Ctrl+Alt+[A-Z0-9] space is exhausted by the other twelve
        // providers (only Ctrl+Alt+0 was left), so the build actions claim a
        // distinct Ctrl+Alt+Shift family — collision-free and still rebindable.
        return List.of(
                new ToolbarContribution("jvm-compile-java", "Compile Java",
                        "javac the current document into a temp dir; errors land in the Output panel",
                        this::compileJava, "control alt shift J"),
                new ToolbarContribution("jvm-run-java", "Run Java",
                        "Run the document with the single-file source launcher; output lands in the Output panel",
                        this::runJava, "control alt shift R"),
                new ToolbarContribution("jvm-debug-java", "Debug Java",
                        "Run with assertions on; report the exception and its line, trace in the Output panel",
                        this::debugJava, "control alt shift D"),
                new ToolbarContribution("jvm-compile-kotlin", "Compile Kotlin",
                        "kotlinc the current document (needs the Kotlin compiler on PATH)",
                        this::compileKotlin, "control alt shift K"),
                new ToolbarContribution("jvm-run-kotlin", "Run Kotlin",
                        "kotlinc, then run the compiled MainKt (needs the Kotlin compiler on PATH)",
                        this::runKotlin, "control alt shift L"),
                new ToolbarContribution("jvm-clean-output", "Clean Output",
                        "Clear the editor's Output panel",
                        this::cleanOutput, "control alt shift X")
        );
    }

    // -- actions -----------------------------------------------------------

    private void compileJava() {
        String text = guardedJavaText("Compile Java");
        if (text == null) {
            return;
        }
        String cls = Toolchain.deriveClassName(text);
        if (cls == null) {
            msg("Compile Java: no class/record/enum/interface found");
            return;
        }
        Optional<Path> javac = Toolchain.jdkTool("javac");
        if (javac.isEmpty()) {
            msg("Compile Java: javac not found in this JDK");
            return;
        }
        stripLegacyBlock();
        Path work = stage(text, cls + ".java");
        if (work == null) {
            return;
        }
        msg("Compiling " + cls + "\u2026");
        runner.run(Toolchain.javacCommand(javac.get(), work.resolve(cls + ".java"),
                        work.resolve("classes")), work, COMPILE_TIMEOUT_MS, res -> {
            if (res.success()) {
                output("javac", "OK \u2014 " + cls + " compiled, no errors");
                msg(cls + " compiled \u2014 OK");
            } else {
                output("javac", res.output());
                msg(cls + " failed (exit " + res.exitCode() + ") \u2014 see the Output panel");
            }
        });
    }

    private void runJava() {
        String text = guardedJavaText("Run Java");
        if (text == null) {
            return;
        }
        String cls = Toolchain.deriveClassName(text);
        if (cls == null) {
            msg("Run Java: no class found in the document");
            return;
        }
        Optional<Path> java = Toolchain.jdkTool("java");
        if (java.isEmpty()) {
            msg("Run Java: the java launcher not found in this JDK");
            return;
        }
        stripLegacyBlock();
        Path work = stage(text, cls + ".java");
        if (work == null) {
            return;
        }
        msg("Running " + cls + "\u2026");
        runner.run(Toolchain.sourceRunCommand(java.get(), work.resolve(cls + ".java"), false),
                work, RUN_TIMEOUT_MS, res -> {
            output("java", res.output());
            msg(cls + " exited " + res.exitCode() + " \u2014 output captured");
        });
    }

    private void debugJava() {
        String text = guardedJavaText("Debug Java");
        if (text == null) {
            return;
        }
        String cls = Toolchain.deriveClassName(text);
        if (cls == null) {
            msg("Debug Java: no class found in the document");
            return;
        }
        Optional<Path> java = Toolchain.jdkTool("java");
        if (java.isEmpty()) {
            msg("Debug Java: the java launcher not found in this JDK");
            return;
        }
        stripLegacyBlock();
        Path work = stage(text, cls + ".java");
        if (work == null) {
            return;
        }
        msg("Debug running " + cls + " (assertions on)\u2026");
        runner.run(Toolchain.sourceRunCommand(java.get(), work.resolve(cls + ".java"), true),
                work, RUN_TIMEOUT_MS, res -> {
            OptionalInt line = Toolchain.firstFrameLine(cls + ".java", res.output());
            if (line.isPresent()) {
                output("java debug", res.output());
                String ex = Toolchain.firstExceptionMessage(res.output());
                msg("Debug: " + (ex.isEmpty() ? "stopped" : ex)
                        + " at " + cls + ".java:" + line.getAsInt());
            } else if (!res.success()) {
                output("java debug", res.output());
                msg("Debug: exit " + res.exitCode() + " \u2014 see the Output panel");
            } else {
                output("java debug", "clean \u2014 " + cls
                        + " exited 0, assertions enabled");
                msg("Debug run clean (" + cls + " exited 0, assertions enabled)");
            }
        });
    }

    private void compileKotlin() {
        String text = guardedKotlinText("Compile Kotlin");
        if (text == null) {
            return;
        }
        Optional<Path> kotlinc = requireKotlinc("Compile Kotlin");
        if (kotlinc.isEmpty()) {
            return;
        }
        String fileName = kotlinFileName();
        stripLegacyBlock();
        Path work = stage(text, fileName);
        if (work == null) {
            return;
        }
        msg("Compiling " + fileName + "\u2026");
        runner.run(Toolchain.kotlincCommand(kotlinc.get(), work.resolve(fileName),
                        work.resolve("classes")), work, COMPILE_TIMEOUT_MS, res -> {
            if (res.success()) {
                output("kotlinc", "OK \u2014 " + fileName + " compiled, no errors");
                msg(fileName + " compiled \u2014 OK");
            } else {
                output("kotlinc", res.output());
                msg("Kotlin compile failed (exit " + res.exitCode() + ") \u2014 see the Output panel");
            }
        });
    }

    private void runKotlin() {
        String text = guardedKotlinText("Run Kotlin");
        if (text == null) {
            return;
        }
        Optional<Path> kotlinc = requireKotlinc("Run Kotlin");
        if (kotlinc.isEmpty()) {
            return;
        }
        String fileName = kotlinFileName();
        String mainClass = Toolchain.kotlinMainClass(fileName);
        stripLegacyBlock();
        Path work = stage(text, fileName);
        if (work == null) {
            return;
        }
        Path classes = work.resolve("classes");
        msg("Compiling and running " + mainClass + "\u2026");
        runner.run(Toolchain.kotlincCommand(kotlinc.get(), work.resolve(fileName), classes),
                work, COMPILE_TIMEOUT_MS, cres -> {
            if (!cres.success()) {
                output("kotlinc", cres.output());
                msg("Kotlin compile failed (exit " + cres.exitCode() + ") \u2014 see the Output panel");
                return;
            }
            runner.run(Toolchain.kotlinRunCommand(Toolchain.kotlinLauncher(kotlinc.get()),
                            classes, mainClass), work, RUN_TIMEOUT_MS, rres -> {
                output("kotlin", rres.output());
                msg(mainClass + " exited " + rres.exitCode() + " \u2014 output captured");
            });
        });
    }

    private void cleanOutput() {
        boolean stripped = stripLegacyBlock();
        if (editor != null) {
            editor.clearOutput();
        }
        msg(stripped
                ? "Clean Output: console cleared and the legacy output block removed"
                : "Clean Output: the Output panel was cleared");
    }

    // -- helpers -------------------------------------------------------------

    /** @return the document text, or null after reporting why it cannot build. */
    private String guardedJavaText(String action) {
        if (currentDoc == null) {
            return null;
        }
        String text = currentDoc.getFullText();
        if (text.isBlank()) {
            msg(action + ": the document is empty");
            return null;
        }
        return text;
    }

    /** Kotlin documents may legitimately have no {@code class} keyword. */
    private String guardedKotlinText(String action) {
        return guardedJavaText(action);
    }

    private Optional<Path> requireKotlinc(String action) {
        Optional<Path> kotlinc = kotlinTool();
        if (kotlinc.isEmpty()) {
            msg(action + ": kotlinc is not on PATH \u2014 install the Kotlin compiler "
                    + "to enable this action");
        }
        return kotlinc;
    }

    /** Seam-friendly kotlinc lookup (tests inject the whole lookup). */
    private Optional<Path> kotlinTool() {
        return kotlinToolSeam.get();
    }

    private String kotlinFileName() {
        String name = currentDoc.getFileName();
        if (name != null && (name.endsWith(".kt") || name.endsWith(".kts"))) {
            return name;
        }
        return "Main.kt";
    }

    private Path stage(String text, String fileName) {
        try {
            return Toolchain.stageSource(text, fileName);
        } catch (java.io.IOException ioe) {
            msg("Could not stage the document: " + ioe.getMessage());
            return null;
        }
    }

    /**
     * Migration: strips a legacy trailing {@code // ---- <tool> output ----}
     * comment block (written into the document by earlier builds of this
     * extension) so the console is the single output surface. WRITE-gated:
     * a silent no-op when the user revoked it.
     *
     * @return true when a legacy block was removed
     */
    private boolean stripLegacyBlock() {
        DocumentContext doc = currentDoc;
        if (doc == null) {
            return false;
        }
        String text = doc.getFullText();
        String stripped = Toolchain.removeTrailingBlock(text);
        if (stripped.equals(text)) {
            return false;
        }
        doc.setFullText(stripped);
        return true;
    }

    /** Sends one tool run's output to the editor's south Output console. */
    private void output(String tool, String body) {
        EditorContext ctx = editor;
        if (ctx != null) {
            ctx.showOutput(tool + " output", body);
        }
    }

    private void msg(String text) {
        EditorContext ctx = editor;
        if (ctx != null) {
            ctx.showMessage(text);
        }
    }
}
