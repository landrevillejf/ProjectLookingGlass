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
package org.jdesktop.lg3d.mandela.lang;

import java.util.List;
import java.util.Objects;

/**
 * The Mandela abstract syntax tree.
 *
 * <p>The tree is a set of immutable {@code record}s in one file, grouped as
 * {@link Expr expressions}, {@link Stmt statements} and {@link Pattern
 * patterns}. Every node carries its 1-based source position, so a compile error
 * or an editor outline can point at the exact token without a side table, and
 * {@link Ast.Walk} gives hosts (the editor's Structure panel, the linter) a
 * visitor that needs no {@code instanceof} chain outside this file.</p>
 *
 * <p>Design points a reader should know before changing the grammar:</p>
 * <ul>
 *   <li><b>Desugaring happens here, not in the VM.</b> The parser turns
 *       {@code a |> f(x)} into {@code f(a, x)} and {@code fun f(x) = expr} into
 *       a function whose body returns {@code expr}, so the compiler only ever
 *       sees one canonical shape. Sugar that changes evaluation order must not
 *       be desugared this way; the pipe's left operand is evaluated exactly
 *       once, which the rewritten call preserves.</li>
 *   <li><b>No expression statements ambiguity.</b> An expression used as a
 *       statement is wrapped in {@link Stmt.Expression}, so the compiler knows
 *       to drop its value.</li>
 *   <li><b>Types are declarations, not annotations.</b> Mandela is dynamically
 *       typed; {@link Stmt.TypeDecl}, {@link Stmt.ClassDecl} and
 *       {@link Stmt.EnumDecl} create runtime values, while {@code : Int} in a
 *       parameter list is an optional, unchecked documentation hint carried by
 *       {@link Param#typeName} for editors and tooling.</li>
 * </ul>
 */
public final class Ast {

    /** Anything in the tree that occupies a source position. */
    public interface Node {

        /** @return the 1-based start line */
        int line();

        /** @return the 1-based start column */
        int column();
    }

    private Ast() {
        // Namespace for the node records.
    }

    // -- parameters ----------------------------------------------------------

    /**
     * One declared parameter or value-type field.
     *
     * @param name     the binding name
     * @param typeName the optional {@code : Type} annotation, or "" (unchecked;
     *                 it exists for editors, documentation and completion)
     * @param defaultExpr the {@code = expr} default, or null when required
     * @param line     the 1-based line
     * @param column   the 1-based column
     */
    public record Param(String name, String typeName, Expr defaultExpr,
                        int line, int column) implements Node {

        /** A required parameter with no annotation. */
        public static Param of(String name, int line, int column) {
            return new Param(name, "", null, line, column);
        }
    }

    // -- expressions ---------------------------------------------------------

    /** An expression: something that produces a value. */
    public sealed interface Expr extends Node
            permits Literal, Interpolated, Name, ListLit, MapLit, RangeLit, Function,
                    Unary, Binary, Test, Contains, Coerce, Conditional, Match, Index, Member,
                    SafeMember, Call, MethodCall, SafeMethodCall, SuperMethod, ThisRef,
                    SuperRef, BlockValue { }

