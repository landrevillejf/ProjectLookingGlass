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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.rt.StandardLibrary;
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
                // The start index is honoured, not ignored - the whole idiom of a
                // hand-written scanner is asking for the next occurrence after the
                // one it just found. A negative start is the 0 a failed lookup
                // answers rather than a count from the end, so a second search
                // never depends on where the first one failed; a start past the end
                // finds nothing instead of refusing, and a long too big for an
                // index is clamped to the end rather than wrapping around.
                Arguments.of("\"a-b-c\".indexOf(\"-\")", "1"),
                Arguments.of("\"a-b-c\".indexOf(\"-\", 2)", "3"),
                Arguments.of("\"a-b-c\".indexOf(\"-\", -1)", "1"),
                Arguments.of("\"a-b-c\".indexOf(\"a\", 1)", "-1"),
                Arguments.of("\"a-b-c\".indexOf(\"b\", 3)", "-1"),
                Arguments.of("\"a-b-c\".indexOf(\"\", 2)", "2"),
                Arguments.of("\"a-b-c\".indexOf(\"c\", 9223372036854775807)", "-1"),
                // The scanner the language actually writes: the second quote is not
                // the first one, which is what this call used to answer.
                Arguments.of("let t = \"f('x')\"; t.indexOf(\"'\", t.indexOf(\"'\") + 1)",
                        "4"),
                Arguments.of("\"hello\"[1]", "e"),
                Arguments.of("\"hello\".charAt(1)", "e"),
                Arguments.of("\"hello\".substring(1, 3)", "el"),
                Arguments.of("\"hello\".substring(2)", "llo"),
                // An inverted window is empty rather than an error, the way a
                // slice of a list is; the bounds are clamped, never swapped.
                Arguments.of("\"hello\".substring(3, 1)", ""),
                Arguments.of("\"42\".toInt() + 1", "43"),
                Arguments.of("\"2.5\".toDouble() * 2", "5.0"),
                Arguments.of("\"ab\".repeat(3)", "ababab"),
                Arguments.of("\"7\".padStart(4, \"0\")", "0007"),
                Arguments.of("\"7\".padEnd(3, \"-\")", "7--"),
                Arguments.of("\"hello\".length", "5"),
                Arguments.of("\"x\".repeat(0)", ""),
                // Splitting empty text is one empty field, not none: a CSV line of
                // zero columns is not something the language can answer with a list.
                Arguments.of("\"\".split(\",\").length", "1"),
                // A separator that is not found leaves the text alone, and "" means
                // the characters, which is what a caller who wrote it is after.
                Arguments.of("\"abc\".replaceFirst(\"z\", \"-\")", "abc"),
                Arguments.of("\"abc\".split(\"\").join(\"-\")", "a-b-c"),
                // A bare split runs on whitespace: a run of it is one separator, and
                // the text does not start or end with an empty field.
                Arguments.of("\"a  b \".split().join(\",\")", "a,b"),
                Arguments.of("\"  a\".split().join(\",\")", "a"),
                // An omitted argument that has no default reaches the call as the
                // absent marker, and a bare split is what that has to mean here.
                Arguments.of("fun f(a = \"x\", b) -> Str { \"p q\".split(b).join(\"-\") }\n"
                        + "f()", "p-q"),
                // The fill defaults to a space, an empty fill pads nothing, and a
                // text already at the width is handed back untouched.
                Arguments.of("\"7\".padStart(4)", "   7"),
                Arguments.of("\"abc\".padStart(2, \"x\")", "abc"),
                Arguments.of("\"\".padStart(3, \"\")", ""),
                Arguments.of("\"abc\".padEnd(6, \"xy\")", "abcxyx"),
                // count is the scan every shipped gate hand-rolled with indexOf
                // in a loop: non-overlapping left to right, an absent needle at
                // every position and one at the end (n + 1, the way split("")
                // reads the characters), and no match at zero, never a refusal.
                Arguments.of("\"a-b-c\".count(\"-\")", "2"),
                Arguments.of("\"aaaa\".count(\"aa\")", "2"),
                Arguments.of("\"abc\".count(\"z\")", "0"),
                Arguments.of("\"abc\".count(\"\")", "4"),
                Arguments.of("\"\".count(\"\")", "1"),
                Arguments.of("\"key = value extra\".splitOnce(\" = \").join(\"|\")",
                        "key|value extra"),
                // An absent separator is the whole text and no remainder; the
                // empty one is the characters, the first and the rest, the way
                // split("") already spells them.
                Arguments.of("\"key\".splitOnce(\"=\").join(\"|\")", "key|"),
                Arguments.of("\"abc\".splitOnce(\"\").join(\"|\")", "a|bc"),
                // The empty text split on the empty separator is the pair of
                // nothing the characters would have been: no first, no rest.
                Arguments.of("\"\".splitOnce(\"\").join(\"|\")", "|"),
                Arguments.of("\" \".isBlank() && \"\\t\\n\".isBlank()", "true"),
                Arguments.of("\"a b\".isBlank()", "false"),
                Arguments.of("\"\".isBlank()", "true"),
                // The strip pair reads as startsWith/endsWith already do: no
                // match hands the text back untouched, and an empty suffix is
                // every text's own ending - removing it removes nothing.
                Arguments.of("\"/etc/hosts\".removePrefix(\"/etc/\")", "hosts"),
                Arguments.of("\"hosts\".removePrefix(\"/etc/\")", "hosts"),
                Arguments.of("\"report.mnd\".removeSuffix(\".mnd\")", "report"),
                Arguments.of("\"report.md\".removeSuffix(\".mnd\")", "report.md"),
                Arguments.of("\"a\".removeSuffix(\"\")", "a"),
                // Line ends are not one character in a tree edited on three
                // platforms: CRLF is one end, a lone CR ends a line, and text
                // that ends in its terminator answers no trailing empty line.
                Arguments.of("\"a\\nb\\n\".lines().join(\",\")", "a,b"),
                Arguments.of("\"a\\r\\nb\\rc\".lines().join(\",\")", "a,b,c"),
                // A CR that is not followed by a LF still ends the line it is
                // on - the pair is one end, a lone carriage is its own.
                Arguments.of("\"a\\rb\".lines().join(\",\")", "a,b"),
                // A carriage return as the text's very last character has no
                // next byte to look at, and still ends the line it is on.
                Arguments.of("\"a\\r\\nb\\r\".lines().join(\",\")", "a,b"),
                Arguments.of("\"\".lines().length", "0"),
                Arguments.of("\"a  b \".words().join(\",\")", "a,b"));
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
                // The same start index the text search takes, with the same rules.
                Arguments.of("[1, 2, 1].indexOf(1, 1)", "2"),
                Arguments.of("[1, 2, 1].indexOf(1, -1)", "0"),
                Arguments.of("[1, 2].indexOf(2, 2)", "-1"),
                Arguments.of("[1, 2].indexOf(1, 9223372036854775807)", "-1"),
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
                Arguments.of("let l = [1]; l.clear(); l.length", "0"),
                Arguments.of("[1, 2, 3].size", "3"),
                // add is push with one value; an omitted value is the null the
                // call site did not write, not a refusal.
                Arguments.of("let l = [1]; l.add(2).join(\",\")", "1,2"),
                Arguments.of("let l = [1]; l.add(); l.length", "2"),
                Arguments.of("let l = []; l.pop()", "null"),
                Arguments.of("[1, 2].get(-1, 9)", "9"),
                Arguments.of("[1, 2].indexOf(9)", "-1"),
                Arguments.of("[1, 2].join()", "12"),
                // s is written from inside the callback, so it is a var: a let
                // would refuse the assignment before the list is even walked.
                Arguments.of("var s = 0; [1, 2, 3].forEach({ x -> s = s + x }); s", "6"),
                Arguments.of("[1, 2].find({ x -> x > 5 })", "null"),
                Arguments.of("let l = [\"bb\", \"a\"];"
                        + " l.sortedBy({ s -> s.length }).join(\",\")", "a,bb"),
                Arguments.of("[1, [2], 3].flatten().join(\",\")", "1,2,3"),
                // A fold with no seed and nothing to fold answers null; a range of a
                // count that cannot be taken is the count that can.
                Arguments.of("[].reduce({ a, b -> a + b })", "null"),
                Arguments.of("[1, 2].take(9).join(\",\")", "1,2"),
                Arguments.of("[1, 2, 3].slice(-10, 2).join(\",\")", "1,2"),
                // A comparator is the script's own order, negative and positive
                // both read, so a descending sort is one line.
                Arguments.of("[1, 2, 3].sort({ a, b -> b - a }).join(\",\")", "3,2,1"),
                // The callback may take fewer names than the builtin can pass, and
                // may be variadic, in which case every argument is theirs to read.
                Arguments.of("fun pick(a, b = 9) -> Int { a * 10 + b }\n"
                        + "[1, 2].map(pick).join(\",\")", "10,21"),
                Arguments.of("[1, 2].map(\"a\".upper).join(\",\")", "A,A"),
                Arguments.of("[5, 5, 1].min()", "1"),
                Arguments.of("[1, 5, 5].max()", "5"),
                // count answers the size of the filter the caller would build
                // to throw the values away; bare, it reads the truthiness any()
                // and all() already answer for, and the two-arity callback is
                // indexed like map's.
                Arguments.of("[1, 2, 3].count({ x -> x > 1 })", "2"),
                Arguments.of("[0, 1, \"\", \"a\"].count()", "2"),
                Arguments.of("[1, 2, 3].count({ x, i -> i == 0 })", "1"),
                // partition is the same walk in halves: each in its original
                // order, both lists present even when one is empty.
                Arguments.of("[1, 2, 3, 4].partition({ x -> x % 2 == 0 })"
                        + ".map({ h -> h.join(\"-\") }).join(\"|\")", "2-4|1-3"),
                Arguments.of("[1].partition({ x -> true })[1].length", "0"));
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
                Arguments.of("let m = {\"a\": 1}; m.isEmpty()", "false"),
                Arguments.of("let m = {\"a\": 1}; m.size", "1"),
                Arguments.of("let m = {\"a\": 1}; m.get(\"b\")", "null"),
                Arguments.of("let m = {\"a\": 1}; m.set(\"b\", 2).length", "2"),
                Arguments.of("let m = {\"a\": 1}; m.clear(); m.length", "0"));
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
                Arguments.of("(0..8 step 3).toList().join(\",\")", "0,3,6"),
                Arguments.of("(0..2 step 2).sum()", "2"),
                // An empty range sums to zero, and the closed form is exact while
                // the terms fit an Int and a Double once they do not.
                Arguments.of("(0..<0).sum()", "0"),
                Arguments.of("(0..1000).sum()", "500500"),
                // 2**40 terms added from both edges do not fit a long, so the
                // closed form answers a Double rather than a wrapped Int.
                Arguments.of("typeOf((0..100000000000).sum())", "Double"),
                // The sequence vocabulary is the list's own over a materialised
                // copy, so a range reads wherever a list does.
                Arguments.of("(0..3).join(\"-\")", "0-1-2-3"),
                Arguments.of("(0..3).min()", "0"),
                Arguments.of("(0..2).sortedBy({ x -> 0 - x }).join(\",\")", "2,1,0"),
                Arguments.of("var s = 0; (0..2).forEach({ x -> s = s + x }); s", "3"));
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
                Arguments.of("(5).toDouble() + 0.5", "5.5"),
                Arguments.of("(27).cbrt()", "3.0"),
                Arguments.of("(0 - 8).cbrt()", "-2.0"),
                Arguments.of("(-2.5).abs()", "2.5"),
                // A double that is already whole converts; one that is not is a
                // refusal rather than a rounding nobody asked for.
                Arguments.of("int(3.0)", "3"),
                // The most negative Int has no positive partner in the range, so
                // abs promotes the way the unary minus does instead of wrapping.
                Arguments.of("abs(0 - 9223372036854775807 - 1)", "9.223372036854776E18"),
                Arguments.of("(0 - 9223372036854775807 - 1).abs()",
                        "9.223372036854776E18"),
                // -2**63 is the one edge of the Int range that is exactly a double,
                // so it converts; there is no Int above 2**63 - 1 to lose, and the
                // refusal for one is the refusals case below.
                Arguments.of("use std.math\nint(0 - math.pow(2, 63))",
                        "-9223372036854775808"));
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
                Arguments.of("abs(-2.5)", "2.5"),
                // min and max keep the type they were handed, and answer the first
                // of equals rather than reordering a value the script can read.
                Arguments.of("min(1, 2, 3)", "1"),
                Arguments.of("max(1, 2, 3)", "3"),
                Arguments.of("max(\"a\", \"c\", \"b\")", "c"),
                // A raised kind reaches a catch in the same script that raised it.
                Arguments.of("var hit = \"\"; try { error(\"boom\", \"MyKind\") }"
                        + " catch e { hit = e.kind }; hit", "MyKind"),
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
                Arguments.of("use std.math\nmath.ceil(2.1)", "3.0"),
                // round is the Java contract, half toward positive, not half away
                // from zero: -2.5 lands on -2, and a script can read why.
                Arguments.of("use std.math\nmath.round(2.5)", "3"),
                Arguments.of("use std.math\nmath.round(-2.5)", "-2"),
                Arguments.of("use std.math\nmath.sqrt(9)", "3.0"),
                Arguments.of("use std.math\nmath.cbrt(27)", "3.0"),
                Arguments.of("use std.math\nmath.exp(0)", "1.0"),
                Arguments.of("use std.math\nmath.log(1)", "0.0"),
                Arguments.of("use std.math\nmath.log10(100)", "2.0"),
                Arguments.of("use std.math\nmath.log2(2)", "1.0"),
                Arguments.of("use std.math\nmath.sin(0)", "0.0"),
                Arguments.of("use std.math\nmath.cos(0)", "1.0"),
                Arguments.of("use std.math\nmath.tan(0)", "0.0"),
                Arguments.of("use std.math\nmath.asin(0)", "0.0"),
                Arguments.of("use std.math\nmath.acos(1)", "0.0"),
                Arguments.of("use std.math\nmath.atan(0)", "0.0"),
                Arguments.of("use std.math\nmath.atan2(0, 1)", "0.0"),
                Arguments.of("use std.math\nmath.hypot(3, 4)", "5.0"),
                // The module's min/max answer with the value, Int staying an Int.
                Arguments.of("use std.math\nmath.min(3, 1)", "1"),
                Arguments.of("use std.math\nmath.max(3, 1)", "3"),
                Arguments.of("use std.math\nmath.sign(-2)", "-1"),
                Arguments.of("use std.math\nmath.sign(0)", "0"),
                Arguments.of("use std.math\nmath.abs(-2.5)", "2.5"),
                Arguments.of("use std.math\nmath.pow(2, 10)", "1024.0"),
                // random answers the half-open unit interval, which is the whole
                // promise; the exact value is nobody's to pin.
                Arguments.of("use std.math\nlet r = math.random(); (r >= 0) && (r < 1)",
                        "true"),
                Arguments.of("use std.math\nmath.randomInt(5, 5)", "5"),
                Arguments.of("use std.math\nmath.abs(-3)", "3"),
                Arguments.of("use std.math\nmath.PI > 3.14", "true"),
                Arguments.of("use std.time\ntime.fields(0).length > 6", "true"),
                // The raw clocks answer Ints - epoch millis and a monotonic tick.
                Arguments.of("use std.time\ntypeOf(time.now())", "Int"),
                Arguments.of("use std.time\ntypeOf(time.nanoTime())", "Int"),
                Arguments.of("use std.time\ntime.parse(\"1970-01-01T00:00:00Z\")", "0"),
                Arguments.of("use std.time\ntime.iso(0)", "1970-01-01T00:00:00Z"),
                // Formatting the epoch with an explicit pattern is locale-proof by
                // contract (ROOT), and the length is timezone-agnostic: only the
                // day could shift, never the count of characters.
                Arguments.of("use std.time\ntime.format(0, \"yyyy-MM-dd\").length", "10"),
                // Bare, it reads the clock and the default pattern - nineteen
                // characters for 'yyyy-MM-dd HH:mm:ss' whatever the instant.
                Arguments.of("use std.time\ntime.format().length", "19"),
                // Sleeping zero seconds answers when the run may park a thread
                // (THREAD), which is the call itself going through.
                Arguments.of("use std.time\ntime.sleep(0); 1", "1"),
                Arguments.of("use std.time\ntime.zone().length > 0", "true"),
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
                Arguments.of("use std.fs\nfs.write(\"when.txt\", \"x\");"
                        + " fs.modified(\"when.txt\") > 0", "true"),
                Arguments.of("use std.fs\nfs.root().length > 0", "true"),
                Arguments.of("use std.env\nenv.has(\"PATH\")", "true"),
                Arguments.of("use std.env\nenv.get(\"NO_SUCH_VAR_YET\", \"d\")", "d"),
                // A variable that is there reads, not just one that is missing.
                Arguments.of("use std.env\nenv.get(\"PATH\").length > 0", "true"),
                Arguments.of("use std.env\nenv.separator.length", "1"),
                Arguments.of("use std.env\nenv.names().length > 0", "true"),
                // The host's own identity, as Str whatever the machine answers.
                Arguments.of("use std.env\ntypeOf(env.user())", "Str"),
                Arguments.of("use std.env\ntypeOf(env.os())", "Str"),
                Arguments.of("use std.env\nenv.pathRoots().length > 0", "true"),
                // Glob: the browser's `// @match` vocabulary as a script call, not
                // a host translation. `*` is any run (including none), `?` is
                // exactly one character, everything else is literal, and the
                // match is of the whole text - an unanchored tail is no match.
                Arguments.of("use std.glob\nglob.matches(\"http://example.com/\", \"http://*\")",
                        "true"),
                Arguments.of("use std.glob\nglob.matches(\"http://x.io\", \"https://*\")",
                        "false"),
                Arguments.of("use std.glob\nglob.matches(\"ab\", \"a*b\")", "true"),
                Arguments.of("use std.glob\nglob.matches(\"abc\", \"a?c\")", "true"),
                Arguments.of("use std.glob\nglob.matches(\"ac\", \"a?c\")", "false"),
                Arguments.of("use std.glob\nglob.matches(\"\", \"*\")", "true"),
                Arguments.of("use std.glob\nglob.matches(\"a\", \"\")", "false"),
                Arguments.of("use std.glob\nglob.matches(\"a.b.c\", \"*.*.*\")", "true"),
                // Two stars, and the segments they separate need ground of
                // their own: `*a*a` names "aa" - one a per segment - and no
                // more, where a greedy one-pointer matcher would let the
                // first `a` of "ba" stand in for both.
                Arguments.of("use std.glob\nglob.matches(\"ba\", \"*a*a\")", "false"),
                Arguments.of("use std.glob\nglob.matches(\"aa\", \"*a*a\")", "true"),
                Arguments.of("use std.glob\nglob.matches(\"banana\", \"*a*a\")", "true"),
                // A `?` in a middle segment costs a scan, not a retry: the
                // wildcard stands for exactly the one character it covers.
                Arguments.of("use std.glob\nglob.matches(\"xxa1bxx\", \"*a?b*\")", "true"),
                Arguments.of("use std.glob\nglob.matches(\"xxabxx\", \"*a?b*\")", "false"),
                // Consecutive stars are one star - a pattern with a run of
                // them matches exactly what one of them does.
                Arguments.of("use std.glob\nglob.matches(\"abc\", \"a**c\")", "true"),
                // The matcher is literal: `?` is not optional and a dot is not
                // a class, which is the whole argument for a glob over a
                // pattern - what you wrote is exactly what matches.
                Arguments.of("use std.glob\nglob.matches(\"axbc\", \"a.c\")", "false"),
                Arguments.of("use std.glob\nglob.matches(\"a\", \"a?\")", "false"),
                // isMatch reads the same way round as matches(text, pattern):
                // the pattern first, like a file name before its candidate -
                // both spellings are the matcher, never two matchers.
                Arguments.of("use std.glob\nglob.isMatch(\"*.mnd\", \"gate.mnd\")", "true"),
                Arguments.of("use std.glob\nglob.isMatch(\"*.mnd\", \"gate.md\")", "false"),
                // The starless pattern is the exact shape, so same length and a
                // literal mismatch is a refusal of the character, not of the
                // length - the arm a length check alone would answer wrong.
                Arguments.of("use std.glob\nglob.matches(\"ab\", \"ac\")", "false"),
                // A head longer than the candidate is no match and not an
                // index out of the text: the pattern is cut by its own first
                // star, which knows nothing about how long the text is.
                Arguments.of("use std.glob\nglob.matches(\"a\", \"abc*\")", "false"),
                // The tail is anchored before the middle is read, so a tail
                // whose ground the head already consumed answers false rather
                // than scanning past the end of the text.
                Arguments.of("use std.glob\nglob.matches(\"a\", \"a*ab\")", "false"),
                // Consecutive stars collapse wherever they sit, not only at
                // the ends: the empty segment between them asks for nothing.
                Arguments.of("use std.glob\nglob.matches(\"axbxc\", \"a**b*c\")", "true"));
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
                // A start index has to be an index; a search still refuses a shape
                // it cannot compare against rather than guessing.
                Arguments.of("\"ab\".indexOf(\"b\", \"x\")", "TypeError"),
                Arguments.of("use std.nope", "ValueError"),
                Arguments.of("assert(1 == 2, \"nope\")", "AssertError"),
                // A module that is not there is a file that cannot be read, which is
                // the same answer the language gives for any other missing file.
                Arguments.of("import \"nothing.mnd\"", "IOError"),
                // The padding ceiling is repeat's: a width read off a bad value is
                // a refusal, not a loop that eats the heap on the way to nothing.
                Arguments.of("\"x\".padStart(20000000, \"0\")", "LimitError"),
                Arguments.of("\"x\".padEnd(20000000, \"0\")", "LimitError"),
                // 2**63 has no long: the conversion says so instead of handing back
                // the largest Int as if that were the answer.
                Arguments.of("use std.math\nint(math.pow(2, 63))", "LimitError"),
                // A Range has nothing to reorder in place, so the mutating spelling
                // is not one of its members - and an unknown member is the same
                // TypeError it is for a list, never a crash from inside the engine.
                Arguments.of("(0..2).sortBy({ x -> x })", "TypeError"),
                // The ceilings: a repeat and a padding that would eat the heap are
                // refused by name rather than attempted.
                Arguments.of("\"x\".repeat(16777217)", "LimitError"),
                Arguments.of("\"ab\".repeat(-1)", "ValueError"),
                // Materialising a range past its limit is refused before the walk,
                // whichever spelling asked for the copy.
                Arguments.of("(0..100000000000).toList()", "LimitError"),
                Arguments.of("(0..100000000000).reversed()", "LimitError"),
                Arguments.of("(0..100000000000).join(\"-\")", "LimitError"),
                // Strict indices refuse where the clamped spellings answer; a
                // defaulted get is the exception, a bare one is the rule.
                Arguments.of("let l = [1]; l.removeAt(5)", "IndexError"),
                Arguments.of("[1, 2].get(9)", "IndexError"),
                Arguments.of("let l = [1]; l.set(5, 9)", "IndexError"),
                // The shape refusals name the shape, so a wrong receiver reads as
                // a type error rather than a mysterious member failure.
                Arguments.of("keys([1])", "TypeError"),
                Arguments.of("valuesOf(\"a\")", "TypeError"),
                Arguments.of("let m = {\"a\": 1}; m.merge(1)", "TypeError"),
                Arguments.of("abs(\"x\")", "TypeError"),
                // Conversions refuse what they cannot answer: a fraction asked to
                // be an Int, and text that is no number at all.
                Arguments.of("int(2.7)", "ValueError"),
                Arguments.of("\"abc\".toInt()", "TypeError"),
                Arguments.of("double(\"abc\")", "TypeError"),
                // An argument that has to be text and is not, is a type error.
                Arguments.of("use std.json\njson.parse(1)", "TypeError"),
                // The bounds of the random draw: reversed is a refusal, and the
                // top of the Int range cannot be included in the draw anyway.
                Arguments.of("use std.math\nmath.randomInt(5, 1)", "ValueError"),
                Arguments.of("use std.math\nmath.randomInt(0, 9223372036854775807)",
                        "LimitError"),
                // The clock's own refusals: a string that is no instant, a pattern
                // that is no pattern, and a sleep that cannot go backwards.
                Arguments.of("use std.time\ntime.parse(\"yesterday\")", "ValueError"),
                Arguments.of("use std.time\ntime.format(0, \"yyyy-'\")", "ValueError"),
                Arguments.of("use std.time\ntime.sleep(-1)", "ValueError"),
                // A fold of nothing has no answer to read; the bare call is the
                // one that has to say so.
                Arguments.of("min()", "ValueError"),
                Arguments.of("max()", "ValueError"),
                // The new vocabulary refuses like the old: a shape it cannot
                // read names the shape, a predicate that is not callable names
                // the call, and a module function missing an argument says how
                // many it needs.
                Arguments.of("1.count(\"a\")", "TypeError"),
                Arguments.of("\"a\".count(1)", "TypeError"),
                Arguments.of("[1].count(3)", "TypeError"),
                Arguments.of("[1].partition(3)", "TypeError"),
                Arguments.of("\"a\".splitOnce(1)", "TypeError"),
                Arguments.of("use std.glob\nglob.matches(\"a\")", "ValueError"),
                Arguments.of("use std.glob\nglob.matches(1, \"a\")", "TypeError"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a library failure is a catchable error with a kind")
    void refusals(String source, String kind) {
        MandelaError raised = Scripts.failure(source, engineWithFiles());
        assertEquals(kind, raised.kind(), source + " -> " + raised.kind() + ":"
                + raised.getMessage());
    }

    @Test
    @DisplayName("a call missing an argument is told how many it needs")
    void missingArgumentMessage() {
        // The kind alone would let "2th" through; the sentence is the contract a
        // script author reads, so it is pinned as its own words.
        MandelaError second = Scripts.failure("\"ab\".replace(\"a\")", engineWithFiles());
        assertEquals("ValueError", second.kind());
        assertEquals("replace needs 2 arguments", second.getMessage());
        MandelaError first = Scripts.failure("\"hello\".substring()", engineWithFiles());
        assertEquals("substring needs an argument", first.getMessage());
    }

    @Test
    @DisplayName("the editor's member list for a Range is the copying spellings")
    void rangeMemberNames() {
        // The completion list is built straight off the library's own tables, so
        // what the language guide lists and what a script may call are the same
        // inventory; sortBy - which could only reorder a throwaway copy - is in
        // neither.
        List<String> names = new StandardLibrary().valueMemberNames("Range");
        assertTrue(names.contains("sortedBy"), names.toString());
        assertTrue(names.contains("sum"), names.toString());
        assertFalse(names.contains("sortBy"), names.toString());
    }

    @Test
    @DisplayName("an interrupted sleep is the Interrupted error the language promises")
    void sleepInterrupted() throws Exception {
        // A host that must stop a running script interrupts its thread; the
        // sleeping library turns that into the error the language promises
        // instead of letting the raw interrupt eat the run.
        Runtime sleeper = engineWithFiles().build();
        AtomicReference<MandelaError> raised = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                sleeper.run("use std.time\ntime.sleep(30)", "lib.mnd");
            } catch (MandelaError error) {
                raised.set(error);
            }
        }, "sleeping-script");
        thread.start();
        Thread.sleep(200);
        thread.interrupt();
        thread.join(10_000);
        assertFalse(thread.isAlive(), "the interrupted sleep never returned");
        assertNotNull(raised.get(), "the interrupt raised nothing");
        assertEquals("Interrupted", raised.get().kind());
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
        // A page host cannot import a file, and the capability gate answers
        // before the host's loader is ever asked - the same PermissionError the
        // reads get.
        MandelaError imported = Scripts.failure(
                () -> browser.run("import \"module.mnd\"", "lib.mnd"));
        assertEquals("PermissionError", imported.kind());
    }

    @Test
    @DisplayName("a host that wires the library with no loader refuses import by name")
    void importWithoutALoader() {
        // The Runtime facade always installs its own loader; a host that embeds
        // the library by hand and offers no import root still gets a script-
        // visible refusal naming what is missing, not a bare null or a crash.
        MandelaError refusal = Scripts.failure(
                () -> new StandardLibrary().importModule(null, "module.mnd"));
        assertEquals("ValueError", refusal.kind());
        assertTrue(refusal.getMessage().contains("cannot be imported by this host"),
                refusal.getMessage());
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
