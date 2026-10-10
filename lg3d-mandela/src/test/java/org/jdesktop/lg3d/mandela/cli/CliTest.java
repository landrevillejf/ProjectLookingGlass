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
package org.jdesktop.lg3d.mandela.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line, driven the way a terminal drives it: arguments in, exit code and
 * text out.
 *
 * <p>{@link MandelaCli#execute} takes the streams and returns the code instead of
 * calling {@code System.exit}, which is what lets this suite assert all five codes and
 * what each one means. That seam is also the desktop's: a console panel embeds the
 * same method, so a code that lied about what happened would be a launcher that
 * reports success on a failed build.</p>
 *
 * <p>The assertions read the whole output as text rather than line by line, because
 * what matters here is that a person (or an editor's run panel) can see the reason,
 * the position and the value. Exact byte-for-byte formatting is asserted only for the
 * {@code file:line: Kind: message} shape, which a tool parses.</p>
 */
class CliTest {

    @TempDir
    Path root;

    /** One command line's worth of answers. */
    private record Outcome(int code, String out, String err) {
    }

    private Outcome run(String... argv) {
        return run(List.of(), argv);
    }

    /** Runs a command line with the console words the {@code repl} will read. */
    private Outcome run(List<String> console, String... argv) {
        StringWriter shown = new StringWriter();
        StringWriter erred = new StringWriter();
        PrintWriter out = new PrintWriter(shown);
        PrintWriter err = new PrintWriter(erred);
        int code = MandelaCli.execute(argv,
                new BufferedReader(new StringReader(String.join("\n", console))), out, err);
        out.flush();
        err.flush();
        return new Outcome(code, shown.toString(), erred.toString());
    }

    /** Writes a program next to the sandbox root and returns its path. */
    private Path write(String name, String source) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    // -- the command line itself ---------------------------------------------

    @Test
    @DisplayName("an empty command line and --help both print the usage and succeed")
    void usage() {
        Outcome bare = run();
        assertEquals(MandelaCli.OK, bare.code());
        assertTrue(bare.out().contains("usage: mandela"), bare.out());

        Outcome asked = run("--help");
        assertEquals(MandelaCli.OK, asked.code());
        assertTrue(asked.out().contains("run FILE"));

        // A typo gets the same text, on the channel a terminal shows in red.
        Outcome typo = run("frobnicate");
        assertEquals(MandelaCli.USAGE, typo.code());
        assertTrue(typo.err().contains("unknown command 'frobnicate'"), typo.err());
        assertTrue(typo.err().contains("usage: mandela"), typo.err());
    }

    @Test
    @DisplayName("a bad flag is a usage error, before any program starts")
    void badFlags() {
        Outcome unknown = run("eval", "-z", "1");
        assertEquals(MandelaCli.USAGE, unknown.code());
        assertTrue(unknown.err().contains("unknown option '-z'"), unknown.err());

        Outcome starved = run("run", "-s");
        assertEquals(MandelaCli.USAGE, starved.code());
        assertTrue(starved.err().contains("needs a directory"), starved.err());

        // Each command also refuses to guess when its own word is missing.
        assertEquals(MandelaCli.USAGE, run("run").code());
        assertEquals(MandelaCli.USAGE, run("check").code());
        assertEquals(MandelaCli.USAGE, run("eval").code());
        assertEquals(MandelaCli.USAGE, run("dump").code());
    }

    @Test
    @DisplayName("version and about describe the engine without running a program")
    void metadata() {
        Outcome version = run("version");
        assertEquals(MandelaCli.OK, version.code());
        assertTrue(version.out().startsWith("mandela "), version.out());

        Outcome about = run("about");
        assertEquals(MandelaCli.OK, about.code());
        assertTrue(about.out().contains("Project Mandela"), about.out());
        assertTrue(about.out().contains("json"), about.out());
        // The line a developer copies into their own code.
        assertTrue(about.out().contains("org.jdesktop.lg3d.mandela.api.Mandela"),
                about.out());
    }

    @Test
    @DisplayName("options may be typed anywhere after the command")
    void optionsAnywhere() {
        MandelaCli.Request before = MandelaCli.Request.parse(
                new String[] {"run", "-t", "--sandbox", "/tmp/x", "a.mnd"});
        MandelaCli.Request after = MandelaCli.Request.parse(
                new String[] {"run", "a.mnd", "--sandbox=/tmp/x", "-t"});
        assertEquals(before, after);
        assertEquals("run", before.command());
        assertEquals(List.of("a.mnd"), before.files());
        assertEquals("/tmp/x", before.sandbox());
        assertTrue(before.trace());
    }

    // -- eval ----------------------------------------------------------------

    @Test
    @DisplayName("eval prints the value of an expression and stays silent for a definition")
    void eval() {
        Outcome answer = run("eval", "1 + 2");
        assertEquals(MandelaCli.OK, answer.code());
        assertEquals("3\n", answer.out());

        Outcome text = run("eval", "\"ab\".upper()");
        assertEquals("AB\n", text.out());

        // A declaration has no value to echo, which is the same rule the REPL uses.
        Outcome quiet = run("eval", "let x = 5");
        assertEquals(MandelaCli.OK, quiet.code());
        assertEquals("", quiet.out());

        Outcome failing = run("eval", "1 / 0");
        assertEquals(MandelaCli.PROGRAM_FAILED, failing.code());
        assertTrue(failing.err().contains("RangeError"), failing.err());
    }

    @Test
    @DisplayName("eval has no filesystem, so a one-liner cannot touch the host")
    void evalIsSandboxed() {
        Outcome refused = run("eval", "use std.fs\nfs.read(\"x\")");
        assertEquals(MandelaCli.PROGRAM_FAILED, refused.code());
        assertTrue(refused.err().contains("PermissionError"), refused.err());

        // The same program with --unrestricted is allowed, because the person who
        // typed it owns the machine it runs on.
        Outcome granted = run("eval", "-u", "use std.env\nenv.separator");
        assertEquals(MandelaCli.OK, granted.code());
    }

    // -- run -----------------------------------------------------------------

    @Test
    @DisplayName("run executes a file, prints its output and passes its arguments")
    void runsAFile() throws IOException {
        Path program = write("hello.mnd", """
                println("hi " + args[0])
                """);
        Outcome outcome = run("run", program.toString(), "Ada");
        assertEquals(MandelaCli.OK, outcome.code());
        assertEquals("hi Ada\n", outcome.out());

        Path silent = write("quiet.mnd", "let unused = 1\n");
        assertEquals("", run("run", silent.toString()).out());
    }

    @Test
    @DisplayName("a run script may read the file beside it, and nothing above that")
    void runSandbox() throws IOException {
        write("note.txt", "alpha\n");
        write("inside.mnd", "use std.fs\nprint(fs.read(\"note.txt\"))\n");
        write("dir/tool.mnd", "use std.fs\nprint(fs.read(\"../note.txt\"))\n");

        Outcome inside = run("run", root.resolve("inside.mnd").toString());
        assertEquals(MandelaCli.OK, inside.code());
        assertEquals("alpha\n", inside.out());

        // The escape is the point: the script sits in a subdirectory and asks for
        // its parent's file, which the root it was given does not cover.
        Outcome escape = run("run", root.resolve("dir/tool.mnd").toString());
        assertEquals(MandelaCli.PROGRAM_FAILED, escape.code());
        assertTrue(escape.err().contains("PermissionError"), escape.err());

        // --sandbox moves the boundary without moving the script.
        write("dir/other.mnd", "use std.fs\nprint(fs.read(\"note.txt\"))\n");
        Outcome moved = run("run", "-s", root.toString(),
                root.resolve("dir/other.mnd").toString());
        assertEquals(MandelaCli.OK, moved.code());
        assertEquals("alpha\n", moved.out());
    }

    @Test
    @DisplayName("a missing file is a different answer from a broken program")
    void unreadable() throws IOException {
        Outcome missing = run("run", root.resolve("nope.mnd").toString());
        assertEquals(MandelaCli.NOT_READABLE, missing.code());
        assertTrue(missing.err().contains("cannot read"), missing.err());

        Path broken = write("broken.mnd", "let x = \n");
        Outcome failed = run("run", broken.toString());
        assertEquals(MandelaCli.PROGRAM_FAILED, failed.code());

        // A directory is not a program either.
        assertEquals(MandelaCli.NOT_READABLE, run("run", root.toString()).code());
    }

    @Test
    @DisplayName("a failure is reported where an editor can find the line again")
    void reportShape() throws IOException {
        Path program = write("boom.mnd", "let a = 1\nprintln(b)\n");
        Outcome outcome = run("run", program.toString());
        assertEquals(MandelaCli.PROGRAM_FAILED, outcome.code());
        // Either a compile finding (an unknown name) or a runtime error, but always
        // with the file and the line the author has to go back to.
        assertTrue(outcome.err().contains("boom.mnd"), outcome.err());
        assertTrue(outcome.err().contains("2"), outcome.err());

        Path raised = write("raise.mnd", "var i = 0\nloop { i += 1 }\n");
        Outcome budget = run("run", raised.toString());
        assertEquals(MandelaCli.PROGRAM_FAILED, budget.code());
        assertTrue(budget.err().contains("LimitError"), budget.err());
    }

    @Test
    @DisplayName("--trace adds the frames after the reason, never before it")
    void trace() throws IOException {
        Path program = write("deep.mnd", """
                fun inner() -> Int { 1 / 0 }
                fun outer() -> Int { inner() }
                outer()
                """);
        Outcome plain = run("run", program.toString());
        Outcome traced = run("run", "-t", program.toString());
        assertTrue(traced.err().contains("trace"), traced.err());
        // The reason stays the first thing on the line, so a human reads it first.
        assertTrue(plain.err().indexOf("RangeError") >= 0);
        assertTrue(traced.err().indexOf("RangeError")
                < traced.err().indexOf("trace"), traced.err());
    }

    // -- check and dump ------------------------------------------------------

    @Test
    @DisplayName("check compiles without running, and answers with every finding")
    void check() throws IOException {
        Path good = write("good.mnd", "fun add(a: Int, b: Int) -> Int { a + b }\n");
        assertEquals(MandelaCli.OK, run("check", good.toString()).code());

        Path bad = write("bad.mnd", "let x = \n");
        Outcome findings = run("check", bad.toString());
        assertEquals(MandelaCli.FINDINGS, findings.code());
        assertTrue(findings.err().contains("bad.mnd"), findings.err());
        assertTrue(findings.err().contains("error(s)"), findings.err());

        // Checking a file that has side effects must not perform them.
        Path writer = write("side.mnd", """
                use std.fs
                fs.write("made.txt", "yes")
                """);
        assertEquals(MandelaCli.OK, run("check", writer.toString()).code());
        assertTrue(!Files.exists(root.resolve("made.txt")),
                "check ran a program it was only meant to compile");

        assertEquals(MandelaCli.NOT_READABLE,
                run("check", root.resolve("nope.mnd").toString()).code());
    }

    @Test
    @DisplayName("dump prints the instructions the compiler produced")
    void dump() throws IOException {
        Path program = write("body.mnd", "fun add(a: Int, b: Int) -> Int { a + b }\nadd(1, 2)\n");
        Outcome listing = run("dump", program.toString());
        assertEquals(MandelaCli.OK, listing.code());
        assertTrue(listing.out().contains("RETURN"), listing.out());

        Path broken = write("broken2.mnd", "fun {\n");
        assertEquals(MandelaCli.PROGRAM_FAILED, run("dump", broken.toString()).code());
    }

    // -- the REPL ------------------------------------------------------------

    @Test
    @DisplayName("a session keeps its names, echoes values and stays quiet for definitions")
    void replSession() {
        Outcome session = run(List.of("1 + 1", "let x = 5", "x * 2", ":quit"), "repl");
        assertEquals(MandelaCli.OK, session.code());
        assertTrue(session.out().contains("Project Mandela"), session.out());
        assertTrue(session.out().contains("2"), session.out());
        assertTrue(session.out().contains("10"), session.out());
        // ':quit' ends the session; the commands after it never run.
        assertTrue(!session.out().contains(":vars"), session.out());
    }

    @Test
    @DisplayName("an unfinished entry waits for the closing delimiter")
    void replContinuation() {
        Outcome session = run(List.of("fun f() {", "  return 40 + 2", "}", "f()", ":quit"),
                "repl");
        assertEquals(MandelaCli.OK, session.code());
        assertTrue(session.out().contains("... "), session.out());
        assertTrue(session.out().contains("42"), session.out());
    }

    @Test
    @DisplayName("a typo reports and keeps the session open, and the code says so")
    void replFailures() {
        Outcome session = run(List.of("nope", "1 + 1", ":quit"), "repl");
        assertEquals(MandelaCli.PROGRAM_FAILED, session.code());
        assertTrue(session.out().contains("2"), session.out());
        assertTrue(session.err().contains("nope"), session.err());
    }

    @Test
    @DisplayName("the session commands answer what a user asks them")
    void replCommands() {
        Outcome vars = run(List.of("let answer = 42", ":vars", ":type answer",
                "fun double(v: Int) -> Int { v * 2 }", ":call double 21", ":quit"), "repl");
        assertEquals(MandelaCli.OK, vars.code());
        assertTrue(vars.out().contains("answer : Int = 42"), vars.out());
        assertTrue(vars.out().contains("answer : Int"), vars.out());
        // ':call' is how a user tries a function with words instead of brackets.
        Outcome called = run(List.of("fun double(v: Int) -> Int { v * 2 }",
                ":call double 21", ":quit"), "repl");
        assertTrue(called.out().contains("42"), called.out());

        Outcome cleared = run(List.of("let gone = 1", ":clear", "gone", ":quit"), "repl");
        assertTrue(cleared.out().contains("session cleared"), cleared.out());

        Outcome unknown = run(List.of(":nonsense", ":quit"), "repl");
        assertTrue(unknown.out().contains("unknown command :nonsense"), unknown.out());
    }

    @Test
    @DisplayName("a session that only reads from an empty console ends cleanly")
    void replWithoutInput() {
        Outcome session = run(List.of(), "repl");
        assertEquals(MandelaCli.OK, session.code());
        assertTrue(session.out().contains("session"), session.out());
    }

    @Test
    @DisplayName("isOpen waits only for delimiters, never for a mistake")
    void replOpenness() {
        assertTrue(Repl.isOpen("fun f() {"));
        assertTrue(Repl.isOpen("[1, [2,"));
        assertTrue(!Repl.isOpen("1 + 1"));
        assertTrue(!Repl.isOpen("}"));
        // An unterminated string is a finding to report at the prompt, not a reason
        // to keep asking for more lines that can never finish the entry.
        assertTrue(!Repl.isOpen("\"oops"));
        assertTrue(!Repl.isOpen(""));
    }

    @Test
    @DisplayName("a ':call' argument reads as the value it looks like")
    void replArgumentTypes() {
        assertEquals(7L, Repl.typed("7"));
        assertEquals(1.5, Repl.typed("1.5"));
        assertEquals(Boolean.TRUE, Repl.typed("true"));
        assertEquals(Boolean.FALSE, Repl.typed("false"));
        assertNull(Repl.typed("null"));
        assertEquals("Ada", Repl.typed("Ada"));
        // A word too wide for a Long is still a number the user meant, rounded the
        // way a Double rounds: the REPL answers 1.0E20 rather than passing text.
        assertEquals(1.0e20, Repl.typed("99999999999999999999"));
    }

    @Test
    @DisplayName("the shared plumbing sizes a sandbox and a script's arguments")
    void plumbing() {
        assertEquals(MandelaCli.rootOf(Path.of("/tmp/a/b.mnd")), Path.of("/tmp/a"));
        assertEquals(0, MandelaCli.scriptArgs(List.of()).size());
        assertEquals("Ada", MandelaCli.scriptArgs(List.of("Ada")).get(0));
        assertEquals(2, MandelaCli.scriptArgs(List.of("a", "b")).size());

        Outcome usage = run("eval", "-t", "1 + 1");
        assertEquals(MandelaCli.OK, usage.code());
        assertEquals("2\n", usage.out());
    }

    @Test
    @DisplayName("a script's own print and eprintln go to the caller's streams")
    void consoleChannels() throws IOException {
        Path program = write("noise.mnd", "print(\"out\")\neprintln(\"bad\")\n");
        Outcome outcome = run("run", program.toString());
        assertEquals(MandelaCli.OK, outcome.code());
        assertTrue(outcome.out().contains("out"), outcome.out());
        assertTrue(!outcome.out().contains("bad"), outcome.out());
        assertTrue(outcome.err().contains("bad"), outcome.err());
    }

    @Test
    @DisplayName("a module a script imports is read from the script's own directory")
    void importsFromADirectory() throws IOException {
        write("lib/nums.mnd", "export fun twice(v: Int) -> Int { v * 2 }\n");
        Path program = write("user.mnd", "import \"lib/nums\"\nprintln(nums.twice(21))\n");
        Outcome outcome = run("run", program.toString());
        assertEquals(MandelaCli.OK, outcome.code());
        assertEquals("42\n", outcome.out());
    }
}