    /**
     * A literal of any primitive kind.
     *
     * @param kind   which payload field is meaningful
     * @param text   the string body / original lexeme (for diagnostics)
     * @param intValue the value when {@code kind} is {@code INT}
     * @param num    the value when {@code kind} is {@code DOUBLE}
     * @param flag   the value when {@code kind} is {@code BOOL}
     */
    public record Literal(LitKind kind, String text, long intValue, double num,
                          boolean flag, int line, int column) implements Expr {

        /** Which payload a {@link Literal} carries. */
        public enum LitKind {
            /** A 64-bit integer in {@code intValue}. */
            INT,
            /** A double in {@code num}. */
            DOUBLE,
            /** A string body in {@code text}. */
            STRING,
            /** {@code true} / {@code false} in {@code flag}. */
            BOOL,
            /** {@code null}. */
            NULL
        }

        /** An integer literal. */
        public static Literal ofInt(long value, int line, int column) {
            return new Literal(LitKind.INT, Long.toString(value), value, 0.0d, false,
                    line, column);
        }

        /** A double literal. */
        public static Literal ofDouble(double value, int line, int column) {
            return new Literal(LitKind.DOUBLE, Double.toString(value), 0L, value, false,
                    line, column);
        }

        /** A string literal. */
        public static Literal ofString(String value, int line, int column) {
            return new Literal(LitKind.STRING, value, 0L, 0.0d, false, line, column);
        }

        /** A boolean literal. */
        public static Literal ofBool(boolean value, int line, int column) {
            return new Literal(LitKind.BOOL, Boolean.toString(value), 0L, 0.0d, value,
                    line, column);
        }

        /** The {@code null} literal. */
        public static Literal ofNull(int line, int column) {
            return new Literal(LitKind.NULL, "null", 0L, 0.0d, false, line, column);
        }
    }

    /**
     * An interpolated string: alternating literal text and embedded expressions,
     * evaluated left to right and concatenated with each value's display form.
     *
     * @param parts  the pieces; {@link Literal} nodes of kind STRING are text,
     *               anything else is an expression
     * @param line   the 1-based line of the opening quote
     * @param column the 1-based column of the opening quote
     */
    public record Interpolated(List<Expr> parts, int line, int column) implements Expr { }

    /**
     * A bare name: a local, an upvalue, a global, a function, a class.
     *
     * @param name   the identifier
     * @param line   the 1-based line
     * @param column the 1-based column
     */
    public record Name(String name, int line, int column) implements Expr { }

    /** A reference to the enclosing instance inside a method. */
    public record ThisRef(int line, int column) implements Expr { }

    /** A reference to the inherited members of the enclosing class. */
    public record SuperRef(int line, int column) implements Expr { }

    /**
     * A list literal, {@code [a, b, c]}.
     *
     * @param items  the element expressions
     * @param line   the 1-based line
     * @param column the 1-based column
     */
    public record ListLit(List<Expr> items, int line, int column) implements Expr { }

    /**
     * A map literal, {@code { "k": v, name: v }}. Keys are evaluated in source
     * order; a duplicate key overwrites the earlier entry, which is what lets a
     * script build a map by merging literals.
     *
     * @param keys   the key expressions
     * @param values the value expressions, parallel to {@code keys}
     * @param line   the 1-based line
     * @param column the 1-based column
     */
    public record MapLit(List<Expr> keys, List<Expr> values, int line, int column)
            implements Expr { }

    /**
     * A range, {@code a..b} (inclusive) or {@code a..<b} (exclusive).
     *
     * @param from      the start expression
     * @param to        the end expression
     * @param exclusive true for {@code ..<}
     * @param stride    the {@code step} expression, or null for a stride of 1
     * @param line      the 1-based line
     * @param column    the 1-based column
     */
    public record RangeLit(Expr from, Expr to, boolean exclusive, Expr stride,
            int line, int column) implements Expr { }

    /**
     * A function or lambda value. A named {@code fun} declaration compiles the
     * same node and binds it to a global; a lambda is this node in expression
     * position.
     *
     * @param name  the declared name, or "" for a lambda
     * @param params the parameters, in declaration order
     * @param body  the body block; a {@code = expr} body arrives as a block whose
     *              single statement is a {@link BlockValue} return marker
     * @param line  the 1-based line
     * @param column the 1-based column
     */
    public record Function(String name, List<Param> params, Stmt.Block body,
                           int line, int column) implements Expr { }

    /**
     * A prefix operator.
     *
     * @param op     one of {@code "-"}, {@code "!"}, {@code "not"}
     * @param operand the operand
     * @param line   the 1-based line
     * @param column the 1-based column
     */
    public record Unary(String op, Expr operand, int line, int column) implements Expr { }

