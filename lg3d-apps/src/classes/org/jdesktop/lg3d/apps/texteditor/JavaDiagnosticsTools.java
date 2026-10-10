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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;

/**
 * The bundled "Java Diagnostics" extension: the live half of the Java/Kotlin IDE
 * surface. It compiles the <em>current</em> document with the running JDK's own
 * compiler and reports every problem back to the editor as {@link Diagnostic}s,
 * which the host paints as gutter dots, wavy underlines and Problems-panel rows
 * (the Phase-0 chrome).
 *
 * <p><b>Java</b> is compiled in memory through
 * {@link ToolProvider#getSystemJavaCompiler()} with a {@link DiagnosticCollector};
 * the source never touches the disk and class output is dropped into a throwaway
 * directory. <b>Kotlin</b> is analysed by shelling out to {@code kotlinc} from
 * {@code PATH} and parsing its {@code file:line:col: severity: message} output;
 * with no Kotlin install it degrades to an empty result and starts no process.</p>
 *
 * <p>Analysis runs on a virtual thread and is delivered back on the EDT through a
 * {@link Scheduler} seam; a monotonically increasing generation counter drops a
 * result whenever a newer edit has started, so typing never blocks and stale
 * diagnostics never paint. Every path is headless-testable: the Java compile is
 * synchronous and self-contained, and the scheduler, {@code kotlinc} lookup and
 * process runner are injectable seams.</p>
 */
public final class JavaDiagnosticsTools implements TextEditorExtension {

    /** Classic {@code file.kt:L:C: error: msg} output line. */
    private static final Pattern KOTLIN_CLASSIC =
            Pattern.compile("^(.*\\.(?:kt|kts)):(\\d+):(\\d+):\\s*(error|warning|info):\\s*(.+)$");
    /** Newer {@code e: file.kt: (L, C): msg} / {@code w:} output line. */
    private static final Pattern KOTLIN_SHORT =
            Pattern.compile("^[ew]:\\s*(.*\\.(?:kt|kts)):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(.+)$");

    /** Runs a computation off the EDT and delivers its result back on it. */
    interface Scheduler {
        void schedule(Supplier<List<Diagnostic>> compute, Consumer<List<Diagnostic>> onEdt);
    }

    /** Runs a tool command to completion (blocking; called off the EDT). */
    interface CommandRunner {
        Toolchain.Result run(List<String> command, Path workDir, long timeoutMs);
    }

    /** The languages live diagnostics understand. */
    enum Lang { JAVA, KOTLIN, NONE }

    private static final Scheduler ASYNC_SCHEDULER = (compute, onEdt) ->
            Thread.ofVirtual().start(() -> {
                List<Diagnostic> result;
                try {
                    result = compute.get();
                } catch (RuntimeException re) {
                    result = null; // a failed analysis clears rather than sticks
                }
                final List<Diagnostic> delivered = result;
                SwingUtilities.invokeLater(() -> onEdt.accept(delivered));
            });

    private static final long KOTLINC_TIMEOUT_MS = 60_000;

    private final AtomicLong generation = new AtomicLong();

    private EditorContext editor;
    private Scheduler scheduler = ASYNC_SCHEDULER;
    private CommandRunner runner = Toolchain::execute;
    private Supplier<Optional<Path>> kotlinToolSeam =
            () -> Toolchain.findOnPath("kotlinc", System.getenv("PATH"));

    // -- test seams -------------------------------------------------------

    final void setSchedulerForTesting(Scheduler replacement) {
        this.scheduler = (replacement != null) ? replacement : ASYNC_SCHEDULER;
    }

    final void setCommandRunnerForTesting(CommandRunner replacement) {
        this.runner = (replacement != null) ? replacement : Toolchain::execute;
    }

    final void setKotlinToolForTesting(Supplier<Optional<Path>> replacement) {
        this.kotlinToolSeam = (replacement != null)
                ? replacement
                : () -> Toolchain.findOnPath("kotlinc", System.getenv("PATH"));
    }

