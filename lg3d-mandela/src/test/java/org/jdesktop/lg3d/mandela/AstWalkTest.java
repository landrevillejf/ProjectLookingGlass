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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jdesktop.lg3d.mandela.lang.Ast;
import org.jdesktop.lg3d.mandela.lang.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tree walker the editor outline and every linting host are built on:
 * {@link Ast.Walk}.
 *
 * <p>The contract pinned here is <em>totality</em>, stated as data rather than as a
 * hand-maintained list of expectations: the node classes a walk reports must equal
 * the classes the sealed interfaces declare in their {@code permits} clause. The
 * walker's switches carry no {@code default} because javac refuses a non-exhaustive
 * one, so grammar and walk cannot drift apart silently; this suite is what proves a
 * node that compiles is also a node a linter can see.</p>
 *
 * <p>One construct per entry rather than one mega-source: a parse failure then names
 * the construct that broke instead of the whole file, and each snippet stays the
 * legal Mandela a guide or a bug report would show.</p>
 */
class AstWalkTest {

    /** Constructs the walker must reach, one program per family. */
    private static final List<String> PROGRAMS = List.of(
            // module forms
            "use std.json",
            "import \"lib/util.mnd\" as util",
            "export let answer = 42",
            "export fun go() -> Int { 1 }",
            // bindings: named, destructured, with and without an initialiser
            "let a = 1",
            "var b",
            "var c = 2\nc = 3\nc += 1",
            "let [x, y] = [1, 2]",
            "let Pair(p) = pair()",
            // functions: expression body (an ExprValue), block body, defaults
            "fun g(p: Int, q: Int = 2) -> Int = p + q",
            "fun h() -> Int { return 1 }",
            "fun s() { return }",
            "fun d() -> Int { defer println(1)\n return 2 }",
            "let f = fun(a: Int, bb: Int = 2) -> Int { a + bb }",
            "let lam = { p -> p * 2 }",
            // declarations that carry children the walk has to descend into
            "type P(x: Int = 1)",
            "type Q(x: Int = 1) { fun get() -> Int { x } }",
            "enum E { A, B }",
            "class Base(a: Int) { fun bump(k: Int) -> Int { k } }",
            "class C(a: Int = 3) extends Base(a + 1) {\n"
                    + "  var n = a\n"
                    + "  init { n += 1 }\n"
                    + "  fun show() -> Int { return n }\n"
                    + "}",
            "class D(a: Int) extends Base(a) {\n"
                    + "  fun read() -> Int {\n"
                    + "    let self = this\n"
                    + "    let up = super\n"
                    + "    return super.bump(self.a)\n"
                    + "  }\n"
                    + "}",
            // control flow, with and without each optional child
            "if true { 1 }",
            "if 1 == 2 { \"a\" } else if 2 == 2 { \"b\" } else { \"c\" }",
            "var t = 0\nwhile t > 9 { break }\nloop { continue }\nt",
            "for i in 1..3 { i }",
            "for [k, v] in {\"a\": 1} { k }",
            "try { 1 } catch e { 2 } finally { 3 }",
            "try { 1 } catch e: Error { 2 }",
            "try { 1 } finally { 2 }",
            "throw \"stop\"",
            "{ let inner = 1\n1 }",
            // operators and literals
            "1 + 2 * 3",
            "- 7",
            "not true",
            "!false",
            "true and false or true",
            "\"a=${1 + 2} b=${3}\"",
            "[1, 2, 3]",
            "let table = {\"a\": 1, \"b\": 2 + 3}",
            "0..9 step 2",
            "1..5",
            "1..<5",
            "x is Int",
            "x !is Int",
            "\"s\" as Str",
            "6 in [1, 2]",
            "6 not in [1, 2]",
            "let either = if (true) 1 else 2\neither",
            // match: literal, guarded and wildcard patterns in one subject
            "match 3 { 1 => \"a\", n if n > 2 => \"high\", _ => \"b\" }",
            // access and call forms, safe and plain
            "let l = [1]\nl[0]",
            "let m = {\"a\": 1}\nm.a",
            "let q: Str = null\nq?.length",
            "println(1, 2)",
            "let xs = [1]\nxs.map { v -> v + 1 }",
            "let o: Str = null\no?.trim()",
            "1 |> plus(2)",
            "fun call(a: Int, b: Int = 1) -> Int { a + b }\ncall(1)");

    @Test
    @DisplayName("the walk reports every statement type the grammar permits")
    void walkIsTotalOverStatements() {
        Set<String> visited = new LinkedHashSet<>();
        for (Ast.Stmt s : parseAll()) {
            new Ast.Walk(new Ast.Visitor() {
                @Override
                public void visitStmt(Ast.Stmt stmt) {
                    visited.add(stmt.getClass().getSimpleName());
                }
            }).stmt(s);
        }
        assertEquals(permits(Ast.Stmt.class), visited,
                "the walker does not report the whole statement vocabulary");
    }

    @Test
    @DisplayName("the walk reports every expression type the grammar permits")
    void walkIsTotalOverExpressions() {
        Set<String> visited = new LinkedHashSet<>();
        for (Ast.Stmt s : parseAll()) {
            new Ast.Walk(new Ast.Visitor() {
                @Override
                public void visitExpr(Ast.Expr expr) {
                    visited.add(expr.getClass().getSimpleName());
                }
            }).stmt(s);
        }
        assertEquals(permits(Ast.Expr.class), visited,
                "the walker does not report the whole expression vocabulary");
    }