    /**
     * An infix operator.
     *
     * @param op the operator text: {@code + - * / % ** == != < <= > >= && || ?? and or}
     * @param left  the left operand
     * @param right the right operand
     * @param line  the 1-based line
     * @param column the 1-based column
     */
    public record Binary(String op, Expr left, Expr right, int line, int column)
            implements Expr { }

    /**
     * An {@code is} type test, {@code x is Int}.
     *
     * @param target   the tested expression
     * @param typeName the type name ({@code Int}, {@code Num}, {@code Str},
     *                 {@code Bool}, {@code List}, {@code Map}, {@code Null},
     *                 {@code Range}, {@code Fun}, {@code Err}, or a declared
     *                 class / type / enum name)
     * @param negated  true for {@code !is}
     * @param line     the 1-based line
     * @param column   the 1-based column
     */
    public record Test(Expr target, String typeName, boolean negated, int line, int column)
            implements Expr { }

    /**
     * A membership test, {@code value in container} (and its {@code not in} form).
     *
     * <p>{@code in} is a reserved word already, so the clause needs no new token
     * and no precedence level of its own: it sits with the comparisons, which is
     * where a reader expects an answer of {@code Bool}.</p>
     *
     * @param value     the candidate
     * @param container the List, Map, Range or Str searched
     * @param negated   true for {@code not in}
     * @param line      the 1-based line
     * @param column    the 1-based column
     */
    public record Contains(Expr value, Expr container, boolean negated,
            int line, int column) implements Expr { }

    /**
     * A runtime conversion, {@code x as Int}.
     *
     * @param target   the value to convert
     * @param typeName the target type name
     * @param line     the 1-based line
     * @param column   the 1-based column
     */
    public record Coerce(Expr target, String typeName, int line, int column) implements Expr { }

    /**
     * A conditional expression written with {@code if}/{@code else} in expression
     * position, as in {@code if (ready) "go" else "wait"}.
     *
     * <p>There is deliberately no {@code c ? a : b} form: with two meanings for
     * {@code :} in the grammar (map literals and named arguments) the colon form is
     * harder to read in a language that also has {@code ${}} interpolation.</p>
     *
     * @param condition the guard
     * @param thenValue the value when true
     * @param elseValue the value when false
     */
    public record Conditional(Expr condition, Expr thenValue, Expr elseValue,
                              int line, int column) implements Expr { }

    /**
     * A {@code match} expression: the first matching arm's body is the value.
     *
     * @param subject the scrutinee
     * @param arms    the arms, tried in order
     */
    public record Match(Expr subject, List<Arm> arms, int line, int column) implements Expr { }

    /**
     * One {@code match} arm.
     *
     * @param pattern the pattern to test
     * @param guard   an optional {@code if} predicate, evaluated only after the
     *                pattern matched
     * @param body    the arm's value expression
     */
    public record Arm(Pattern pattern, Expr guard, Expr body) { }

    /** An indexing expression, {@code target[index]}. */
    public record Index(Expr target, Expr index, int line, int column) implements Expr { }

    /** A property access, {@code target.name}. */
    public record Member(Expr target, String name, int line, int column) implements Expr { }

    /** A null-safe property access, {@code target?.name}. */
    public record SafeMember(Expr target, String name, int line, int column) implements Expr { }

    /**
     * A call, {@code callee(args)}. When the callee names a {@code class}, a
     * {@code type} or an {@code enum}, arguments may be given by name
     * ({@code Point(x: 1, y: 2)}); {@code argNames} then carries one entry per
     * argument, {@code ""} for a positional one.
     *
     * @param callee   the expression producing the callable value
     * @param args     the argument expressions, in order
     * @param argNames the parallel name list (empty when every argument is
     *                 positional), or {@code null} for none
     */
    public record Call(Expr callee, List<Expr> args, List<String> argNames,
                       int line, int column) implements Expr { }