    // -- SPI ---------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.DIAGNOSE
        );
        return new TextEditorManifest(
                "lg3d.java-diagnostics",
                "Java Diagnostics",
                "1.0.0",
                "Live compile diagnostics for Java (in-memory javac) and Kotlin (kotlinc)",
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
        analyze(doc);
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        analyze(doc);
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        analyze(doc);
    }

    // -- analysis ----------------------------------------------------------

    /**
     * Kicks off an analysis for {@code doc}. Non-JVM files clear any previous
     * diagnostics; a stale in-flight result is dropped by the generation counter
     * so only the newest edit paints.
     */
    private void analyze(DocumentContext doc) {
        EditorContext ctx = editor;
        if (doc == null || ctx == null) {
            return;
        }
        String path = doc.getFilePath();
        String fileName = doc.getFileName();
        String source = doc.getFullText();
        Lang lang = detectKind(fileName, source);
        if (lang == Lang.NONE || source.isBlank()) {
            ctx.reportDiagnostics(path, List.of()); // not a JVM file: clear stale
            return;
        }
        final long myGen = generation.incrementAndGet();
        final String name = (fileName == null || fileName.isBlank())
                ? defaultFileName(lang) : fileName;
        scheduler.schedule(
                () -> compute(lang, source, name, path),
                diags -> {
                    if (myGen != generation.get()) {
                        return; // a newer edit superseded this run
                    }
                    ctx.reportDiagnostics(path, (diags == null) ? List.of() : diags);
                });
    }

    private List<Diagnostic> compute(Lang lang, String text, String name, String path) {
        try {
            return switch (lang) {
                case JAVA -> compileJava(text, name);
                case KOTLIN -> compileKotlin(text, name);
                case NONE -> List.of();
            };
        } catch (RuntimeException | IOException re) {
            // A compiler that could not start is one info note, never a crash.
            return List.of(Diagnostic.of(path, 1, 1, Diagnostic.Kind.INFO,
                    "Diagnostics unavailable: " + re.getMessage()));
        }
    }

    /** In-memory javac of one source file; maps its diagnostics to ours. */
    static List<Diagnostic> compileJava(String source, String fileName) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return List.of(); // trimmed runtime without jdk.compiler
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                     compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                    "-proc:none",            // skip annotation processing
                    "-d", dropOutputDir());  // discard class output; never touches cwd
            JavaFileObject unit = new InMemorySource(fileName, source);
            compiler.getTask(null, fm, collector, options, null, List.of(unit)).call();
        } catch (IOException | RuntimeException re) {
            return List.of();
        }
        List<Diagnostic> out = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> jd : collector.getDiagnostics()) {
            out.add(mapJavaDiagnostic(jd, fileName));
        }
        return out;
    }

    private static Diagnostic mapJavaDiagnostic(
            javax.tools.Diagnostic<? extends JavaFileObject> jd, String fileName) {
        int line = Math.max(1, (int) jd.getLineNumber());
        int col = Math.max(1, (int) jd.getColumnNumber());
        Diagnostic.Kind kind = switch (jd.getKind()) {
            case ERROR -> Diagnostic.Kind.ERROR;
            case WARNING, MANDATORY_WARNING -> Diagnostic.Kind.WARNING;
            case NOTE -> Diagnostic.Kind.INFO;
            default -> Diagnostic.Kind.INFO;
        };
        return new Diagnostic(fileName, line, col, 0, 0, kind, strip(jd.getMessage(null)));
    }

    /** Kotlin analysis: run {@code kotlinc} on the staged source and parse output. */
    private List<Diagnostic> compileKotlin(String source, String fileName) throws IOException {
        Optional<Path> kotlinc = kotlinToolSeam.get();
        if (kotlinc.isEmpty()) {
            return List.of(); // no compiler: nothing to report, nothing spawned
        }
        Path work = Toolchain.stageSource(source, fileName);
        try {
            Toolchain.Result res = runner.run(
                    Toolchain.kotlincCommand(kotlinc.get(), work.resolve(fileName),
                            work.resolve("classes")),
                    work, KOTLINC_TIMEOUT_MS);
            return parseKotlinOutput(res.output());
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * Pure parser for {@code kotlinc} diagnostics: matches both the classic
     * {@code file.kt:L:C: error: msg} form and the newer {@code e: file.kt:
     * (L, C): msg} form. Package-private so it is headless-testable in isolation.
     */
    static List<Diagnostic> parseKotlinOutput(String output) {
        List<Diagnostic> out = new ArrayList<>();
        if (output == null) {
            return out;
        }
        for (String raw : output.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher c = KOTLIN_CLASSIC.matcher(line);
            if (c.matches()) {
                out.add(Diagnostic.of(c.group(1), Integer.parseInt(c.group(2)),
                        Integer.parseInt(c.group(3)), kotlinKind(c.group(4)), strip(c.group(5))));
                continue;
            }
            Matcher s = KOTLIN_SHORT.matcher(line);
            if (s.matches()) {
                out.add(Diagnostic.of(s.group(1), Integer.parseInt(s.group(2)),
                        Integer.parseInt(s.group(3)),
                        shortKotlinKind(line.charAt(0)), strip(s.group(4))));
            }
        }
        return out;
    }

    private static Diagnostic.Kind kotlinKind(String word) {
        return switch (word) {
            case "error" -> Diagnostic.Kind.ERROR;
            case "warning" -> Diagnostic.Kind.WARNING;
            default -> Diagnostic.Kind.INFO;
        };
    }

    private static Diagnostic.Kind shortKotlinKind(char tag) {
        return (tag == 'e') ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
    }

    /** The language of {@code fileName}, deciding whether live diagnostics apply. */
    static Lang detectKind(String fileName, String source) {
        String n = (fileName == null) ? "" : fileName.toLowerCase();
        if (n.endsWith(".java")) {
            return Lang.JAVA;
        }
        if (n.endsWith(".kt") || n.endsWith(".kts")) {
            return Lang.KOTLIN;
        }
        return Lang.NONE;
    }

    private static String defaultFileName(Lang lang) {
        return (lang == Lang.JAVA) ? "Main.java" : "Main.kt";
    }

    private static String strip(String message) {
        return (message == null) ? "" : message.strip();
    }

    /** A temp directory to discard {@code javac} class output into. */
    private static String dropOutputDir() {
        try {
            Path dir = Files.createTempDirectory("lg3d-diag-out-");
            dir.toFile().deleteOnExit();
            return dir.toString();
        } catch (IOException ioe) {
            return System.getProperty("java.io.tmpdir", ".");
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a throwaway staging dir
                }
            });
        } catch (IOException ignored) {
            // the tree may already be gone
        }
    }

    /** An in-memory {@code .java} compilation unit over the current document text. */
    static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;

        InMemorySource(String fileName, String content) {
            super(URI.create("string:///" + fileName.replace(' ', '_')), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