    @Test
    @DisplayName("program() visits every top-level statement, in order")
    void programWalksTheTopLevelInOrder() {
        List<String> order = new ArrayList<>();
        new Ast.Walk(new Ast.Visitor() {
            @Override
            public void visitStmt(Ast.Stmt stmt) {
                order.add(stmt.getClass().getSimpleName());
            }
        }).program(parse("let a = 1\nlet b = 2\nprintln(a + b)"));
        assertEquals(List.of("Let", "Let", "Expression"), order);
    }

    @Test
    @DisplayName("optional children are descended into, not skipped")
    void walkDescendsIntoOptionalChildren() {
        // Each pair is the same construct with and without its optional child. The
        // assertions read the literals the walk reached, because the child to prove
        // carries a value that appears nowhere else in the program.
        assertEquals(Set.of("0", "9", "2"), literalsOf("0..9 step 2"),
                "a range's stride must be walked");
        assertEquals(Set.of("1", "5"), literalsOf("1..5"),
                "a range without a stride must not invent one");

        assertTrue(literalsOf("if 1 == 2 { \"a\" } else if 2 == 2 { \"b\" } "
                + "else { \"d\" }").contains("d"),
                "the last else branch of an else-if chain must be walked");
        assertEquals(Set.of("true", "1"), literalsOf("if true { 1 }"),
                "a missing else must not be walked as an empty branch");

        assertTrue(literalsOf("match 3 { n if n > 2 => \"high\", _ => \"b\" }")
                .contains("2"), "a match guard must be walked, not only its body");

        assertTrue(literalsOf("fun d(a: Int, b: Int = 7) -> Int = a + b")
                .contains("7"), "a default parameter must be walked");

        assertTrue(literalsOf("try { 1 } catch e { \"handler\" } "
                + "finally { \"finalizer\" }").containsAll(
                        Set.of("handler", "finalizer")),
                "both optional try clauses must be walked");
    }

    /**
     * The literal texts one program's walk reaches.
     *
     * @param source the program
     * @return the source text of every literal node the walk reported
     */
    private static Set<String> literalsOf(String source) {
        Set<String> texts = new LinkedHashSet<>();
        Ast.Visitor reader = new Ast.Visitor() {
            @Override
            public void visitExpr(Ast.Expr expr) {
                if (expr instanceof Ast.Literal literal) {
                    texts.add(literal.text());
                }
            }
        };
        for (Ast.Stmt s : parse(source)) {
            new Ast.Walk(reader).stmt(s);
        }
        return texts;
    }

    @Test
    @DisplayName("a pattern with a child expression is walked into")
    void walkVisitsPatternChildren() {
        List<String> seen = new ArrayList<>();
        new Ast.Walk(new Ast.Visitor() {
            @Override
            public void visitExpr(Ast.Expr expr) {
                seen.add(expr.getClass().getSimpleName());
            }
        }).stmt(parseOne("match 3 { 1 => \"a\", _ => \"b\" }"));
        // The literal pattern's own literal is a child node, not a name to bind.
        assertTrue(seen.contains("Literal"),
                "a literal pattern must expose its literal to the walker: " + seen);
    }

    @Test
    @DisplayName("a visitor may answer only one side of the contract")
    void visitorDefaultsAreUsable() {
        // The interface's empty defaults exist so a host overrides one hook; a walk
        // with each half-visitor must complete without touching the other hook.
        List<Ast.Stmt> statements = parse("let a = 1 + 2");
        Ast.Visitor exprOnly = new Ast.Visitor() {
            @Override
            public void visitExpr(Ast.Expr expr) {
                // Only expressions are of interest here.
            }
        };
        Ast.Visitor stmtOnly = new Ast.Visitor() {
            @Override
            public void visitStmt(Ast.Stmt stmt) {
                // Only statements are of interest here.
            }
        };
        for (Ast.Stmt s : statements) {
            new Ast.Walk(exprOnly).stmt(s);
            new Ast.Walk(stmtOnly).stmt(s);
        }
        assertEquals(1, statements.size());
    }

    @Test
    @DisplayName("a walker without a visitor is refused at the door")
    void walkRequiresItsVisitor() {
        NullPointerException refusal =
                assertThrows(NullPointerException.class, () -> new Ast.Walk(null));
        assertTrue(refusal.getMessage().contains("visitor"), refusal.getMessage());
    }

    /**
     * Parses every program in {@link PROGRAMS} and returns their statements.
     *
     * @return the top-level statements of the whole corpus
     */
    private static List<Ast.Stmt> parseAll() {
        List<Ast.Stmt> all = new ArrayList<>();
        for (String source : PROGRAMS) {
            all.addAll(parse(source));
        }
        return all;
    }

    private static Ast.Stmt parseOne(String source) {
        List<Ast.Stmt> statements = parse(source);
        assertEquals(1, statements.size(), () -> source + " -> " + statements);
        return statements.get(0);
    }

    /**
     * Parses one program, failing with its diagnostics rather than a half-built tree:
     * a snippet that stopped parsing is a different bug from a node the walk misses,
     * and the suite must not report it as one.
     *
     * @param source the program
     * @return its top-level statements
     */
    private static List<Ast.Stmt> parse(String source) {
        Parser.Program program = Parser.parse(source, "walk.mnd");
        assertFalse(program.hasErrors(), () -> source + " -> " + program.diagnostics());
        return program.statements();
    }

    /**
     * The vocabulary a sealed interface promises, read off the class file.
     *
     * @param sealed the {@code Ast.Expr} or {@code Ast.Stmt} interface
     * @return the simple names of its permitted subtypes
     */
    private static Set<String> permits(Class<?> sealed) {
        Class<?>[] permitted = sealed.getPermittedSubclasses();
        assertTrue(permitted != null, sealed + " is not sealed; the walk contract changed");
        return Arrays.stream(permitted)
                .map(Class::getSimpleName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