    /** A method call, {@code target.name(args)}. */
    public record MethodCall(Expr target, String name, List<Expr> args,
                             int line, int column) implements Expr { }

    /** A null-safe method call, {@code target?.name(args)}. */
    public record SafeMethodCall(Expr target, String name, List<Expr> args,
                                 int line, int column) implements Expr { }

    /** A super-dispatched method call, {@code super.name(args)}. */
    public record SuperMethod(String name, List<Expr> args, int line, int column)
            implements Expr { }

    /**
     * A block used as an expression value (the tail value of a
     * {@code fun f() = { ... }} body or a lambda).
     *
     * @param block the block whose last expression value is the result
     */
    public record BlockValue(Stmt.Block block, int line, int column) implements Expr { }

    // -- patterns ------------------------------------------------------------

    /** A {@code match} arm pattern or a destructuring {@code let} target. */
    public sealed interface Pattern extends Node
            permits Pattern.Named, Pattern.Any, Pattern.LiteralOf, Pattern.TypeOf,
                    Pattern.Destructure {

        /** Binds the whole value to a name. */
        record Named(String name, int line, int column) implements Pattern { }

        /** {@code _}: matches anything and binds nothing. */
        record Any(int line, int column) implements Pattern { }

        /** Matches a value equal to a literal. */
        record LiteralOf(Expr literal, int line, int column) implements Pattern { }

        /**
         * Matches a type, optionally binding the value.
         *
         * @param typeName the tested type name
         * @param bind     the name to bind, or null when the value is unused
         */
        record TypeOf(String typeName, String bind, int line, int column) implements Pattern { }

        /**
         * Matches a list, a map or an instance and binds its parts.
         *
         * @param typeName the declared type to match, or "" for a positional list
         * @param names    the binding names, in order; a {@code "_"} entry skips a part
         */
        record Destructure(String typeName, List<String> names, int line, int column)
                implements Pattern { }
    }

    // -- statements ----------------------------------------------------------

