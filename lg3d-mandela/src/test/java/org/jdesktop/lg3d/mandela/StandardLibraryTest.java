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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The standard library as a script sees it: the members of each built-in type, the
 * global functions, and the {@code std.*} modules reached with {@code use}.
 *
 * <p>Everything here runs through the public {@link Mandela} facade rather than the
 * internals, because the library's contract is the answer a program gets &mdash; and
 * because a suite that wired the {@code VM} up by hand would keep passing after the
 * facade broke, which is the opposite of what a shipped API needs. Each case is a
 * snippet and the text the snippet displays, so a failure reads as the script author
 * reads it.</p>
 *
 * <p>The file cases get a fresh directory per invocation. {@code std.fs} is allowed
 * to write inside it and nowhere else, so the suite also proves the root is honoured
 * without needing a permission mock.</p>
 */
class StandardLibraryTest {

    @TempDir
    Path root;

    // -- text ----------------------------------------------------------------

    static Stream<Arguments> text() {
        return Stream.of(
                Arguments.of("\"abc\".upper()", "ABC"),
                Arguments.of("\"ABC\".lower()", "abc"),
                Arguments.of("\"  pad  \".trim()", "pad"),
                Arguments.of("\"a,b,c\".split(\",\").length", "3"),
                Arguments.of("\"a,b,\".split(\",\")[2]", ""),
                Arguments.of("\"one two\".split()[1]", "two"),
                Arguments.of("\"aXbXc\".replace(\"X\", \"-\")", "a-b-c"),
                Arguments.of("\"aaa\".replaceFirst(\"a\", \"b\")", "baa"),
                Arguments.of("\"hello\".contains(\"ell\")", "true"),
                Arguments.of("\"hello\".startsWith(\"he\")", "true"),
                Arguments.of("\"hello\".endsWith(\"lo\")", "true"),
                Arguments.of("\"hello\".indexOf(\"z\")", "-1"),
                Arguments.of("\"hello\"[1]", "e"),
                Arguments.of("\"hello\".charAt(1)", "e"),
                Arguments.of("\"hello\".substring(1, 3)", "el"),
                Arguments.of("\"hello\".substring(2)", "llo"),
                Arguments.of("\"42\".toInt() + 1", "43"),
                Arguments.of("\"2.5\".toDouble() * 2", "5.0"),
                Arguments.of("\"ab\".repeat(3)", "ababab"),
                Arguments.of("\"7\".padStart(4, \"0\")", "0007"),
                Arguments.of("\"7\".padEnd(3, \"-\")", "7--"),
                Arguments.of("\"hello\".length", "5"),
                Arguments.of("\"x\".repeat(0)", ""),
                // Splitting empty text is one empty field, not none: a CSV line of
                // zero columns is not something the language can answer with a list.
                Arguments.of("\"\".split(\",\").length", "1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("text members")
    void text(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- lists ---------------------------------------------------------------

    static Stream<Arguments> lists() {
        return Stream.of(
                Arguments.of("[1, 2, 3].length", "3"),
                Arguments.of("[1, 2, 3].first", "1"),
                Arguments.of("[1, 2, 3].last", "3"),
                Arguments.of("[].isEmpty()", "true"),
                Arguments.of("[3, 1, 2].sort().join(\",\")", "1,2,3"),
                Arguments.of("[\"bb\", \"a\"].sortBy({ s -> s.length }).join(\",\")", "a,bb"),
                Arguments.of("let l = [3, 1, 2]; l.sorted().join(\",\")", "1,2,3"),
                Arguments.of("[1, 2].reversed().join(\",\")", "2,1"),
                Arguments.of("[1, 2, 3].map({ x -> x * 2 }).join(\",\")", "2,4,6"),
                // A two-arity callback is indexed, which is what a script wants when
                // it needs the position as well as the value.
                Arguments.of("[1, 2, 3].map({ x, i -> i }).join(\",\")", "0,1,2"),
                Arguments.of("[1, 2, 3, 4].filter({ x -> x % 2 == 0 }).join(\",\")", "2,4"),
                Arguments.of("[1, 2, 3].reduce({ a, b -> a + b })", "6"),
                Arguments.of("[1, 2, 3].reduce({ a, b -> a + b }, 10)", "16"),
                Arguments.of("[1, 2, 3].join(\"-\")", "1-2-3"),
                Arguments.of("[1, 2, 3].any({ x -> x > 2 })", "true"),
                Arguments.of("[1, 2, 3].all({ x -> x > 0 })", "true"),
                Arguments.of("[1, 2, 3].all({ x -> x > 2 })", "false"),
                Arguments.of("[0, 0].any()", "false"),
                Arguments.of("[0, 1].any()", "true"),
                Arguments.of("[1, 2, 3].find({ x -> x == 2 })", "2"),
                Arguments.of("[1, 2, 3].indexOf(2)", "1"),
                Arguments.of("[1, 2, 3].contains(4)", "false"),
                Arguments.of("[1, 2, 3].slice(1).join(\",\")", "2,3"),
                Arguments.of("[1, 2, 3].slice(0, 2).join(\",\")", "1,2"),
                Arguments.of("[1, 2, 3, 4].take(2).join(\",\")", "1,2"),
                Arguments.of("[1, 2, 3, 4].skip(2).join(\",\")", "3,4"),
                Arguments.of("[1, 1, 2].distinct().join(\",\")", "1,2"),
                Arguments.of("[1, 2, 3].sum()", "6"),
                Arguments.of("[3, 1].min()", "1"),
                Arguments.of("[3, 1].max()", "3"),
                Arguments.of("[[1], [2]].flatten().join(\",\")", "1,2"),
                Arguments.of("let l = [1]; l.push(2).length", "2"),
                Arguments.of("let l = [1, 2, 3]; l.removeAt(0)", "1"),
                Arguments.of("let l = [1, 2]; l.pop()", "2"),
                Arguments.of("[1, 2, 3].get(0)", "1"),
                Arguments.of("[1, 2, 3].get(9, 0)", "0"),
                Arguments.of("[\"a\", \"b\"].join(\"\")", "ab"),
                Arguments.of("let l = [1, 2]; l.set(0, 9).join(\",\")", "9,2"),
                Arguments.of("let l = [2]; l.insert(0, 1).join(\",\")", "1,2"),
                Arguments.of("let l = [1]; l.clear(); l.length", "0"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("list members")
    void lists(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- maps ----------------------------------------------------------------

    static Stream<Arguments> maps() {
        return Stream.of(
                Arguments.of("let m = {\"a\": 1}; m.get(\"a\")", "1"),
                Arguments.of("let m = {\"a\": 1}; m.get(\"b\", 9)", "9"),
                Arguments.of("let m = {\"a\": 1}; m.has(\"a\")", "true"),
                Arguments.of("let m = {\"a\": 1}; m.keys.join(\",\")", "a"),
                Arguments.of("let m = {\"a\": 1, \"b\": 2};"
                        + " m.values.map({ v -> str(v) }).join(\",\")", "1,2"),
                Arguments.of("let m = {\"a\": 1}; m.length", "1"),
                Arguments.of("let m = {\"a\": 1}; m.put(\"b\", 2).length", "2"),
                Arguments.of("let m = {\"a\": 1}; m.merge({\"b\": 2}).length", "2"),
                Arguments.of("let m = {\"a\": 1}; m.mapValues({ v -> v + 1 }).get(\"a\")", "2"),
                Arguments.of("let m = {\"a\": 1}; m.entries()[0][0]", "a"),
                Arguments.of("let m = {\"a\": 1}; m.remove(\"a\")", "1"),
                Arguments.of("let m = {\"a\": 1}; m[\"a\"]", "1"),
                // A key that spells a member name still reads as a key: the member
                // path is only taken when the map has no such entry.
                Arguments.of("let m = {\"json\": 1}; m.json", "1"),
                Arguments.of("let m = {\"map\": 1}; m.map", "1"),
                Arguments.of("let m = {\"a\": 1}; m.copy().put(\"b\", 2).length", "2"),
                Arguments.of("let m = {\"a\": 1}; var s = 0;"
                        + " m.forEach({ k, v -> s = s + v }); s", "1"),
                Arguments.of("let m = {\"a\": 1}; m.isEmpty()", "false"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("map members")
    void maps(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- ranges --------------------------------------------------------------

    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of("(0..2).toList().join(\",\")", "0,1,2"),
                Arguments.of("(0..<5).length", "5"),
                Arguments.of("(1..3).sum()", "6"),
                // Closed form, so a billion-value range answers as fast as a small one.
                Arguments.of("(0..100).sum()", "5050"),
                Arguments.of("(1..3).contains(3)", "true"),
                Arguments.of("(1..3).first", "1"),
                Arguments.of("(1..3).last", "3"),
                Arguments.of("(1..3).reversed().join(\",\")", "3,2,1"),
                Arguments.of("(0..2).map({ x -> x }).length", "3"),
                Arguments.of("(0..0).isEmpty()", "false"),
                // A single-value range is not empty, and a stride that cannot reach
                // the bound stops below it rather than overshooting.
                Arguments.of("(0..8 step 3).toList().join(\",\")", "0,3,6"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("range members")
    void ranges(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- numbers -------------------------------------------------------------

    static Stream<Arguments> numbers() {
        return Stream.of(
                Arguments.of("(-5).abs()", "5"),
                Arguments.of("2.7.round()", "3"),
                Arguments.of("2.2.floor()", "2.0"),
                Arguments.of("2.1.ceil()", "3.0"),
                Arguments.of("(9).sqrt()", "3.0"),
                Arguments.of("(5).toInt()", "5"),
                Arguments.of("(5).isWhole()", "true"),
                Arguments.of("(5).sign()", "1"),
                Arguments.of("(0).sign()", "0"),
                Arguments.of("(5).toDouble() + 0.5", "5.5"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("number members")
    void numbers(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- global functions ----------------------------------------------------

    static Stream<Arguments> globals() {
        return Stream.of(
                Arguments.of("str(12)", "12"),
                Arguments.of("int(\"7\")", "7"),
                Arguments.of("double(\"1.5\") + 0.5", "2.0"),
                Arguments.of("bool(0)", "false"),
                Arguments.of("length([1, 2])", "2"),
                Arguments.of("typeOf(1.5)", "Double"),
                Arguments.of("min(3, 1, 2)", "1"),
                Arguments.of("max(3, 1, 2)", "3"),
                Arguments.of("abs(-2)", "2"),
                Arguments.of("keys({\"a\": 1}).join(\",\")", "a"),
                Arguments.of("valuesOf({\"a\": 1}).join(\",\")", "1"),
                Arguments.of("assert(1 == 1); 1", "1"),
                // The host's own binding is visible to a program with no setup.
                Arguments.of("answer + 0", "42"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("global functions")
    void globals(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- the std.* modules ---------------------------------------------------

    static Stream<Arguments> modules() {
        return Stream.of(
                Arguments.of("use std.json\njson.encode([1, \"a\", null])", "[1,\"a\",null]"),
                Arguments.of("use std.json\njson.parse(\"{\\\"k\\\": [1, 2]}\").k[1]", "2"),
                Arguments.of("use std.json\njson.isValid(\"[1,]\")", "false"),
                Arguments.of("use std.json\njson.stringify(1.5)", "1.5"),
                Arguments.of("use std.math\nmath.floor(2.9)", "2.0"),
                Arguments.of("use std.math\nmath.pow(2, 10)", "1024.0"),
                Arguments.of("use std.math\nmath.randomInt(5, 5)", "5"),
                Arguments.of("use std.math\nmath.abs(-3)", "3"),
                Arguments.of("use std.math\nmath.PI > 3.14", "true"),
                Arguments.of("use std.time\ntime.fields(0).length > 6", "true"),
                Arguments.of("use std.time\ntime.parse(\"1970-01-01T00:00:00Z\")", "0"),
                Arguments.of("use std.time\ntime.iso(0)", "1970-01-01T00:00:00Z"),
                Arguments.of("use std.fs\nfs.read(\"note.txt\").startsWith(\"alpha\")", "true"),
                Arguments.of("use std.fs\nfs.lines(\"note.txt\").length", "2"),
                Arguments.of("use std.fs\nfs.write(\"out.txt\", \"x\"); fs.read(\"out.txt\")", "x"),
                Arguments.of("use std.fs\nfs.write(\"out.txt\", \"x\");"
                        + " fs.append(\"out.txt\", \"y\"); fs.read(\"out.txt\")", "xy"),
                Arguments.of("use std.fs\nfs.exists(\"missing.txt\")", "false"),
                Arguments.of("use std.fs\nfs.list(\"\").contains(\"note.txt\")", "true"),
                Arguments.of("use std.fs\nfs.mkdir(\"sub\"); fs.isDirectory(\"sub\")", "true"),
                Arguments.of("use std.fs\nfs.write(\"gone.txt\", \"1\");"
                        + " fs.delete(\"gone.txt\")", "true"),
                Arguments.of("use std.fs\nfs.write(\"big.txt\", \"abc\");"
                        + " fs.size(\"big.txt\")", "3"),
                Arguments.of("use std.fs\nfs.root().length > 0", "true"),
                Arguments.of("use std.env\nenv.has(\"PATH\")", "true"),
                Arguments.of("use std.env\nenv.get(\"NO_SUCH_VAR_YET\", \"d\")", "d"),
                Arguments.of("use std.env\nenv.separator.length", "1"),
                Arguments.of("use std.env\nenv.names().length > 0", "true"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("the std modules reached with 'use'")
    void modules(String source, String expected) {
        assertEquals(expected, shown(source), source);
    }

    // -- failures a script can catch ----------------------------------------

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("use std.json\njson.parse(\"{\")", "JsonError"),
                Arguments.of("(-9).sqrt()", "ValueError"),
                Arguments.of("[1, 2].map(3)", "TypeError"),
                Arguments.of("\"a\" + 1", "TypeError"),
                Arguments.of("[1, 2].nope", "TypeError"),
                Arguments.of("\"hello\".charAt(9)", "IndexError"),
                Arguments.of("use std.nope", "ValueError"),
                Arguments.of("assert(1 == 2, \"nope\")", "AssertError"),
                // A module that is not there is a file that cannot be read, which is
                // the same answer the language gives for any other missing file.
                Arguments.of("import \"nothing.mnd\"", "IOError"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a library failure is a catchable error with a kind")
    void refusals(String source, String kind) {
        MandelaError raised = Scripts.failure(source, engineWithFiles());
        assertEquals(kind, raised.kind(), source + " -> " + raised.kind() + ":"
                + raised.getMessage());
    }

    // -- the console belongs to the host ------------------------------------

    @Test
    @DisplayName("println and eprintln go to the host's channels, not to System.out")
    void console() {
        List<String> out = new ArrayList<>();
        List<String> err = new ArrayList<>();
        Runtime runtime = engineWithFiles().output(out::add).error(err::add).build();
        runtime.run("println(\"one\", 2)", "lib.mnd");
        runtime.run("eprintln(\"bad\")", "lib.mnd");
        // One write per line, terminator included: a host that pipes the desktop's
        // script output into a file gets the same bytes it would on a terminal.
        assertEquals("one 2\n", String.join("", out));
        assertEquals("bad\n", String.join("", err));
    }

    @Test
    @DisplayName("a host with no filesystem still loads std.fs, and is told why")
    void sandboxedHost() {
        Runtime browser = Mandela.engine(Capabilities.webPage()).build();
        MandelaError fs = Scripts.failure(
                () -> browser.run("use std.fs\nfs.read(\"x\")", "lib.mnd"));
        assertEquals("PermissionError", fs.kind());
        MandelaError env = Scripts.failure(
                () -> browser.run("use std.env\nenv.get(\"PATH\")", "lib.mnd"));
        assertEquals("PermissionError", env.kind());
    }

    // -- plumbing ------------------------------------------------------------

    /**
     * Runs one snippet on an engine that may read and write this test's directory,
     * and returns the text its answer displays as.
     *
     * @param source the snippet
     * @return the displayed answer, {@code "null"} for no value
     */
    private String shown(String source) {
        return Values.display(engineWithFiles().build().run(prepare(source), "lib.mnd"));
    }

    /**
     * An engine with the grants the library needs: both console channels, a
     * filesystem rooted at this invocation's temporary directory, the environment,
     * and one host binding the globals cases read.
     *
     * @return a builder, so a caller can add its own output sinks
     */
    private Runtime.Builder engineWithFiles() {
        return Scripts.files(root).bind("answer", 42L);
    }

    /**
     * Writes the file the {@code std.fs} cases expect, because a snippet that reads
     * {@code note.txt} is easier to read than one that creates it first.
     *
     * @param source the snippet
     * @return the snippet, unchanged
     */
    private String prepare(String source) {
        if (source.contains("note.txt")) {
            try {
                Files.writeString(root.resolve("note.txt"), "alpha\nbeta\n");
            } catch (IOException problem) {
                throw new IllegalStateException("cannot seed the sandbox", problem);
            }
        }
        return source;
    }
}
