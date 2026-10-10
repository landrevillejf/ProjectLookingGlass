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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic.Kind;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link JavaDiagnosticsTools} (Phase 1): the pure Kotlin
 * output parser (both compiler formats), language detection, a real in-memory
 * {@code javac} run over broken and valid sources, and the analysis-to-report
 * wiring exercised through the synchronous scheduler seam with a capturing
 * {@link EditorSinks} (including generation-based staleness dropping and the
 * no-compiler Kotlin degradation). No test spawns a real {@code kotlinc}.
 */
class JavaDiagnosticsToolsTest {

    // -- pure: language detection ------------------------------------------

    @Test
    @DisplayName("detectKind maps file extensions to languages")
    void detectKind() {
        assertEquals(JavaDiagnosticsTools.Lang.JAVA,
                JavaDiagnosticsTools.detectKind("Foo.java", "x"));
        assertEquals(JavaDiagnosticsTools.Lang.KOTLIN,
                JavaDiagnosticsTools.detectKind("Main.kt", "x"));
        assertEquals(JavaDiagnosticsTools.Lang.KOTLIN,
                JavaDiagnosticsTools.detectKind("build.KTS", "x"));
        assertEquals(JavaDiagnosticsTools.Lang.NONE,
                JavaDiagnosticsTools.detectKind("notes.txt", "x"));
        assertEquals(JavaDiagnosticsTools.Lang.NONE,
                JavaDiagnosticsTools.detectKind(null, "x"));
    }

    // -- pure: kotlinc output parser ---------------------------------------

    @Test
    @DisplayName("the classic kotlinc format parses to diagnostics")
    void parseKotlinClassic() {
        String out = String.join("\n",
                "warning: unreferenced thing",
                "Main.kt:3:5: error: unresolved reference: foo",
                "Main.kt:7:1: warning: never used variable x");
        List<Diagnostic> diags = JavaDiagnosticsTools.parseKotlinOutput(out);
        assertEquals(2, diags.size());
        assertEquals(3, diags.get(0).line());
        assertEquals(5, diags.get(0).col());
        assertEquals(Kind.ERROR, diags.get(0).kind());
        assertEquals("unresolved reference: foo", diags.get(0).message());
        assertEquals(Kind.WARNING, diags.get(1).kind());
    }

    @Test
    @DisplayName("the newer e:/w: kotlinc format parses to diagnostics")
    void parseKotlinShort() {
        String out = String.join("\n",
                "e: Main.kt: (12, 9): Unresolved reference: bar",
                "w: Main.kt: (4, 1): Variable 'x' is never used");
        List<Diagnostic> diags = JavaDiagnosticsTools.parseKotlinOutput(out);
        assertEquals(2, diags.size());
        assertEquals(12, diags.get(0).line());
        assertEquals(9, diags.get(0).col());
        assertEquals(Kind.ERROR, diags.get(0).kind());
        assertEquals("Unresolved reference: bar", diags.get(0).message());
        assertEquals(Kind.WARNING, diags.get(1).kind());
    }

    @Test
    @DisplayName("null and non-diagnostic output parse to nothing")
    void parseKotlinNoise() {
        assertTrue(JavaDiagnosticsTools.parseKotlinOutput(null).isEmpty());
        assertTrue(JavaDiagnosticsTools.parseKotlinOutput("").isEmpty());
        assertTrue(JavaDiagnosticsTools
                .parseKotlinOutput("info: loading module\nBUILD FAILED").isEmpty());
    }

    // -- real in-memory javac ----------------------------------------------

    @Test
    @DisplayName("a broken Java source yields an ERROR diagnostic with a line")
    void compileJavaBroken() {
        String src = "public class Foo {\n    void bar() {\n        int i = \"not an int\";\n    }\n}\n";
        List<Diagnostic> diags = JavaDiagnosticsTools.compileJava(src, "Foo.java");
        assertFalse(diags.isEmpty(), "javac reported no diagnostics");
        boolean sawError = diags.stream()
                .anyMatch(d -> d.kind() == Kind.ERROR && d.line() == 3);
        assertTrue(sawError, "expected an ERROR on line 3, got " + diags);
    }

    @Test
    @DisplayName("a valid Java source compiles with no ERROR")
    void compileJavaValid() {
        String src = "public class Foo {\n"
                + "    public static void main(String[] a) {\n"
                + "        System.out.println(a.length);\n"
                + "    }\n}\n";
        List<Diagnostic> diags = JavaDiagnosticsTools.compileJava(src, "Foo.java");
        assertTrue(diags.stream().noneMatch(d -> d.kind() == Kind.ERROR),
                "valid source should not error, got " + diags);
    }

    // -- wiring: analyze -> reportDiagnostics -------------------------------

    /** A scheduler that runs the computation immediately and delivers inline. */
    private static JavaDiagnosticsTools.Scheduler sync() {
        return (compute, onEdt) -> onEdt.accept(compute.get());
    }

    private static DocumentContext doc(String fileName, String text) {
        return new DocumentContext("/src/" + fileName, fileName, text, "",
                0, 1, 1, 0, 0, s -> { }, s -> { });
    }