    /** A statement: something that is executed for its effect. */
    public sealed interface Stmt extends Node
            permits Stmt.Expression, Stmt.Let, Stmt.Fun, Stmt.TypeDecl, Stmt.ClassDecl,
                    Stmt.EnumDecl, Stmt.If, Stmt.While, Stmt.For, Stmt.Loop,
                    Stmt.Assign, Stmt.CompoundAssign, Stmt.ExprValue, Stmt.Block,
                    Stmt.Return, Stmt.Throw, Stmt.Try, Stmt.Defer, Stmt.Break,
                    Stmt.Continue, Stmt.Import, Stmt.Use {

        /** An expression evaluated for its side effects; its value is dropped. */
        record Expression(Expr value, int line, int column) implements Stmt { }

        /**
         * A declaration whose value is kept as the statement's result inside a
         * function body (used by the implicit-return rule).
         *
         * @param value the expression
         */
        record ExprValue(Expr value, int line, int column) implements Stmt { }

        /**
         * A {@code let} (immutable) or {@code var} (mutable) binding.
         *
         * @param pattern the target (a name or a destructuring form)
         * @param typeName the optional annotation, or ""
         * @param value   the initialiser; may be null, which binds {@code null}
         * @param mutable true for {@code var}
         * @param exported true for {@code export let}, which puts the name on the
         *                 module's public surface
         */
        record Let(Pattern pattern, String typeName, Expr value, boolean mutable,
                   boolean exported, int line, int column) implements Stmt { }

        /**
         * A function declaration.
         *
         * @param function the same node the expression form uses (its
         *                 {@link Function#name()} is non-empty here)
         * @param exported true when prefixed with {@code export}
         */
        record Fun(Function function, boolean exported, int line, int column) implements Stmt { }

        /**
         * A {@code type} declaration: an immutable, equatable record with named
         * fields, optional defaults and optional methods.
         *
         * @param name     the type name
         * @param fields   the fields, in declaration order; {@link Param#defaultExpr}
         *                 supplies a default
         * @param methods  the declared methods
         * @param exported true when prefixed with {@code export}
         */
        record TypeDecl(String name, List<Param> fields, List<Fun> methods,
                        boolean exported, int line, int column) implements Stmt { }

        /**
         * A {@code class} declaration with a primary constructor, mutable state,
         * methods and optional inheritance.
         *
         * @param name       the class name
         * @param params     the primary-constructor parameters
         * @param parent     the {@code extends} base name, or ""
         * @param parentArgs the arguments forwarded to the base constructor
         * @param fields     the field declarations (evaluated after the base runs)
         * @param methods    the declared methods
         * @param initializers the {@code init { ... }} blocks, in order
         * @param exported   true when prefixed with {@code export}
         */
        record ClassDecl(String name, List<Param> params, String parent,
                        List<Expr> parentArgs, List<Stmt.Let> fields, List<Fun> methods,
                        List<Block> initializers, boolean exported,
                        int line, int column) implements Stmt { }

        /**
         * An {@code enum} declaration: a fixed set of named, equatable values.
         *
         * @param name      the enum name
         * @param constants the constant names, in order (their {@code ordinal}
         *                  is the index)
         * @param exported  true when prefixed with {@code export}
         */
        record EnumDecl(String name, List<String> constants, boolean exported,
                        int line, int column) implements Stmt { }

        /**
         * {@code if} / {@code else if} / {@code else}.
         *
         * @param condition the guard
         * @param thenBlock the taken branch
         * @param elseBlock the alternative, or null; an {@code else if} arrives as
         *                  a block containing a single {@link If}
         */
        record If(Expr condition, Block thenBlock, Stmt elseBlock,
                  int line, int column) implements Stmt { }

        /** {@code while (cond) body}. */
        record While(Expr condition, Block body, int line, int column) implements Stmt { }

        /**
         * {@code for (pattern in iterable) body}, over a list, map, range, string
         * or any value that supports {@code iterate}.
         *
         * @param pattern  the loop variable pattern (a name or destructuring form)
         * @param iterable the expression producing the sequence
         */
        record For(Pattern pattern, Expr iterable, Block body, int line, int column)
                implements Stmt { }

        /** {@code loop { ... }} with {@code break} / {@code continue}. */
        record Loop(Block body, int line, int column) implements Stmt { }

        /**
         * An assignment to an existing binding, a field or an element.
         *
         * @param target one of {@link Name}, {@link Member}, {@link Index}
         * @param value  the right-hand side
         */
        record Assign(Expr target, Expr value, int line, int column) implements Stmt { }

        /**
         * A compound assignment, {@code target op= value}.
         *
         * @param target the place to update
         * @param op     the binary operator text
         */
        record CompoundAssign(Expr target, String op, Expr value,
                              int line, int column) implements Stmt { }

        /** {@code return} with an optional value. */
        record Return(Expr value, int line, int column) implements Stmt { }

        /** {@code throw expr}. */
        record Throw(Expr value, int line, int column) implements Stmt { }

        /**
         * {@code try / catch / finally}.
         *
         * @param body      the guarded block
         * @param errorName the catch binding name, or null when there is no catch
         * @param errorType an optional {@code : Type} filter on the catch, or ""
         * @param handler   the catch block
         * @param finalizer the {@code finally} block, or null
         */
        record Try(Block body, String errorName, String errorType, Block handler,
                   Block finalizer, int line, int column) implements Stmt { }

        /** {@code defer stmt}: run after the enclosing function body finishes. */
        record Defer(Stmt statement, int line, int column) implements Stmt { }

        /** {@code break} inside the nearest enclosing loop. */
        record Break(int line, int column) implements Stmt { }

        /** {@code continue} inside the nearest enclosing loop. */
        record Continue(int line, int column) implements Stmt { }

        /**
         * {@code import "path"}: load another Mandela source as a module and bind
         * its exported names under the module alias.
         *
         * @param path the literal path, resolved by the host's module loader
         * @param alias the binding name, or "" (derived from the path)
         */
        record Import(String path, String alias, int line, int column) implements Stmt { }

        /**
         * {@code use std.json}: bring a standard-library module into scope.
         *
         * @param segments the dotted module path, at least one segment
         */
        record Use(List<String> segments, int line, int column) implements Stmt { }

        /** A brace-delimited sequence of statements; every block introduces its own scope. */
        record Block(List<Stmt> statements, int line, int column) implements Stmt { }
    }

