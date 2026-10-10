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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;
import org.jdesktop.lg3d.mandela.api.EditorServices;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Runtime;

/**
 * The bundled "Mandela" extension: Espresso's support for the desktop's own
 * scripting language. It gives a {@code .mnd} buffer the four things a developer
 * needs to work in a new language &mdash; squiggles for what is wrong, an outline
 * of what is there, candidates for what is next, and a way to run it &mdash; and it
 * gets all four from the language rather than from an editor-local model of it.
 *
 * <p>That last distinction is the whole design. The analysis is
 * {@link EditorServices} and {@code Mandela.check()}, the real parser and the real
 * standard library; nothing here knows Mandela's grammar. When the language gains a
 * statement form, the outline and the completion list gain it in the same jar bump,
 * with no change to this file. An extension that carried its own regex model of
 * Mandela would silently start lying to the developer instead.</p>
 *
 * <p><b>Analysis</b> runs off the EDT on a virtual thread and is delivered through
 * a {@link Scheduler} seam; a generation counter drops a result as soon as a newer
 * edit has started, so typing never waits for a parse and a stale squiggle never
 * paints. One pass fills all three surfaces at once, because they read the same
 * syntax tree. A document that is not Mandela clears all three: an outline left
 * over from the previous file is worse than an empty panel.</p>
 *
 * <p><b>Run</b> executes the buffer <em>in this JVM</em> with the language's
 * default console sandbox &mdash; no filesystem, no network, and an instruction
 * budget, so a stray {@code loop { }} stops instead of eating the desktop. Output
 * goes to the editor's Output panel, never to a terminal the user is not looking
 * at. A fresh engine is built per run so a run cannot inherit globals from the
 * previous one; the check path shares its engine because checking mutates nothing.
 * The only time this extension touches the text is the one action that is asked
 * for it and only into an empty buffer, and without the {@code WRITE} grant it
 * prints the template to the Output panel instead of pretending to insert it.</p>
 */
public final class MandelaTools implements TextEditorExtension {

    /** The id in the extension manager and in the services file. */
    static final String ID = "lg3d.mandela";

    /** What one buffer produces: the three panels Mandela can fill. */
    record Analysis(List<Diagnostic> diagnostics, List<StructureSymbol> structure,
                    List<String> completions) {

        /** What a non-Mandela document gets: everything cleared. */
        static final Analysis EMPTY = new Analysis(List.of(), List.of(), List.of());
    }

    /** Runs an analysis off the EDT and delivers it back on the EDT. */
    interface Scheduler {
        void schedule(Supplier<Analysis> compute, Consumer<Analysis> onEdt);
    }

    /** Runs a program and reports what its console said. */
    interface Runner {
        void run(String source, String sourceName, Consumer<RunResult> done);
    }

    /** One run's output, with the failure text empty when the program finished. */
    record RunResult(String output, String failure) {

        /** @return true when the program did not complete. */
        boolean failed() {
            return failure != null && !failure.isEmpty();
        }
    }

    /** Production scheduler: compute on a virtual thread, deliver on the EDT. */
    private static final Scheduler ASYNC_SCHEDULER = (compute, onEdt) ->
            Thread.ofVirtual().start(() -> {
                Analysis result;
                try {
                    result = compute.get();
                } catch (RuntimeException re) {
                    result = null; // a failed analysis clears rather than sticks
                }
                final Analysis delivered = result;
                SwingUtilities.invokeLater(() -> onEdt.accept(delivered));
            });

    private static final Scheduler SYNC_SCHEDULER =
            (compute, onEdt) -> onEdt.accept(compute.get());

    private final AtomicLong generation = new AtomicLong();

    private EditorContext editor;
    private DocumentContext currentDoc;
    private Scheduler scheduler = ASYNC_SCHEDULER;
    private Runner runner = MandelaTools::runAsync;

    /**
     * The engine the check and bytecode paths share. It is never asked to run a
     * program, so it holds no state a run could corrupt and needs no sandbox
     * decision beyond the default.
     */
    private volatile Runtime checker;

    // -- test seams ---------------------------------------------------------