    private static final class Capture {
        final List<List<Diagnostic>> reports = new ArrayList<>();
        EditorContext ctxWithDiagnose() {
            EditorSinks sinks = EditorSinks.builder()
                    .reportDiagnostics((path, diags) -> reports.add(diags))
                    .build();
            return new EditorContext(
                    EnumSet.of(TextEditorPermission.DIAGNOSE), sinks);
        }
    }

    @Test
    @DisplayName("an opened Java document is analysed and its errors reported")
    void analyzeReportsJavaErrors() {
        Capture cap = new Capture();
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(sync());
        ext.onEditorStarted(cap.ctxWithDiagnose());
        ext.onDocumentOpened(doc("Foo.java",
                "class Foo { void x(){ int i = \"s\"; } }"));

        assertEquals(1, cap.reports.size());
        assertTrue(cap.reports.get(0).stream().anyMatch(d -> d.kind() == Kind.ERROR));
    }

    @Test
    @DisplayName("a non-JVM document clears diagnostics")
    void analyzeClearsNonJvm() {
        Capture cap = new Capture();
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(sync());
        ext.onEditorStarted(cap.ctxWithDiagnose());
        ext.onDocumentOpened(doc("notes.txt", "just prose"));

        assertEquals(1, cap.reports.size());
        assertTrue(cap.reports.get(0).isEmpty());
    }

    @Test
    @DisplayName("Kotlin output runs through the runner seam and reports")
    void analyzeKotlinViaSeam() {
        Capture cap = new Capture();
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(sync());
        ext.setKotlinToolForTesting(() -> Optional.of(Path.of("/usr/bin/kotlinc")));
        ext.setCommandRunnerForTesting((cmd, work, timeout) ->
                new Toolchain.Result(1, "Main.kt:2:1: error: expecting a newline"));
        ext.onEditorStarted(cap.ctxWithDiagnose());
        ext.onDocumentOpened(doc("Main.kt", "fun main() { syntax error here"));

        assertEquals(1, cap.reports.size());
        Diagnostic d = cap.reports.get(0).get(0);
        assertEquals(Kind.ERROR, d.kind());
        assertEquals(2, d.line());
    }

    @Test
    @DisplayName("with no kotlinc installed the Kotlin path reports nothing, spawns nothing")
    void analyzeKotlinNoCompiler() {
        Capture cap = new Capture();
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(sync());
        ext.setKotlinToolForTesting(Optional::empty);
        final boolean[] ran = {false};
        ext.setCommandRunnerForTesting((cmd, work, timeout) -> {
            ran[0] = true;
            return new Toolchain.Result(0, "");
        });
        ext.onEditorStarted(cap.ctxWithDiagnose());
        ext.onDocumentOpened(doc("Main.kt", "fun main() {}"));

        assertFalse(ran[0], "must not spawn a process without a compiler");
        assertEquals(1, cap.reports.size());
        assertTrue(cap.reports.get(0).isEmpty());
    }

    @Test
    @DisplayName("a superseded analysis result is dropped by the generation guard")
    void staleResultDropped() {
        Capture cap = new Capture();
        // The scheduler defers delivery so we can replay it after a newer edit.
        AtomicReference<Consumer<List<Diagnostic>>> pending = new AtomicReference<>();
        JavaDiagnosticsTools.Scheduler deferred = (compute, onEdt) -> pending.set(onEdt);
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(deferred);
        ext.onEditorStarted(cap.ctxWithDiagnose());

        // First edit schedules a delivery; second edit advances the generation.
        ext.onDocumentChanged(doc("Foo.java", "class A{ int i=\"x\"; }")); // gen 1
        Consumer<List<Diagnostic>> stale = pending.get();
        ext.onDocumentChanged(doc("Foo.java", "class B{ int i=\"y\"; }")); // gen 2
        stale.accept(List.of(Diagnostic.of("Foo.java", 1, 1, Kind.ERROR, "old")));

        assertTrue(cap.reports.isEmpty(), "the stale generation must not report");

        // Delivering the current generation does report.
        Consumer<List<Diagnostic>> fresh = pending.get();
        fresh.accept(List.of(Diagnostic.of("Foo.java", 1, 1, Kind.ERROR, "new")));
        assertEquals(1, cap.reports.size());
    }

    @Test
    @DisplayName("without DIAGNOSE granted, reporting is a permission no-op")
    void requiresDiagnosePermission() {
        final int[] calls = {0};
        EditorSinks sinks = EditorSinks.builder()
                .reportDiagnostics((p, d) -> calls[0]++)
                .build();
        EditorContext ctx = new EditorContext(
                EnumSet.of(TextEditorPermission.READ), sinks);
        JavaDiagnosticsTools ext = new JavaDiagnosticsTools();
        ext.setSchedulerForTesting(sync());
        ext.onEditorStarted(ctx);
        ext.onDocumentOpened(doc("Foo.java", "class Foo{ int i=\"x\"; }"));

        assertEquals(0, calls[0], "no DIAGNOSE grant means no diagnostics surface");
    }
}