    // -- walking -------------------------------------------------------------

    /**
     * A read-only visitor over the tree. Hosts that need the whole tree (an
     * outline, a linter) implement the hooks they care about; every hook has an
     * empty default.
     */
    public interface Visitor {

        /** Called for every expression node, in preorder. */
        default void visitExpr(Expr expr) { }

        /** Called for every statement node, in preorder. */
        default void visitStmt(Stmt stmt) { }
    }

    /**
     * Depth-first traversal that visits every node exactly once.
     *
     * <p>The walk is total over the node types in this file; when the grammar
     * grows a node, add its children here so the outline and the linter keep
     * seeing the whole program.</p>
     */
    public static final class Walk {

        private final Visitor visitor;

        /** Creates a walk that reports to {@code visitor}. */
        public Walk(Visitor visitor) {
            this.visitor = Objects.requireNonNull(visitor, "visitor");
        }

        /** Visits every node of the program. */
        public void program(List<Stmt> statements) {
            for (Stmt s : statements) {
                stmt(s);
            }
        }

        /** Visits a statement subtree. */
        public void stmt(Stmt s) {
            visitor.visitStmt(s);
            switch (s) {
                case Stmt.Expression e -> expr(e.value());
                case Stmt.ExprValue e -> expr(e.value());
                case Stmt.Let l -> {
                    pattern(l.pattern());
                    if (l.value() != null) {
                        expr(l.value());
                    }
                }
                case Stmt.Fun f -> {
                    for (Expr d : defaults(f.function().params())) {
                        expr(d);
                    }
                    block(f.function().body());
                }
                case Stmt.TypeDecl t -> {
                    for (Expr d : defaults(t.fields())) {
                        expr(d);
                    }
                    for (Stmt.Fun m : t.methods()) {
                        stmt(m);
                    }
                }
                case Stmt.ClassDecl c -> {
                    for (Expr d : defaults(c.params())) {
                        expr(d);
                    }
                    for (Expr a : c.parentArgs()) {
                        expr(a);
                    }
                    for (Stmt.Let f : c.fields()) {
                        stmt(f);
                    }
                    for (Stmt.Fun m : c.methods()) {
                        stmt(m);
                    }
                    for (Stmt.Block b : c.initializers()) {
                        block(b);
                    }
                }
                case Stmt.EnumDecl ignored -> {
                    // Constants carry no sub-expressions.
                }
                case Stmt.If i -> {
                    expr(i.condition());
                    block(i.thenBlock());
                    if (i.elseBlock() != null) {
                        stmt(i.elseBlock());
                    }
                }
                case Stmt.While w -> {
                    expr(w.condition());
                    block(w.body());
                }
                case Stmt.For f -> {
                    pattern(f.pattern());
                    expr(f.iterable());
                    block(f.body());
                }
                case Stmt.Loop l -> block(l.body());
                case Stmt.Assign a -> {
                    expr(a.target());
                    expr(a.value());
                }
                case Stmt.CompoundAssign a -> {
                    expr(a.target());
                    expr(a.value());
                }
                case Stmt.Return r -> {
                    if (r.value() != null) {
                        expr(r.value());
                    }
                }
                case Stmt.Throw t -> expr(t.value());
                case Stmt.Try t -> {
                    block(t.body());
                    if (t.handler() != null) {
                        block(t.handler());
                    }
                    if (t.finalizer() != null) {
                        block(t.finalizer());
                    }
                }
                case Stmt.Defer d -> stmt(d.statement());
                case Stmt.Break ignored -> {
                    // Leaf.
                }
                case Stmt.Continue ignored -> {
                    // Leaf.
                }
                case Stmt.Import ignored -> {
                    // Path is a literal, already validated by the parser.
                }
                case Stmt.Use ignored -> {
                    // Segments are names.
                }
                case Stmt.Block b -> block(b);
                default -> throw new IllegalStateException("unwalked statement " + s);
            }
        }

