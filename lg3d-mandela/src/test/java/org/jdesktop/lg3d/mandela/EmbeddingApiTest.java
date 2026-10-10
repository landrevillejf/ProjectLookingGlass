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
package org.jdesktop.lg3d.mandela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.api.Interop;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.api.MandelaScriptEngineFactory;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The embedding API as a host uses it: the {@link Mandela} facade, the long-lived
 * {@link Runtime} a desktop panel holds on to, module loading from disk, and what
 * the sandbox does when a script reaches past its grants.
 *
 * <p>The hosts this is written for are the desktop's own &mdash; a Web page running a
 * script, an Espresso tool running a macro &mdash; and both keep one engine alive
 * across many runs. So the cases below are sequences, not single expressions: bind,
 * run, read a name back, call a function, re-point the console. A facade that only
 * worked for the first {@code eval} would look fine in a language test and break in
 * the browser.</p>
 */
class EmbeddingApiTest {

    @TempDir
    Path root;

    /** A small project on disk: one exported module, a cycle, and a silent module. */
    @BeforeEach
    void writeProject() throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/nums.mnd"), """
                export fun twice(v: Int) -> Int { v * 2 }
                export let tag = "nums"
                """);
        Files.writeString(root.resolve("lib/cyclic-a.mnd"), """
                import "lib/cyclic-b" as b
                export fun a() -> Int { 1 }
                """);
        Files.writeString(root.resolve("lib/cyclic-b.mnd"), """
                import "lib/cyclic-a" as a
                export fun b() -> Int { 2 }
                """);
        Files.writeString(root.resolve("lib/quiet.mnd"), """
                fun helper() -> Int { 1 }
                """);
        Files.writeString(root.resolve("main.mnd"), """
                import "lib/nums"
                nums.twice(5)
                """);
        Files.writeString(root.resolve("note.txt"), "alpha\nbeta\n");
    }

    // -- the facade ----------------------------------------------------------

    @Nested
    @DisplayName("one-shot evaluation")
    class Facade {

        @Test
        @DisplayName("answers with the last expression, in host types")
        void answers() {
            assertEquals(3L, Mandela.eval("1 + 2"));
            assertEquals("ab", Mandela.eval("\"a\" + \"b\""));
            assertEquals(10.0, Mandela.eval("2.5 * 4"));
            assertEquals("ada,lin", Mandela.eval("names.join(\",\")",
                    Map.of("names", List.of("ada", "lin"))));
            assertEquals(1.5, Mandela.eval("tax(15.0)",
                    Map.of("tax", Interop.fn1("tax", v -> ((Double) v) * 0.1))));
            assertEquals(25L, Mandela.eval("let x = 5\nx * x"));
        }

        @Test
        @DisplayName("check answers with findings instead of throwing")
        void check() {
            assertTrue(Mandela.check("let x = 1\nx").isEmpty());
            List<Diagnostic> broken = Mandela.check("let x = ");
            assertFalse(broken.isEmpty());
            assertTrue(broken.get(0).isError());
            // The default name has to read as a string, or a host shows the user a
            // diagnostics panel keyed on an empty file name.
            assertEquals(Runtime.STRING_SOURCE, broken.get(0).sourceName());
            assertEquals("scratch.mnd",
                    Mandela.check("let x = ", "scratch.mnd").get(0).sourceName());
            // The rule is the identifier a host branches on. An undeclared name is
            // the normal state halfway through typing a buffer, so it must be
            // distinguishable from a program the grammar cannot read at all.
            assertEquals("syntax", broken.get(0).rule());
            assertEquals("undefined-name",
                    Mandela.check("let x = 1\nx + neverDeclared").get(0).rule());
            assertEquals("param-order",
                    Mandela.check("fun f(a: Int = 1, b: Int) -> Int { b }").stream()
                            .filter(finding -> !finding.isError()).findFirst().orElseThrow().rule());
        }

        @Test
        @DisplayName("about() describes the engine to a tool that lists languages")
        void about() {
            Map<String, Object> about = Mandela.about();
            assertEquals("Mandela", about.get("language"));
            assertEquals("Project Mandela", about.get("project"));
            assertEquals("mnd", about.get("extension"));
            assertTrue(String.valueOf(about.get("modules")).contains("json"));
            assertTrue(Mandela.ENGINE_NAMES.contains("mandela"));
            // Espresso reads the version from the JSR-223 factory; the facade must
            // not drift from it, or the two surfaces advertise different languages.
            assertEquals(Mandela.LANGUAGE_VERSION,
                    new MandelaScriptEngineFactory().getLanguageVersion());
        }

        @Test
        @DisplayName("a failure reaches the host as a typed error")
        void failures() {
            MandelaError failure = assertThrows(MandelaError.class, () -> Mandela.eval("1 / 0"));
            assertEquals("RangeError", failure.kind());
        }
    }

    // -- a live runtime ------------------------------------------------------

    @Nested
    @DisplayName("a runtime kept across runs")
    class LiveRuntime {

        @Test
        @DisplayName("names survive, and the host can read and call them")
        void names() {
            Runtime runtime = Scripts.engine().bind("answer", 42L).build();
            assertEquals(42L, runtime.run("answer"));
            // A trailing declaration is not an expression, so it answers null; the
            // name it bound is what the host reads back.
            assertNull(runtime.run("let half = answer / 2"));
            assertEquals(63L, runtime.run("half + answer"));
            assertEquals(21L, runtime.get("half"));
            runtime.run("fun add(a: Int, b: Int) -> Int { a + b }");
            assertEquals(5L, runtime.call("add", 2, 3));
            assertTrue(runtime.globals().containsKey("add"));
            assertTrue(runtime.knownGlobals().contains("println"));
            assertTrue(runtime.knownGlobals().contains("answer"));
        }

        @Test
        @DisplayName("the console can be re-pointed between runs")
        void console() {
            StringBuilder out = new StringBuilder();
            Runtime runtime = Scripts.engine().bind("answer", 42L).output(out::append).build();
            runtime.run("print(\"one\")");
            assertEquals("one", out.toString());
            StringBuilder moved = new StringBuilder();
            runtime.setOutput(moved::append, moved::append);
            runtime.run("println(\"two\")");
            assertEquals("two\n", moved.toString());
        }

        @Test
        @DisplayName("the REPL's silent answer is not the same as null")
        void replEntries() {
            Runtime runtime = Scripts.engine().build();
            assertEquals(Runtime.REPL_SILENT, runtime.evalEntry("let quiet = 1", "<repl>"));
            assertEquals(1L, runtime.evalEntry("quiet", "<repl>"));
            // A script that really answers null must print "null" rather than look
            // like a declaration the REPL should not echo.
            assertNull(runtime.evalEntry("null", "<repl>"));
            assertFalse(Runtime.REPL_SILENT.equals(runtime.evalEntry("1 + 1", "<repl>")));
        }

        @Test
        @DisplayName("the machine describes itself to a tool that wants to show bytecode")
        void introspection() {
            Runtime runtime = Scripts.engine().build();
            runtime.run("fun add(a: Int, b: Int) -> Int { a + b }");
            String listing = runtime.disassemble("1 + 2");
            assertFalse(listing.isBlank());
            assertTrue(listing.contains("RETURN"));
            assertTrue(runtime.trace().isEmpty());
            assertEquals("test", runtime.capabilities().label());
        }

        @Test
        @DisplayName("check never throws, and run reports a missing name by itself")
        void findings() {
            Runtime runtime = Scripts.engine().build();
            assertFalse(runtime.check("if 1 {", "t.mnd").isEmpty());
            LangException absent = assertThrows(LangException.class,
                    () -> runtime.run("noSuchName", "t.mnd"));
            assertTrue(absent.getMessage().contains("noSuchName"), absent.getMessage());
        }

        @Test
        @DisplayName("a failure keeps its frames, because the host reports them later")
        void failureFrames() {
            Runtime runtime = Scripts.engine().build();
            Scripts.failure("""
                    fun inner() -> Int { 1 / 0 }
                    fun outer() -> Int { inner() }
                    outer()
                    """, runtime);
            // The live chain is empty once the machine has unwound, so the snapshot
            // is the only answer a crash report can print -- and it must name the
            // functions the error passed through, outermost first.
            assertTrue(runtime.trace().isEmpty());
            List<String> frames = runtime.failureTrace();
            int outer = frames.indexOf(frames.stream()
                    .filter(frame -> frame.startsWith("outer")).findFirst().orElse(""));
            int inner = frames.indexOf(frames.stream()
                    .filter(frame -> frame.startsWith("inner")).findFirst().orElse(""));
            assertTrue(outer >= 0 && inner > outer, frames.toString());
        }
    }

    // -- modules -------------------------------------------------------------

    @Nested
    @DisplayName("modules loaded from disk")
    class Modules {

        private Runtime project() {
            return Scripts.files(root).build();
        }

        @Test
        @DisplayName("an import binds the file stem and its exports")
        void imports() {
            Runtime runtime = project();
            assertEquals(42L, runtime.run("import \"lib/nums\"\nnums.twice(21)"));
            assertEquals("nums", runtime.run("import \"lib/nums\"\nnums.tag"));
            assertEquals(8L, runtime.run("import \"lib/nums\" as n\nn.twice(4)"));
        }

        @Test
        @DisplayName("a file on disk runs as a program")
        void files() {
            assertEquals(10L, project().runFile(root.resolve("main.mnd")));
        }

        @Test
        @DisplayName("a cycle, a module with no exports and a missing file each say why")
        void refusals() {
            Runtime runtime = project();
            MandelaError cycle = Scripts.failure("import \"lib/cyclic-a\"\n1", runtime);
            assertTrue(cycle.getMessage().contains("circular"), cycle.getMessage());
            MandelaError silent = Scripts.failure("import \"lib/quiet\" as q\n1", runtime);
            assertTrue(silent.getMessage().contains("export"), silent.getMessage());
            MandelaError missing = Scripts.failure("import \"lib/nope\"\n1", runtime);
            assertTrue(missing.getMessage().contains("nope"), missing.getMessage());
        }
    }

    // -- the sandbox ---------------------------------------------------------

    @Nested
    @DisplayName("what the grants allow and refuse")
    class Sandbox {

        @Test
        @DisplayName("a web page computes, but touches nothing on the host")
        void webPage() {
            Runtime page = Mandela.engine(Capabilities.webPage()).build();
            assertEquals(42L, page.run("2 * 21"));
            assertEquals("PermissionError", Scripts.failure("print(\"x\")", page).kind());
            assertEquals("PermissionError",
                    Scripts.failure("use std.fs\nfs.read(\"note.txt\")", page).kind());
            assertEquals("PermissionError",
                    Scripts.failure("use std.env\nenv.get(\"HOME\")", page).kind());
        }

        @Test
        @DisplayName("a console engine has no filesystem and no threads")
        void console() {
            Runtime console = Scripts.engine().build();
            assertEquals("PermissionError",
                    Scripts.failure("use std.fs\nfs.read(\"x\")", console).kind());
            assertEquals("PermissionError",
                    Scripts.failure("use std.time\ntime.sleep(1)", console).kind());
        }

        @Test
        @DisplayName("a desktop app writes inside its root and is refused outside it")
        void desktop() {
            Runtime app = Scripts.files(root).build();
            assertEquals(11L, app.run("use std.fs\nfs.read(\"note.txt\").length"));
            assertEquals("made by mandela", app.run("""
                    use std.fs
                    fs.write("out.txt", "made by mandela")
                    fs.read("out.txt")
                    """));
            assertEquals("PermissionError",
                    Scripts.failure("use std.fs\nfs.read(\"../secret\")", app).kind());
            // The JVM door stays shut even for a desktop app: it opens only for a
            // prefix the host named in its own source, which a reviewer can read.
            assertFalse(app.capabilities().allowsJvm("java.lang.Runtime"));
            assertTrue(app.capabilities().describe().contains("file_read"));
        }

        @Test
        @DisplayName("'open' grants the host's environment, but still no JVM type")
        void open() {
            Runtime wild = Mandela.engine(Capabilities.open()).output(text -> { }).build();
            // Read from the host rather than hard-coded, so the suite says the same
            // thing on a Windows desktop.
            assertEquals(System.getProperty("file.separator"),
                    wild.run("use std.env\nenv.separator"));
            assertFalse(wild.capabilities().allowsJvm("java.lang.Runtime"));

            Capabilities listed = Capabilities.builder().grant(Capabilities.Grant.JVM)
                    .allowJvm("java.time").label("listed").build();
            assertTrue(listed.allowsJvm("java.time.LocalDate"));
            assertFalse(listed.allowsJvm("java.lang.Runtime"));
        }

        @Test
        @DisplayName("a permission failure names the host that set the rule")
        void labels() {
            Runtime page = Mandela.engine(Capabilities.webPage()).build();
            assertTrue(Scripts.failure("print(\"x\")", page).getMessage()
                    .contains(Capabilities.webPage().label()),
                    "the message should name the sandbox mode");
        }
    }
}