    /**
     * Replaces the scheduler; a null argument restores the production one. Tests
     * pass {@link #syncScheduler()} to stay on the calling thread.
     */
    final void setSchedulerForTesting(Scheduler replacement) {
        this.scheduler = (replacement != null) ? replacement : ASYNC_SCHEDULER;
    }

    final void setRunnerForTesting(Runner replacement) {
        this.runner = (replacement != null) ? replacement : MandelaTools::runAsync;
    }

    /** @return the seam value that runs the analysis on the calling thread. */
    static Scheduler syncScheduler() {
        return SYNC_SCHEDULER;
    }

    // -- SPI ----------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        // WRITE is asked for by one action only (the starter template into an empty
        // buffer) and is not needed for analysis, run or the panels, so a user who
        // declines it loses that one convenience and nothing else.
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.FILE_IO,
                TextEditorPermission.TOOLBAR,
                TextEditorPermission.DIAGNOSE
        );
        return new TextEditorManifest(
                ID,
                "Mandela",
                "1.0.0",
                "Live analysis, outline, completion and in-JVM run for the Mandela"
                        + " scripting language (.mnd)",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Mandela";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
        analyze(doc);
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
        analyze(doc);
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
        analyze(doc);
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        // The plain Ctrl+Alt+[A-Z0-9] family is exhausted and the Ctrl+Alt+Shift
        // family already holds J R D K L X (JVM Build Tools), so Mandela claims the
        // free M (run), A (check) and B (bytecode) of that same family.
        return List.of(
                new ToolbarContribution("mandela-run", "Run Mandela",
                        "Run the document in this JVM inside the console sandbox;"
                                + " output lands in the Output panel",
                        this::runScript, "control alt shift M"),
                new ToolbarContribution("mandela-check", "Check Mandela",
                        "Parse and compile the document without running it; problems"
                                + " land in the Problems panel",
                        this::checkScript, "control alt shift A"),
                new ToolbarContribution("mandela-bytecode", "Mandela Bytecode",
                        "Show the compiled instruction stream for the document",
                        this::showBytecode, "control alt shift B"),
                new ToolbarContribution("mandela-new", "Mandela Template",
                        "Replace an empty document with a starter Mandela script",
                        this::insertTemplate));
    }

    // -- actions ------------------------------------------------------------

    /** Runs the current document and hands its console output to the Output panel. */
    private void runScript() {
        DocumentContext doc = currentDoc;
        if (doc == null || !isMandela(doc.getFileName(), doc.getFullText())) {
            msg("Run Mandela: the current document is not a Mandela script");
            return;
        }
        String source = doc.getFullText();
        if (source.isBlank()) {
            msg("Run Mandela: the document is empty");
            return;
        }
        String name = sourceName(doc);
        msg("Running " + name + "\u2026");
        runner.run(source, name, result -> {
            output("mandela", consoleText(result));
            msg(result.failed()
                    ? name + " stopped \u2014 see the Output panel"
                    : name + " finished");
        });
    }

    /** Checks on demand, so the status line reports the count the panel shows. */
    private void checkScript() {
        DocumentContext doc = currentDoc;
        if (doc == null || !isMandela(doc.getFileName(), doc.getFullText())) {
            msg("Check Mandela: the current document is not a Mandela script");
            return;
        }
        String source = doc.getFullText();
        if (source.isBlank()) {
            msg("Check Mandela: the document is empty");
            return;
        }
        Analysis found = analyse(source, sourceName(doc), doc.getFilePath(),
                doc.getCaretOffset());
        paint(doc, found);
        msg(found.diagnostics().isEmpty()
                ? "Check Mandela: no problems found"
                : "Check Mandela: " + found.diagnostics().size() + " problem(s)");
    }

    /** Dumps the instruction stream, which is what {@code mandela dump} prints. */
    private void showBytecode() {
        DocumentContext doc = currentDoc;
        if (doc == null || !isMandela(doc.getFileName(), doc.getFullText())) {
            msg("Mandela Bytecode: the current document is not a Mandela script");
            return;
        }
        String source = doc.getFullText();
        if (source.isBlank()) {
            msg("Mandela Bytecode: the document is empty");
            return;
        }
        try {
            output("mandela bytecode", checker().disassemble(source));
            msg("Mandela Bytecode: instruction stream written to the Output panel");
        } catch (RuntimeException broken) {
            // An unparsable buffer has no bytecode. The compiler's own words are
            // the useful answer, so they go to the panel rather than the status line.
            output("mandela bytecode", String.valueOf(broken.getMessage()));
            msg("Mandela Bytecode: the document does not compile");
        }
    }

    /**
     * Writes a starter template into the buffer. Offered only for an empty
     * document, because replacing text a developer has written is the one thing
     * an analysis extension must never do.
     */
    private void insertTemplate() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        if (!doc.getFullText().isBlank()) {
            msg("Mandela Template: only an empty document gets a starter script");
            return;
        }
        String script = EditorServices.starterTemplates().get("script");
        if (script == null) {
            return;
        }
        if (!ctx.has(TextEditorPermission.WRITE)) {
            // A gated write is a silent no-op, and reporting success on one would
            // be a lie the developer has to discover. Show the text instead.
            output("mandela template", script);
            msg("Mandela Template: WRITE was not granted, so the starter script is"
                    + " in the Output panel");
            return;
        }
        doc.setFullText(script);
        msg("Mandela Template: starter script inserted");
    }

    // -- analysis -----------------------------------------------------------

    /**
     * Kicks off the three-panel refresh for {@code doc}. A non-Mandela document
     * clears the panels immediately and starts no work.
     */
    private void analyze(DocumentContext doc) {
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String path = doc.getFilePath();
        if (!isMandela(doc.getFileName(), doc.getFullText())) {
            generation.incrementAndGet(); // drop anything already in flight
            paint(doc, Analysis.EMPTY);
            return;
        }
        final long myGen = generation.incrementAndGet();
        final String source = doc.getFullText();
        final String name = sourceName(doc);
        scheduler.schedule(
                () -> analyse(source, name, path, doc.getCaretOffset()),
                found -> {
                    if (myGen != generation.get()) {
                        return; // a newer edit superseded this run
                    }
                    paint(doc, (found == null) ? Analysis.EMPTY : found);
                });
    }

    /** Hands one analysis to the three surfaces it belongs to. */
    private void paint(DocumentContext doc, Analysis found) {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        String path = doc.getFilePath();
        ctx.reportDiagnostics(path, found.diagnostics());
        ctx.showStructure(path, found.structure());
        ctx.publishCompletions(path, found.completions());
    }

    /**
     * One pass over one buffer: what is wrong, what is declared and what comes
     * next. Package-private and static so it is headless-testable without an
     * editor, an EDT or a thread.
     *
     * @param source the whole buffer
     * @param name   the name problems are reported under
     * @param path   the document path the editor keys its panels on
     * @param caret  the 0-based caret, which decides the completion candidates
     * @return the three panel payloads
     */
    static Analysis analyse(String source, String name, String path, int caret) {
        List<Diagnostic> problems = new ArrayList<>();
        for (org.jdesktop.lg3d.mandela.lang.Diagnostic finding
                : Mandela.check(source, name)) {
            problems.add(map(path, finding));
        }
        List<StructureSymbol> outline = new ArrayList<>();
        for (EditorServices.Entry entry : EditorServices.outline(source)) {
            outline.add(new StructureSymbol(entry.name(), entry.kind(), entry.line()));
        }
        return new Analysis(List.copyOf(problems), List.copyOf(outline),
                EditorServices.complete(source, caret));
    }

    /**
     * Maps one language diagnostic onto the editor's value type. The two records
     * carry the same fields on purpose (see {@code mandela.lang.Diagnostic}), so
     * this is a rename, not a reinterpretation: a range stays a range and a hint
     * stays a hint.
     */
    static Diagnostic map(String path,
                          org.jdesktop.lg3d.mandela.lang.Diagnostic finding) {
        String message = finding.message();
        if (!finding.rule().isEmpty()) {
            message = message + " [" + finding.rule() + "]";
        }
        return new Diagnostic(path, finding.line(), finding.column(),
                finding.endLine(), finding.endColumn(), kind(finding.severity()),
                message);
    }

    /** The editor severity that matches the language's. */
    static Diagnostic.Kind kind(org.jdesktop.lg3d.mandela.lang.Diagnostic.Severity from) {
        if (from == null) {
            return Diagnostic.Kind.ERROR;
        }
        return switch (from) {
            case ERROR -> Diagnostic.Kind.ERROR;
            case WARNING -> Diagnostic.Kind.WARNING;
            case INFO -> Diagnostic.Kind.INFO;
            case HINT -> Diagnostic.Kind.HINT;
        };
    }

    /**
     * @return true when this buffer should be treated as Mandela: a {@code .mnd}
     *         name, or an extension-less file whose first lines carry a Mandela
     *         shebang (the shape of an executable script)
     */
    static boolean isMandela(String fileName, String source) {
        String name = (fileName == null) ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith("." + Mandela.FILE_EXTENSION)) {
            return true;
        }
        if (source == null || !source.startsWith("#!")) {
            return false;
        }
        String head = source.substring(0, Math.min(source.length(), 200))
                .toLowerCase(java.util.Locale.ROOT);
        int newline = head.indexOf('\n');
        String shebang = (newline < 0) ? head : head.substring(0, newline);
        return shebang.contains("mandela") && !name.contains(".");
    }

    /** @return the name a buffer's problems are reported under. */
    private static String sourceName(DocumentContext doc) {
        String name = doc.getFileName();
        return (name == null || name.isBlank()) ? EditorServices.BUFFER : name;
    }

    private Runtime checker() {
        Runtime existing = checker;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (checker == null) {
                // Console capabilities, no sinks: checking runs nothing, and the
                // default sandbox is the one that cannot hurt anyone.
                checker = Mandela.engine().build();
            }
            return checker;
        }
    }

    // -- the in-JVM runner --------------------------------------------------

    /** Production runner: execute off the EDT, deliver on it. */
    private static void runAsync(String source, String name, Consumer<RunResult> done) {
        Thread.ofVirtual().start(() -> {
            RunResult result = runInProcess(source, name);
            SwingUtilities.invokeLater(() -> done.accept(result));
        });
    }

    /**
     * Runs one program in this JVM and captures its console.
     *
     * <p>A fresh engine per run, so a script cannot see the globals its previous
     * run left behind and two runs cannot share a stack. The engine carries the
     * language's default sandbox: no files, no network, a bounded instruction
     * count. Whatever the program printed is shown even when it failed part way,
     * because the half-output is usually the clue.</p>
     *
     * @param source the program text
     * @param name   the name failures are reported under
     * @return the console text and, when the program did not finish, why
     */
    static RunResult runInProcess(String source, String name) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        try {
            Mandela.engine()
                    .output(line -> append(out, line))
                    .error(line -> append(err, line))
                    .build()
                    .run(source, name);
            return new RunResult(out.toString(), err.toString());
        } catch (RuntimeException failure) {
            // A parse error, a compile error and a thrown script error all arrive
            // here as the language's own exceptions with the author's words in them.
            String message = String.valueOf(failure.getMessage());
            if (!message.isEmpty() && !message.endsWith("\n")) {
                message = message + "\n";
            }
            return new RunResult(out.toString(), err + message);
        }
    }

    /**
     * One run's console text: what the program printed, then why it stopped. The
     * two are separate buffers inside the engine but one panel for the developer,
     * and a half-line of output must not glue onto the first word of the error.
     */
    static String consoleText(RunResult result) {
        String printed = result.output();
        String stopped = result.failure();
        if (printed.isEmpty()) {
            return stopped;
        }
        if (stopped.isEmpty()) {
            return printed;
        }
        return printed + (printed.endsWith("\n") ? "" : "\n") + stopped;
    }

    /**
     * Appends one console delivery to a buffer. The engine's sink hands over a
     * whole line <em>with</em> its terminator (that is what {@code println} does),
     * so adding another here would double-space the Output panel; the unterminated
     * {@code print} case is the one that still needs the closing newline.
     */
    private static void append(StringBuilder target, String line) {
        if (line == null) {
            return;
        }
        target.append(line);
        if (!line.isEmpty() && !line.endsWith("\n")) {
            target.append('\n');
        }
    }

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