        /** Visits an expression subtree. */
        public void expr(Expr e) {
            visitor.visitExpr(e);
            switch (e) {
                case Literal ignored -> {
                    // Leaf.
                }
                case Interpolated i -> {
                    for (Expr p : i.parts()) {
                        expr(p);
                    }
                }
                case Name ignored -> {
                    // Leaf.
                }
                case ThisRef ignored -> {
                    // Leaf.
                }
                case SuperRef ignored -> {
                    // Leaf.
                }
                case ListLit l -> {
                    for (Expr item : l.items()) {
                        expr(item);
                    }
                }
                case MapLit m -> {
                    for (Expr k : m.keys()) {
                        expr(k);
                    }
                    for (Expr v : m.values()) {
                        expr(v);
                    }
                }
                case RangeLit r -> {
                    expr(r.from());
                    expr(r.to());
                    if (r.stride() != null) {
                        expr(r.stride());
                    }
                }
                case Function f -> {
                    for (Expr d : defaults(f.params())) {
                        expr(d);
                    }
                    block(f.body());
                }
                case Unary u -> expr(u.operand());
                case Binary b -> {
                    expr(b.left());
                    expr(b.right());
                }
                case Test t -> expr(t.target());
                case Contains c -> {
                    expr(c.value());
                    expr(c.container());
                }
                case Coerce c -> expr(c.target());
                case Conditional c -> {
                    expr(c.condition());
                    expr(c.thenValue());
                    expr(c.elseValue());
                }
                case Match mt -> {
                    expr(mt.subject());
                    for (Arm arm : mt.arms()) {
                        pattern(arm.pattern());
                        if (arm.guard() != null) {
                            expr(arm.guard());
                        }
                        expr(arm.body());
                    }
                }
                case Index i -> {
                    expr(i.target());
                    expr(i.index());
                }
                case Member m -> expr(m.target());
                case SafeMember m -> expr(m.target());
                case Call c -> {
                    expr(c.callee());
                    for (Expr a : c.args()) {
                        expr(a);
                    }
                }
                case MethodCall c -> {
                    expr(c.target());
                    for (Expr a : c.args()) {
                        expr(a);
                    }
                }
                case SafeMethodCall c -> {
                    expr(c.target());
                    for (Expr a : c.args()) {
                        expr(a);
                    }
                }
                case SuperMethod c -> {
                    for (Expr a : c.args()) {
                        expr(a);
                    }
                }
                case BlockValue b -> block(b.block());
                default -> throw new IllegalStateException("unwalked expression " + e);
            }
        }

        /** Visits a block's statements. */
        public void block(Stmt.Block b) {
            for (Stmt s : b.statements()) {
                stmt(s);
            }
        }

        /** Visits a pattern (only destructuring patterns have children). */
        public void pattern(Pattern p) {
            if (p instanceof Pattern.LiteralOf lit) {
                expr(lit.literal());
            }
        }

        private static List<Expr> defaults(List<Param> params) {
            return params.stream().map(Param::defaultExpr).filter(Objects::nonNull).toList();
        }
    }
}
