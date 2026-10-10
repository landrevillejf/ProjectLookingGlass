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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The grammar's own matrix: snippets that must parse, and the mistakes that must
 * not.
 *
 * <p>This suite exists because a parser can fail in two opposite ways, and both are
 * shipped defects. A construct that used to parse and no longer does is a silent
 * break for every script already written; a mistake that parses is worse, because the
 * program runs and answers &mdash; wrongly. Half of the cases below are written to
 * produce a diagnostic, and each names the fragment of the message its author had to
 * read to work out what went wrong, which is the part an error-message change breaks
 * first.</p>
 *
 * <p>Parsing is done through {@link Parser} directly rather than through an engine:
 * these cases are about syntax, and a compile or run step would only add a second
 * reason for the same failure.</p>
 */
class SyntaxTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("accepted")
    @DisplayName("every form the grammar promises parses")
    void accepted(String source) {
        Parser.Program program = Parser.parse(source, "syntax.mnd");
        assertFalse(program.hasErrors(), () -> source + " -> " + program.diagnostics());
    }

    /**
     * One line per construct the language accepts, grouped the way the guide groups
     * them.
     *
     * @return the snippets that must parse
     */
    static Stream<Arguments> accepted() {
        return Stream.of(
                // functions, lambdas and returns
                Arguments.of("fun f() -> Int { return 1 }"),
                Arguments.of("fun f() -> Int {\n  return 1\n}\nf()"),
                Arguments.of("fun f() -> Int {\n  let x = 1\n  return x\n}\nf()"),
                Arguments.of("fun f() {\n  let x = 1\n  x\n}\nf()"),
                Arguments.of("let g = { -> 2 }\ng()"),
                Arguments.of("let g = { x -> x * 2 }\ng(3)"),
                Arguments.of("let l = [{ -> 2 }, { -> 4 }]"),
                // A lambda that is a call's last argument may be written after the
                // parentheses, or in place of them when it is the only one -- Kotlin's
                // shape, and the one a host's own scripts reach for first. The two
                // cases below that must NOT read as a trailing lambda are pinned with
                // the loop and match lines: a block that follows a call is that call's
                // argument only when its header carries a parameter arrow.
                Arguments.of("[1, 2].map { x -> x * 2 }"),
                Arguments.of("[1, 2].map({ x -> x * 2 })"),
                Arguments.of("[1, 2].map { x, i -> \"${i}:${x}\" }.join(\" \")"),
                Arguments.of("fun apply(n: Int, f: Fun) -> Int { f(n) }\napply(3) { x -> x * 10 }"),
                Arguments.of("fun apply(n: Int, f: Fun) -> Int { f(n) }\napply(3) { x ->\n  x * 10\n}"),
                Arguments.of("[1, 2] |> .map { x -> x + 1 }"),
                Arguments.of("let m = {\"a\": 1}\nm.mapValues { v -> v + 1 }"),
                Arguments.of("[3, 1].sortBy { x -> 0 - x }"),
                // A body that happens to hold a lambda is still a body: the arrow is
                // nested, so it is not at the header level of the trailing block.
                Arguments.of("fun items() -> List { [1, 2] }\nvar out = 0\nfor x in items() { out += x }\nout"),
                Arguments.of("fun ready() -> Bool { false }\nvar n = 0\nwhile ready() { break }"),
                // The arm arrow is not a lambda header in the trailing position, so a
                // 'match' whose subject is a call keeps its arms.
                Arguments.of("fun f(x: Int) -> Int { x }\nmatch f(2) { 1 => \"a\", _ => \"b\" }"),
                Arguments.of("[1, 2].map { x -> if x > 1 { x } else { 0 } }"),
                Arguments.of("fun call(a: Int, b: Int = 1) -> Int { a + b }\ncall(1)"),
                Arguments.of("fun name(row: Map) -> Str { row.label }"),
                // control flow
                Arguments.of("if (true) { 1 } else { 2 }"),
                Arguments.of("if true { 1 }"),
                Arguments.of("if 1 == 2 { \"a\" } else if 2 == 2 { \"b\" } else { \"c\" }"),
                Arguments.of("var n = 0\nwhile (n < 5) { n += 1 }\nn"),
                Arguments.of("for i in 1..3 { i }"),
                Arguments.of("for i in 0..8 step 2 { i }"),
                Arguments.of("for i in 8..0 step -1 { i }"),
                Arguments.of("for [k, v] in {\"a\": 1} { k }"),
                Arguments.of("for k, v in {\"a\": 1} { k }"),
                // The header takes parentheses the same way an 'if' does, which is
                // what a Java or Kotlin author types first.
                Arguments.of("for (i in 1..3) { i }"),
                Arguments.of("for (k, v in {\"a\": 1}) { k }"),
                Arguments.of("while (true) { break }"),
                Arguments.of("loop { break }"),
                // strings
                Arguments.of("\"plain\""),
                Arguments.of("\"a=${1}\""),
                Arguments.of("\"a=${1} b=${2}\""),
                Arguments.of("\"a=${1 + 2}\""),
                Arguments.of("\"a=$x\""),
                Arguments.of("\"a${'b'}c\""),
                Arguments.of("\"outer ${\"inner ${1}\"} outer\""),
                Arguments.of("\"\"\"\n  block\n\"\"\""),
                // declarations
                Arguments.of("type P(x: Int)"),
                Arguments.of("type P(x: Int)\nP(1)"),
                Arguments.of("class C(a: Int) { }"),
                Arguments.of("class C(a: Int) {\n  fun get() -> Int { a }\n}\nC(2).get()"),
                Arguments.of("class D(a: Int) extends C { }"),
                Arguments.of("enum E { A, B }"),
                Arguments.of("var v = 0\nv += 1\nv"),
                // modules
                Arguments.of("use std.json"),
                Arguments.of("use std.fs"),
                Arguments.of("import \"lib/util.mnd\""),
                Arguments.of("import \"lib/util.mnd\" as util"),
                Arguments.of("export fun go() -> Int { 1 }"),
                Arguments.of("export let answer = 42"),
                Arguments.of("export type Row(a: Int)"),
                Arguments.of("export class Widget() { }"),
                Arguments.of("export enum Size { S, M }"),
                // expressions
                Arguments.of("[1, 2] + [3]"),
                Arguments.of("let m = {\"k\": 1}\nm[\"k\"]"),
                Arguments.of("1 |> plus(2)"),
                Arguments.of("let s: Str = null\ns?.length"),
                Arguments.of("null ?? \"alt\""),
                Arguments.of("match 3 { 1 => \"a\", _ => \"b\" }"),
                Arguments.of("match v {\n  n if n > 8 => \"high\",\n  _ => \"low\"\n}"),
                Arguments.of("try { 1 } catch e { 2 } finally { 3 }"),
                Arguments.of("try { 1 } catch e: Error { 2 }"),
                Arguments.of("fun f() {\n  defer close()\n}"),
                Arguments.of("let x = 1; let y = 2; y"),
                Arguments.of("let a = 1\nlet b = 2\nb"),
                Arguments.of("2 ** 10 % 3"),
                Arguments.of("let t = if (true) 1 else 2\nt"),
                Arguments.of("x is Int"),
                Arguments.of("x !is Int"),
                Arguments.of("x as Str"),
                Arguments.of("6 in [1, 2]"),
                Arguments.of("6 not in [1, 2]"),
                Arguments.of("\"${6 in r}\""),
                Arguments.of("let [a, b] = pair()\nlet Pair(x, y) = pair()"),
                Arguments.of("let row = {\"a\": 1, \"b\": 2}\nrow.a"),
                Arguments.of("!ready"),
                Arguments.of("not ready"),
                Arguments.of("a and b or c"),
                Arguments.of("- 7"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("refused")
    @DisplayName("a mistake is reported with the words an author needs")
    void refused(String source, String fragment) {
        Parser.Program program = Parser.parse(source, "syntax.mnd");
        assertTrue(program.hasErrors(), () -> source + " parsed, but it should not");
        List<Diagnostic> findings = program.diagnostics();
        assertTrue(containsMentioning(findings, fragment),
                () -> source + " -> " + findings + " (expected a hint of '" + fragment + "')");
    }

    /**
     * Each case is a snippet and the fragment of the message that makes it fixable:
     * the name of what was expected, or the reason the mistake is one.
     *
     * @return the programs that must not parse
     */
    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("1 +", "expected an expression"),
                Arguments.of("let", "expected a pattern"),
                // A declaration that binds nothing cannot be read back, so it is
                // refused even though a bare literal is a legal 'match' pattern.
                Arguments.of("let 1 = 2", "needs a name"),
                Arguments.of("let x is Int = 1", "needs a name"),
                Arguments.of("fun f(", "unterminated parameter list"),
                Arguments.of("if { 1 }", "a condition"),
                Arguments.of("class C {", "expected '}' to close"),
                Arguments.of("export var answer = 1", "export var"),
                Arguments.of("export let [a, b] = pair()", "single name"),
                Arguments.of("export 1", "export"),
                Arguments.of("for i in 0..8 step 2", "expected '{'"),
                Arguments.of("return 1", "return"),
                Arguments.of("break", "loop"),
                Arguments.of("fun f() { defer }", "defer"),
                Arguments.of("throw", "a value to throw"),
                Arguments.of("try { 1 }", "needs a 'catch' or a 'finally'"),
                Arguments.of("import nothing", "quoted module path"),
                // A trailing block with no parameter arrow is not a lambda -- it would
                // be a guess about the callback's arity -- so it stays a mistake rather
                // than silently becoming a map literal or an implicit-parameter form.
                Arguments.of("[1, 2].map { println(1) }", "expected an end of statement"),
                Arguments.of("[1, 2].map { x -> 1", "unterminated lambda"),
                Arguments.of("\"unterminated", null),
                Arguments.of("let reserved = if", null));
    }

    /**
     * @param findings the diagnostics the parser produced
     * @param fragment the words the message must contain, or null for any error
     * @return true when one finding says it
     */
    private static boolean containsMentioning(List<Diagnostic> findings, String fragment) {
        if (fragment == null) {
            return !findings.isEmpty();
        }
        for (Diagnostic finding : findings) {
            if (finding.message().contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
