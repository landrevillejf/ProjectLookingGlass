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
package org.jdesktop.lg3d.mandela.code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdesktop.lg3d.mandela.lang.Ast;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.Keywords;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.values.MandelaClass;

/**
 * The Mandela compiler: a parsed statement list in, one {@link CompiledFunction}
 * of bytecode out.
 *
 * <p>One pass, single traversal, no intermediate IR. The grammar is simple enough
 * that each construct maps onto a fixed instruction shape, and skipping an IR
 * keeps the whole tool chain &mdash; lexer, parser, compiler, machine &mdash;
 * small enough to read in an afternoon, which is the point of a desktop scripting
 * language.</p>
 *
 * <p>The few things it does that are worth knowing before editing:</p>
 * <ul>
 *   <li><b>Top-level bindings are globals.</b> A script's {@code let} is a global
 *       name, not a slot, so the REPL can build a session one statement at a time
 *       and an {@code import}ed module's names stay reachable.</li>
 *   <li><b>Tail expressions return.</b> A function's last statement, when it is an
 *       expression, is the function's value. Statements therefore compile with a
 *       {@code keepValue} flag, and every construct guarantees the stack holds
 *       exactly one value when it says it does.</li>
 *   <li><b>Captured locals are boxed eagerly.</b> The first closure that reads a
 *       local marks its slot as holding a cell, and the already-emitted
 *       load/store instructions are rewritten (see
 *       {@link CompiledFunction.Builder#boxSlot(int)}). There is no
 *       close-upvalue pass and no scope exit to run.</li>
 *   <li><b>{@code finally} is duplicated, not threaded.</b> The finalizer is
 *       emitted on each path that leaves the {@code try} &mdash; normal end,
 *       caught error, filter mismatch, and every {@code return} / {@code break} /
 *       {@code continue} written inside it &mdash; so it runs exactly once per
 *       path without a {@code JSR} or a second handler stack.</li>
 *   <li><b>Classes compile to a constructor.</b> Field initialisers, {@code init}
 *       blocks and the {@code extends} call are sequenced into one synthesised
 *       {@code init} body whose slot 0 is {@code this}, which is what the machine
 *       calls after it allocates the instance.</li>
 *   <li><b>Inside a class, a bare name may be a field or a method.</b> The
 *       compiler knows the enclosing declaration's field and method names, so
 *       {@code count += 1} and {@code redraw()} lower to {@code this} member
 *       operations at compile time. Nothing is resolved by name at run time, so
 *       there is no hidden-lookup tax and no ambiguity with globals.</li>
 * </ul>
 *
 * <p>Errors are thrown as {@link LangException} carrying the offending node's
 * position, so {@code mandela check} and the editor's Problems panel get the same
 * coordinates the parser reports.</p>
 */
public final class Compiler {

    /** A compiled program: the body to run, the names it exports, the findings. */
    public record Result(CompiledFunction main, List<String> exports,
                         List<Diagnostic> diagnostics) { }

    /** One enclosing loop, holding the jumps that must be patched when it ends. */
    private static final class LoopCtx {
        final List<Integer> breaks = new ArrayList<>();
        final List<Integer> continues = new ArrayList<>();
        final int tryDepth;

        LoopCtx(int tryDepth) {
            this.tryDepth = tryDepth;
        }
    }

    /** One enclosing {@code try}, holding the finalizer a jump out must replay. */
    private static final class TryCtx {
        final Ast.Stmt.Block finalizer;
        final int tryDepth;

        TryCtx(Ast.Stmt.Block finalizer, int tryDepth) {
            this.finalizer = finalizer;
            this.tryDepth = tryDepth;
        }
    }

    /**
     * One enclosing class or {@code type}, for bare field / method names.
     *
     * <p>A subclass sees its base's members too, so a bare {@code total} inside
     * {@code class Meter() extends Counter} reads the inherited field the way it
     * does in Kotlin. The inherited names are held separately from the declared
     * ones because resolution must try this class's own members first: an
     * overriding method or a redeclared field shadows the base, and
     * {@link #requireField} must keep rejecting a duplicate only within one class.
     */
    private static final class ClassCtx {
        final Set<String> fields = new LinkedHashSet<>();
        final Set<String> methods = new LinkedHashSet<>();
        final Set<String> inheritedFields = new LinkedHashSet<>();
        final Set<String> inheritedMethods = new LinkedHashSet<>();
    }

    /**
     * Where a name lives. {@link #FIELD} is the in-class case: the value is a
     * member of {@code this} rather than a variable.
     */
    private static final int R_LOCAL = 0;
    private static final int R_BOX = 1;
    private static final int R_UPVALUE = 2;
    private static final int R_GLOBAL = 3;
    private static final int R_FIELD = 4;

    /**
     * A resolved read or write target.
     *
     * @param kind      one of the {@code R_} constants
     * @param index     the frame slot or upvalue index, -1 for name-kinded targets
     * @param name      the source name
     * @param immutable false for {@code var} and for parameters
     */
    private record Slot(int kind, int index, String name, boolean immutable) { }

    private final String sourceName;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<String> globals = new HashSet<>();
    private final Map<String, Boolean> globalMutable = new LinkedHashMap<>();
    private final List<String> exports = new ArrayList<>();
    private final Deque<LoopCtx> loops = new ArrayDeque<>();
    private final Deque<TryCtx> trys = new ArrayDeque<>();
    private final Deque<ClassCtx> classes = new ArrayDeque<>();
    /**
     * Types this compile has already seen, so a subclass can read its base's shape
     * at compile time. Host-registered classes are absent here, which is why an
     * inherited name from a base the compiler cannot see still resolves as a
     * global or reports {@code unknown name} rather than guessing.
     */
    private final Map<String, ClassDef> declaredTypes = new LinkedHashMap<>();

    private Scope scope;
    /** Set while compiling a method body: its locals are the outermost legal ones. */
    private Scope methodBarrier;
    /** The scope of the function compiled most recently, to emit its captures. */
    private Scope lastFunctionScope;

    private Compiler(String sourceName, Set<String> knownGlobals) {
        this.sourceName = (sourceName == null) ? "<script>" : sourceName;
        if (knownGlobals != null) {
            this.globals.addAll(knownGlobals);
        }
    }

    // -- entry points --------------------------------------------------------

    /**
     * Compiles a program, throwing on the first error.
     *
     * @param program    the parsed statements
     * @param sourceName the logical script name
     * @return the script body
     */
    public static CompiledFunction compile(List<Ast.Stmt> program, String sourceName) {
        return compile(program, sourceName, Set.of());
    }

    /**
     * Compiles a program with the host's global names pre-declared.
     *
     * @param program      the parsed statements
     * @param sourceName   the logical script name
     * @param knownGlobals names the host contributes, which the compiler must
     *                     accept without a declaration
     * @return the script body
     */
    public static CompiledFunction compile(List<Ast.Stmt> program, String sourceName,
                                           Set<String> knownGlobals) {
        Compiler c = new Compiler(sourceName, knownGlobals);
        return c.script(program);
    }

    /**
     * Compiles a program as a module and reports the names its {@code export}
     * declarations publish.
     *
     * @param program      the parsed statements
     * @param sourceName   the logical script name
     * @param knownGlobals names the host contributes
     * @return the body and its export list
     */
    public static Result compileModule(List<Ast.Stmt> program, String sourceName,
                                       Set<String> knownGlobals) {
        Compiler c = new Compiler(sourceName, knownGlobals);
        CompiledFunction main = c.script(program);
        return new Result(main, List.copyOf(c.exports), List.copyOf(c.diagnostics));
    }

    /**
     * Compiles a program and returns its findings instead of failing on them, the
     * mode the editor's Problems panel and {@code mandela check} use.
     *
     * @param program      the parsed statements
     * @param sourceName   the logical script name
     * @param knownGlobals names the host contributes
     * @return the body (null when a hard error stopped the compile) and the findings
     */
    public static Result compileLenient(List<Ast.Stmt> program, String sourceName,
                                        Set<String> knownGlobals) {
        Compiler c = new Compiler(sourceName, knownGlobals);
        CompiledFunction main = null;
        try {
            main = c.script(program);
        } catch (LangException e) {
            c.diagnostics.add(e.toDiagnostic());
        }
        return new Result(main, List.copyOf(c.exports), List.copyOf(c.diagnostics));
    }

    private CompiledFunction script(List<Ast.Stmt> program) {
        scope = new Scope(null, "<script>", sourceName, 1, false);
        for (int i = 0; i < program.size(); i++) {
            statement(program.get(i), i == program.size() - 1);
        }
        // The last statement was compiled in keep-value mode, so the operand stack
        // already holds what the script evaluates to; pushing null on top of it
        // would hand the host the null instead of the value.
        if (program.isEmpty()) {
            pushNull(1);
        }
        emit(Op.RETURN, lastLine(program));
        return scope.finish(scope.slotsNeeded());
    }

    private static int lastLine(List<Ast.Stmt> program) {
        return program.isEmpty() ? 1 : program.get(program.size() - 1).line();
    }

    // -- emission helpers ----------------------------------------------------

    private void emit(Op op, int arg, int line) {
        scope.builder.emit(op, arg, line);
    }

    private void emit(Op op, int line) {
        scope.builder.emit(op, 0, line);
    }

    private int constant(Object value) {
        return scope.builder.constant(value);
    }

    private LangException fail(String message, int line, int column) {
        return new LangException(sourceName, line, column, message);
    }

    /**
     * Fails with the check's own identifier, so a host can tell a name that is not
     * declared yet &mdash; routine while a buffer is being typed &mdash; from a
     * program the grammar cannot read at all.
     */
    private LangException fail(String rule, String message, int line, int column) {
        return new LangException(sourceName, line, column, line, column, message, rule);
    }

    private void warn(String rule, String message, int line, int column) {
        diagnostics.add(Diagnostic.of(sourceName, line, column,
                Diagnostic.Severity.WARNING, rule, message));
    }

    /** Leaves the null value, for a statement used where a value is expected. */
    private void pushNull(int line) {
        emit(Op.LOAD_CONST, constant(null), line);
    }

    // -- statements ----------------------------------------------------------

    private void statement(Ast.Stmt stmt, boolean keepValue) {
        switch (stmt) {
            case Ast.Stmt.Expression e -> {
                expr(e.value());
                if (!keepValue) {
                    emit(Op.POP, e.line());
                }
            }
            case Ast.Stmt.ExprValue e -> expr(e.value());
            case Ast.Stmt.Let l -> declaration(l, keepValue);
            case Ast.Stmt.Fun f -> functionDeclaration(f, keepValue);
            case Ast.Stmt.TypeDecl t -> typeDeclaration(t, keepValue);
            case Ast.Stmt.ClassDecl c -> classDeclaration(c, keepValue);
            case Ast.Stmt.EnumDecl e -> enumDeclaration(e, keepValue);
            case Ast.Stmt.Block b -> block(b, keepValue);
            case Ast.Stmt.If i -> ifStatement(i, keepValue);
            case Ast.Stmt.While w -> { whileStatement(w); if (keepValue) pushNull(w.line()); }
            case Ast.Stmt.For f -> { forStatement(f); if (keepValue) pushNull(f.line()); }
            case Ast.Stmt.Loop l -> { loopStatement(l); if (keepValue) pushNull(l.line()); }
            case Ast.Stmt.Assign a -> assign(a.target(), a.value(), null, a, false, keepValue);
            case Ast.Stmt.CompoundAssign a ->
                    assign(a.target(), a.value(), a.op(), a, true, keepValue);
            case Ast.Stmt.Return r -> returnStatement(r);
            case Ast.Stmt.Throw t -> {
                expr(t.value());
                emit(Op.THROW, t.line());
                if (keepValue) {
                    pushNull(t.line());
                }
            }
            case Ast.Stmt.Try t -> tryStatement(t, keepValue);
            case Ast.Stmt.Defer d -> { deferStatement(d); if (keepValue) pushNull(d.line()); }
            case Ast.Stmt.Break b -> { jump(b.line(), true); if (keepValue) pushNull(b.line()); }
            case Ast.Stmt.Continue c -> {
                jump(c.line(), false);
                if (keepValue) {
                    pushNull(c.line());
                }
            }
            case Ast.Stmt.Import i -> importStatement(i, keepValue);
            case Ast.Stmt.Use u -> { useStatement(u); if (keepValue) pushNull(u.line()); }
        }
    }

    /** Compiles a statement list into the current scope. */
    private void block(Ast.Stmt.Block block, boolean keepValue) {
        int mark = scope.beginBlock();
        List<Ast.Stmt> body = block.statements();
        if (body.isEmpty()) {
            if (keepValue) {
                pushNull(block.line());
            }
        } else {
            for (int i = 0; i < body.size(); i++) {
                statement(body.get(i), keepValue && i == body.size() - 1);
            }
        }
        scope.endBlock(mark);
    }

    private void declaration(Ast.Stmt.Let let, boolean keepValue) {
        if (let.value() == null) {
            pushNull(let.line());
        } else {
            expr(let.value());
        }
        bind(let.pattern(), let.mutable(), let.line(), let.column());
        if (let.exported()) {
            // The parser has already insisted on a single name, so the only shape
            // reaching here is `export let name = ...`.
            exports.add(((Ast.Pattern.Named) let.pattern()).name());
        }
        if (keepValue) {
            pushNull(let.line());
        }
    }

    /**
     * Binds the value on top of the stack to a declaration pattern.
     *
     * @param pattern the target
     * @param mutable whether the binding may be reassigned
     * @param line    the statement line, for diagnostics
     * @param column  the statement column
     */
    private void bind(Ast.Pattern pattern, boolean mutable, int line, int column) {
        switch (pattern) {
            case Ast.Pattern.Named n -> bindName(n.name(), line, n.line(), mutable);
            case Ast.Pattern.Any ignored -> emit(Op.POP, line);
            case Ast.Pattern.LiteralOf ignored -> {
                emit(Op.POP, line);
                throw fail("a declaration target must be a name or a destructuring"
                        + " pattern; a literal only makes sense in 'match'", line, column);
            }
            case Ast.Pattern.TypeOf ignored -> {
                emit(Op.POP, line);
                throw fail("a declaration target cannot test a type; write"
                        + " 'let v = value as Type' or use 'match'", line, column);
            }
            case Ast.Pattern.Destructure d -> bindDestructure(d, mutable, line, column);
        }
    }

    /** Binds the stacked value to one name, as a global at the top level. */
    private void bindName(String name, int line, int nameLine, boolean mutable) {
        if (scope.parent == null) {
            declareGlobal(name, mutable, line);
            emit(Op.DEFINE_GLOBAL, constant(name), line);
            return;
        }
        int slot = scope.declare(name, mutable, false);
        emit(Op.SET_LOCAL, slot, nameLine);
    }

    private void declareGlobal(String name, boolean mutable, int line) {
        Boolean previous = globalMutable.get(name);
        if (previous != null && previous && !mutable) {
            throw fail("'" + name + "' was declared with 'var' and cannot be"
                    + " re-declared with 'let' in the same script", line, 1);
        }
        globalMutable.put(name, mutable);
        globals.add(name);
    }

    /**
     * Binds a {@code [a, b]} or {@code Point(x, y)} target to the stacked value.
     *
     * <p>Both forms test, bind on success and reload the value to raise a
     * {@code MatchError} on failure. The success path has to jump over that failure
     * path; without the jump a matching value would bind correctly and then fall
     * straight into the error.</p>
     */
    private void bindDestructure(Ast.Pattern.Destructure d, boolean mutable,
                                 int line, int column) {
        List<String> names = d.names();
        int mark = scope.beginBlock();
        int slot = scope.allocateTemp(1);
        emit(Op.SET_LOCAL, slot, line);
        if (d.typeName().isEmpty()) {
            emit(Op.GET_LOCAL, slot, line);
            emit(Op.MATCH_LIST, names.size(), line);
            int bail = scope.builder.position();
            emit(Op.JMP_IF_FALSE, 0, line);
            for (int i = 0; i < names.size(); i++) {
                emit(Op.GET_LOCAL, slot, line);
                emit(Op.LOAD_CONST, constant((long) i), line);
                emit(Op.GET_INDEX, line);
                bindEntry(names.get(i), d, mutable);
            }
            emitNoMatch(slot, bail, line);
            scope.endBlock(mark);
            return;
        }
        emit(Op.GET_LOCAL, slot, line);
        emit(Op.IS_TYPE, constant(d.typeName()), line);
        int bail = scope.builder.position();
        emit(Op.JMP_IF_FALSE, 0, line);
        for (int i = 0; i < names.size(); i++) {
            // The pattern's names are the new bindings; the parts come out in the
            // order the type declared its fields.
            emit(Op.GET_LOCAL, slot, line);
            emit(Op.GET_FIELD, i, line);
            bindEntry(names.get(i), d, mutable);
        }
        emitNoMatch(slot, bail, line);
        scope.endBlock(mark);
    }

    /** Closes a destructuring test: jump past the failure path, then raise there. */
    private void emitNoMatch(int slot, int bail, int line) {
        int join = scope.builder.position();
        emit(Op.JMP, 0, line);
        scope.builder.patch(bail, scope.builder.position());
        emit(Op.GET_LOCAL, slot, line);
        emit(Op.ERROR_NO_MATCH, line);
        scope.builder.patch(join, scope.builder.position());
    }

    /** Binds one part of a destructuring target, honouring the {@code "_"} skip. */
    private void bindEntry(String name, Ast.Pattern.Destructure d, boolean mutable) {
        if (name.equals("_")) {
            emit(Op.POP, d.line());
            return;
        }
        bindName(name, d.line(), d.line(), mutable);
    }

    private void functionDeclaration(Ast.Stmt.Fun decl, boolean keepValue) {
        Ast.Function function = decl.function();
        if (scope.parent == null) {
            // No hoisting: a top-level 'fun' binds its global when its line runs,
            // so an entry-point script declares before use, like every statement.
            declareGlobal(function.name(), false, decl.line());
        }
        int fnConst = compileFunction(function, function.name(), false);
        emitFunctionValue(fnConst, function.line());
        if (scope.parent == null) {
            emit(Op.DEFINE_GLOBAL, constant(function.name()), decl.line());
        } else {
            int slot = scope.declare(function.name(), false, false);
            emit(Op.SET_LOCAL, slot, decl.line());
        }
        if (decl.exported()) {
            exports.add(function.name());
        }
        if (keepValue) {
            pushNull(decl.line());
        }
    }

    /** Pushes a closure over the function constant, supplying its captures. */
    private void emitFunctionValue(int fnConst, int line) {
        List<Scope.Capture> captures = lastFunctionScope.captures();
        if (captures.isEmpty()) {
            emit(Op.NEW_FUNCTION, fnConst, line);
            return;
        }
        for (Scope.Capture capture : captures) {
            if (capture.fromLocal()) {
                emit(Op.CAPTURE_LOCAL, capture.index(), line);
            } else {
                emit(Op.CAPTURE_UPVALUE, capture.index(), line);
            }
        }
        emit(Op.NEW_CLOSURE, (fnConst << 8) | captures.size(), line);
    }

    private void ifStatement(Ast.Stmt.If stmt, boolean keepValue) {
        expr(stmt.condition());
        emit(Op.JMP_IF_FALSE, 0, stmt.line());
        int toElse = scope.builder.position() - 2;
        block(stmt.thenBlock(), keepValue);
        if (stmt.elseBlock() == null) {
            if (keepValue) {
                emit(Op.JMP, 0, stmt.line());
                int toEnd = scope.builder.position() - 2;
                scope.builder.patch(toElse, scope.builder.position());
                pushNull(stmt.line());
                scope.builder.patch(toEnd, scope.builder.position());
            } else {
                scope.builder.patch(toElse, scope.builder.position());
            }
            return;
        }
        emit(Op.JMP, 0, stmt.line());
        int toEnd = scope.builder.position() - 2;
        scope.builder.patch(toElse, scope.builder.position());
        statement(stmt.elseBlock(), keepValue);
        scope.builder.patch(toEnd, scope.builder.position());
    }

    private void whileStatement(Ast.Stmt.While stmt) {
        int start = scope.builder.position();
        expr(stmt.condition());
        emit(Op.JMP_IF_FALSE, 0, stmt.line());
        int exit = scope.builder.position() - 2;
        LoopCtx ctx = loopBody(stmt.body(), start);
        emit(Op.JMP, start, stmt.line());
        scope.builder.patch(exit, scope.builder.position());
        patchBreaks(ctx);
    }

    private void loopStatement(Ast.Stmt.Loop stmt) {
        int start = scope.builder.position();
        LoopCtx ctx = loopBody(stmt.body(), start);
        emit(Op.JMP, start, stmt.line());
        patchBreaks(ctx);
    }

    /**
     * Compiles a loop body with the break / continue lists attached.
     *
     * <p>{@code continue} can be patched here, because its target &mdash; the test
     * at the top &mdash; is already behind us. {@code break} cannot: the loop's real
     * exit only exists once the caller has emitted the back-edge, so the caller
     * finishes the job in {@link #patchBreaks(LoopCtx)}. Patching a break to the
     * end of the body would land it on that back-edge and turn it into a
     * {@code continue}.</p>
     */
    private LoopCtx loopBody(Ast.Stmt.Block body, int start) {
        LoopCtx ctx = new LoopCtx(scope.tryDepth());
        loops.push(ctx);
        int mark = scope.beginBlock();
        block(body, false);
        scope.endBlock(mark);
        loops.pop();
        for (int jump : ctx.continues) {
            scope.builder.patch(jump, start);
        }
        return ctx;
    }

    /** Sends every pending {@code break} of {@code ctx} to the position the loop now exits at. */
    private void patchBreaks(LoopCtx ctx) {
        int end = scope.builder.position();
        for (int jump : ctx.breaks) {
            scope.builder.patch(jump, end);
        }
    }

    private void forStatement(Ast.Stmt.For stmt) {
        int mark = scope.beginBlock();
        expr(stmt.iterable());
        emit(Op.NEW_ITER, stmt.line());
        int iterSlot = scope.allocateTemp(1);
        emit(Op.SET_LOCAL, iterSlot, stmt.line());
        int start = scope.builder.position();
        emit(Op.GET_LOCAL, iterSlot, stmt.line());
        emit(Op.ITER_NEXT, stmt.line());
        emit(Op.JMP_IF_FALSE, 0, stmt.line());
        int exit = scope.builder.position() - 2;
        // A two-name untyped pattern is the map-pair form, `for ((k, v) in map)`;
        // every other pattern takes the element alone.
        boolean pair = stmt.pattern() instanceof Ast.Pattern.Destructure destructure
                && destructure.names().size() == 2 && destructure.typeName().isEmpty();
        if (pair) {
            emit(Op.GET_LOCAL, iterSlot, stmt.line());
            emit(Op.ITER_KV, stmt.line());
            Ast.Pattern.Destructure d = (Ast.Pattern.Destructure) stmt.pattern();
            // ITER_KV pushes the key first, so the value is on top and binds first.
            bindEntry(d.names().get(1), d, true);
            bindEntry(d.names().get(0), d, true);
        } else {
            emit(Op.GET_LOCAL, iterSlot, stmt.line());
            emit(Op.ITER_VALUE, stmt.line());
            bind(stmt.pattern(), true, stmt.line(), stmt.column());
        }
        LoopCtx ctx = loopBody(stmt.body(), start);
        emit(Op.JMP, start, stmt.line());
        scope.builder.patch(exit, scope.builder.position());
        patchBreaks(ctx);
        scope.endBlock(mark);
    }

    private void returnStatement(Ast.Stmt.Return stmt) {
        if (scope.parent == null) {
            throw fail("'return' is only valid inside a function", stmt.line(), stmt.column());
        }
        runFinalizersForReturn();
        if (scope.isMethod && stmt.value() == null) {
            // A constructor or a void-style method hands back its receiver, which
            // is what lets `super.init(...)` and chaining work without a `return this`.
            emit(Op.GET_LOCAL, 0, stmt.line());
            emit(Op.RETURN, stmt.line());
            return;
        }
        if (stmt.value() == null) {
            emit(Op.RETURN_NULL, stmt.line());
            return;
        }
        expr(stmt.value());
        emit(Op.RETURN, stmt.line());
    }

    /**
     * Duplicates the enclosing {@code finally} bodies on a path that leaves them
     * early. A {@code return} exits the whole function, so every open try in this
     * function is unwound, outermost finalizer first.
     */
    private void runFinalizersForReturn() {
        List<Ast.Stmt.Block> pending = new ArrayList<>();
        for (TryCtx ctx : trys) {
            if (ctx.finalizer != null) {
                pending.add(ctx.finalizer);
            }
        }
        // The deque runs innermost first, so reversing replays outermost first,
        // which is the order a `finally` is meant to run in.
        for (int i = pending.size() - 1; i >= 0; i--) {
            block(pending.get(i), false);
        }
    }

    private void jump(int line, boolean isBreak) {
        LoopCtx ctx = loops.peek();
        if (ctx == null) {
            throw fail((isBreak ? "'break'" : "'continue'") + " is only valid"
                    + " inside a loop", line, 1);
        }
        int drops = scope.handlerDrop(ctx.tryDepth);
        if (drops > 0) {
            emit(Op.POP_HANDLERS, drops, line);
        }
        List<Ast.Stmt.Block> pending = new ArrayList<>();
        for (TryCtx tryCtx : trys) {
            // A try records the depth it was entered *at*, so the innermost open try
            // of a loop sitting at depth 0 has tryDepth 0 as well: the jump owes its
            // finalizer whenever the try is at or below the loop's own depth.
            if (tryCtx.tryDepth >= ctx.tryDepth && tryCtx.finalizer != null) {
                pending.add(tryCtx.finalizer);
            }
        }
        for (int i = pending.size() - 1; i >= 0; i--) {
            block(pending.get(i), false);
        }
        int here = scope.builder.position();
        scope.builder.emit(Op.JMP, 0, line);
        if (isBreak) {
            ctx.breaks.add(here);
        } else {
            ctx.continues.add(here);
        }
    }

    /**
     * Compiles {@code try / catch / finally}.
     *
     * <p>The machine removes the handler record before jumping to the catch
     * target, so the catch entry begins with the error on the stack and no live
     * handler for this {@code try}. Every exit replays the finalizer itself, and a
     * rethrow is simply a {@code THROW} with the error reloaded from its slot.</p>
     */
    private void tryStatement(Ast.Stmt.Try stmt, boolean keepValue) {
        int line = stmt.line();
        int mark = scope.beginBlock();
        int errorSlot = scope.allocateTemp(1);
        int outerTryDepth = scope.tryDepth();
        scope.enterTry();
        trys.push(new TryCtx(stmt.finalizer(), outerTryDepth));

        emit(Op.ENTER_TRY, 0, line);
        int enterAt = scope.builder.position() - 2;
        block(stmt.body(), false);
        emit(Op.LEAVE_TRY, line);
        emit(Op.JMP, 0, line);
        List<Integer> toFinalize = new ArrayList<>();
        toFinalize.add(scope.builder.position() - 2);
        List<Integer> toJoin = new ArrayList<>();

        // Handler entry: the machine already removed this try's record and left the
        // error on the stack, so park it in its slot before anything else runs.
        // patch() takes the instruction's own offset and writes the word after it.
        scope.builder.patch(enterAt, scope.builder.position());
        emit(Op.SET_LOCAL, errorSlot, line);
        if (stmt.errorName() != null) {
            int bound = scope.declare(stmt.errorName(), false, false);
            emit(Op.GET_LOCAL, errorSlot, line);
            emit(Op.SET_LOCAL, bound, line);
        }

        if (stmt.handler() == null) {
            // No catch: the finalizer runs, then the same error keeps travelling.
            replayFinalizer(stmt.finalizer(), line);
            emit(Op.GET_LOCAL, errorSlot, line);
            emit(Op.THROW, line);
        } else if (stmt.errorType().isEmpty()) {
            block(stmt.handler(), false);
            replayFinalizer(stmt.finalizer(), line);
            emit(Op.JMP, 0, line);
            toJoin.add(scope.builder.position() - 2);
        } else {
            emit(Op.GET_LOCAL, errorSlot, line);
            emit(Op.IS_TYPE, constant(stmt.errorType()), line);
            emit(Op.JMP_IF_FALSE, 0, line);
            int mismatch = scope.builder.position() - 2;
            block(stmt.handler(), false);
            replayFinalizer(stmt.finalizer(), line);
            emit(Op.JMP, 0, line);
            toJoin.add(scope.builder.position() - 2);
            scope.builder.patch(mismatch, scope.builder.position());
            replayFinalizer(stmt.finalizer(), line);
            emit(Op.GET_LOCAL, errorSlot, line);
            emit(Op.THROW, line);
        }

        for (int jump : toFinalize) {
            scope.builder.patch(jump, scope.builder.position());
        }
        replayFinalizer(stmt.finalizer(), line);
        for (int jump : toJoin) {
            scope.builder.patch(jump, scope.builder.position());
        }
        if (keepValue) {
            pushNull(line);
        }
        trys.pop();
        scope.leaveTryScope();
        scope.endBlock(mark);
    }

    private void replayFinalizer(Ast.Stmt.Block finalizer, int line) {
        if (finalizer != null) {
            block(finalizer, false);
        }
    }

    private void deferStatement(Ast.Stmt.Defer stmt) {
        Ast.Function thunk = new Ast.Function("", List.of(),
                new Ast.Stmt.Block(List.of(stmt.statement()), stmt.line(), stmt.column()),
                stmt.line(), stmt.column());
        int fnConst = compileFunction(thunk, "defer", false);
        emitFunctionValue(fnConst, stmt.line());
        emit(Op.ADD_DEFER, stmt.line());
    }

    private void importStatement(Ast.Stmt.Import stmt, boolean keepValue) {
        if (!globals.contains(IMPORT_HOOK)) {
            throw fail("'import' needs a host that provides '" + IMPORT_HOOK + "';"
                    + " this runtime does not", stmt.line(), stmt.column());
        }
        emit(Op.GET_GLOBAL, constant(IMPORT_HOOK), stmt.line());
        emit(Op.LOAD_CONST, constant(stmt.path()), stmt.line());
        emit(Op.CALL, 1, stmt.line());
        String alias = stmt.alias().isEmpty()
                ? moduleAliasFor(stmt.path()) : stmt.alias();
        bindName(alias, stmt.line(), stmt.line(), false);
        if (keepValue) {
            pushNull(stmt.line());
        }
    }

    private void useStatement(Ast.Stmt.Use stmt) {
        // The module names are the host's business, not the compiler's: a page's
        // library has no 'fs' and a desktop's has six more names than this one
        // knows about, so the only honest check is the one the runtime hook makes
        // when it answers with the list the machine really has.
        String path = String.join(".", stmt.segments());
        if (!globals.contains(USE_HOOK)) {
            throw fail("'use' needs a host that provides '" + USE_HOOK + "';"
                    + " this runtime does not", stmt.line(), stmt.column());
        }
        emit(Op.GET_GLOBAL, constant(USE_HOOK), stmt.line());
        emit(Op.LOAD_CONST, constant(path), stmt.line());
        emit(Op.CALL, 1, stmt.line());
        emit(Op.POP, stmt.line());
        // The hook installs the module under its last name segment, so from here on
        // `json` is a known global in this program; without the registration a
        // following `json.parse(...)` would fail as an unknown name even though the
        // module is there at run time.
        String bound = stmt.segments().get(stmt.segments().size() - 1);
        globalMutable.put(bound, Boolean.FALSE);
        globals.add(bound);
    }

    /** The global a host installs to load a source module. */
    public static final String IMPORT_HOOK = "__import__";

    /** The global a host installs to copy a standard-library module into scope. */
    public static final String USE_HOOK = "__use__";

    private static String moduleAliasFor(String path) {
        String file = path;
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        if (slash >= 0) {
            file = file.substring(slash + 1);
        }
        int dot = file.lastIndexOf('.');
        if (dot > 0) {
            file = file.substring(0, dot);
        }
        StringBuilder out = new StringBuilder(file.length());
        for (int i = 0; i < file.length(); i++) {
            char c = file.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        if (out.isEmpty() || (!Character.isLetter(out.charAt(0)) && out.charAt(0) != '_')) {
            out.insert(0, 'm');
        }
        return out.toString();
    }

    // -- expressions ---------------------------------------------------------

    /** Compiles an expression, leaving exactly one value on the stack. */
    private void expr(Ast.Expr e) {
        switch (e) {
            case Ast.Literal l -> literal(l);
            case Ast.Interpolated i -> interpolate(i);
            case Ast.Name n -> loadName(n.name(), n.line(), n.column());
            case Ast.ThisRef t -> loadThis(t.line());
            case Ast.SuperRef t -> loadSuper(t.line());
            case Ast.ListLit l -> {
                for (Ast.Expr item : l.items()) {
                    expr(item);
                }
                emit(Op.MAKE_LIST, l.items().size(), l.line());
            }
            case Ast.MapLit m -> {
                for (int i = 0; i < m.keys().size(); i++) {
                    expr(m.keys().get(i));
                    expr(m.values().get(i));
                }
                emit(Op.MAKE_MAP, m.keys().size(), m.line());
            }
            case Ast.RangeLit r -> {
                expr(r.from());
                expr(r.to());
                // The stride is always on the stack, so MAKE_RANGE has one shape and
                // a plain 1..10 costs the same reasoning as 1..10 step 2.
                if (r.stride() != null) {
                    expr(r.stride());
                } else {
                    emit(Op.LOAD_CONST, constant(1L), r.line());
                }
                emit(Op.MAKE_RANGE, r.exclusive() ? 1 : 0, r.line());
            }
            case Ast.Function f -> {
                int fnConst = compileFunction(f, f.name(), false);
                emitFunctionValue(fnConst, f.line());
            }
            case Ast.Unary u -> unary(u);
            case Ast.Binary b -> binary(b);
            case Ast.Test t -> {
                expr(t.target());
                emit(Op.IS_TYPE, constant(t.typeName()), t.line());
                if (t.negated()) {
                    emit(Op.NOT, t.line());
                }
            }
            case Ast.Contains c -> {
                expr(c.value());
                expr(c.container());
                emit(Op.CONTAINS, c.line());
                if (c.negated()) {
                    emit(Op.NOT, c.line());
                }
            }
            case Ast.Coerce c -> {
                expr(c.target());
                emit(Op.AS_TYPE, constant(c.typeName()), c.line());
            }
            case Ast.Conditional c -> conditional(c);
            case Ast.Match m -> matchExpression(m);
            case Ast.Index i -> {
                expr(i.target());
                expr(i.index());
                emit(Op.GET_INDEX, i.line());
            }
            case Ast.Member m -> {
                expr(m.target());
                emit(Op.GET_MEMBER, constant(m.name()), m.line());
            }
            case Ast.SafeMember m -> safeMember(m);
            case Ast.Call c -> call(c);
            case Ast.MethodCall c -> methodCall(c.target(), c.name(), c.args(), c.line());
            case Ast.SafeMethodCall c -> safeMethodCall(c);
            case Ast.SuperMethod m -> superMethod(m);
            case Ast.BlockValue b -> block(b.block(), true);
        }
    }

    private void literal(Ast.Literal l) {
        Object value = switch (l.kind()) {
            case INT -> l.intValue();
            case DOUBLE -> l.num();
            case STRING -> l.text();
            case BOOL -> l.flag();
            case NULL -> null;
        };
        emit(Op.LOAD_CONST, constant(value), l.line());
    }

    /**
     * Compiles an interpolated string as a fold over {@code CONCAT}, starting from the
     * empty string. {@code CONCAT} renders each part with its display form, the
     * same rule {@code print} uses, so an interpolation and an explicit
     * {@code str(...)} of the same value agree.
     */
    private void interpolate(Ast.Interpolated i) {
        emit(Op.LOAD_CONST, constant(""), i.line());
        for (Ast.Expr part : i.parts()) {
            if (part instanceof Ast.Literal text
                    && text.kind() == Ast.Literal.LitKind.STRING) {
                emit(Op.LOAD_CONST, constant(text.text()), text.line());
            } else {
                expr(part);
            }
            emit(Op.CONCAT, i.line());
        }
    }

    private void unary(Ast.Unary u) {
        switch (u.op()) {
            case "-" -> {
                expr(u.operand());
                emit(Op.NEG, u.line());
            }
            case "!", "not" -> {
                expr(u.operand());
                emit(Op.NOT, u.line());
            }
            default -> throw fail("unknown prefix operator '" + u.op() + "'",
                    u.line(), u.column());
        }
    }

    private void binary(Ast.Binary b) {
        switch (b.op()) {
            case "&&", "and" -> andThen(b);
            case "||", "or" -> orElse(b);
            case "??" -> coalesce(b);
            default -> {
                expr(b.left());
                expr(b.right());
                emit(arith(b.op(), b.line()), b.line());
            }
        }
    }

    /** The opcode for one infix operator: arithmetic, comparison or equality. */
    private Op arith(String op, int line) {
        return switch (op) {
            case "+" -> Op.ADD;
            case "-" -> Op.SUB;
            case "*" -> Op.MUL;
            case "/" -> Op.DIV;
            case "%" -> Op.MOD;
            case "**" -> Op.POW;
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            case "<" -> Op.LT;
            case "<=" -> Op.LE;
            case ">" -> Op.GT;
            case ">=" -> Op.GE;
            default -> throw fail("unknown operator '" + op + "'", line, 1);
        };
    }

    /** {@code a && b}: both sides must be truthy; neither value escapes. */
    private void andThen(Ast.Binary b) {
        List<Integer> falsy = new ArrayList<>();
        expr(b.left());
        emit(Op.JMP_IF_FALSE, 0, b.line());
        falsy.add(scope.builder.position() - 2);
        expr(b.right());
        emit(Op.JMP_IF_FALSE, 0, b.line());
        falsy.add(scope.builder.position() - 2);
        emit(Op.LOAD_CONST, constant(Boolean.TRUE), b.line());
        emit(Op.JMP, 0, b.line());
        int join = scope.builder.position() - 2;
        for (int jump : falsy) {
            scope.builder.patch(jump, scope.builder.position());
        }
        emit(Op.LOAD_CONST, constant(Boolean.FALSE), b.line());
        scope.builder.patch(join, scope.builder.position());
    }

    /** {@code a || b}: either side being truthy is enough. */
    private void orElse(Ast.Binary b) {
        List<Integer> truthy = new ArrayList<>();
        expr(b.left());
        emit(Op.JMP_IF_TRUE, 0, b.line());
        truthy.add(scope.builder.position() - 2);
        expr(b.right());
        emit(Op.JMP_IF_TRUE, 0, b.line());
        truthy.add(scope.builder.position() - 2);
        emit(Op.LOAD_CONST, constant(Boolean.FALSE), b.line());
        emit(Op.JMP, 0, b.line());
        int join = scope.builder.position() - 2;
        for (int jump : truthy) {
            scope.builder.patch(jump, scope.builder.position());
        }
        emit(Op.LOAD_CONST, constant(Boolean.TRUE), b.line());
        scope.builder.patch(join, scope.builder.position());
    }

    /** {@code a ?? b}: the left value itself when it is not null, else the right. */
    private void coalesce(Ast.Binary b) {
        expr(b.left());
        emit(Op.DUP, b.line());
        emit(Op.JMP_IF_NOT_NULL, 0, b.line());
        int join = scope.builder.position() - 2;
        emit(Op.POP, b.line());
        expr(b.right());
        scope.builder.patch(join, scope.builder.position());
    }

    private void conditional(Ast.Conditional c) {
        expr(c.condition());
        emit(Op.JMP_IF_FALSE, 0, c.line());
        int toElse = scope.builder.position() - 2;
        expr(c.thenValue());
        emit(Op.JMP, 0, c.line());
        int toEnd = scope.builder.position() - 2;
        scope.builder.patch(toElse, scope.builder.position());
        expr(c.elseValue());
        scope.builder.patch(toEnd, scope.builder.position());
    }

    /**
     * Compiles a {@code match}: the scrutinee is parked once in a slot, each arm
     * tests its pattern, runs its guard, binds and yields its body. Arms are tried
     * in source order and the first one whose pattern and guard both hold wins;
     * falling off the end is a {@code MatchError}, not a silent null.
     */
    private void matchExpression(Ast.Match m) {
        int mark = scope.beginBlock();
        int slot = scope.allocateTemp(1);
        expr(m.subject());
        emit(Op.SET_LOCAL, slot, m.line());
        List<Integer> toEnd = new ArrayList<>();
        for (Ast.Arm arm : m.arms()) {
            int armMark = scope.beginBlock();
            List<Integer> toNext = new ArrayList<>();
            testPattern(arm.pattern(), slot);
            emit(Op.JMP_IF_FALSE, 0, arm.pattern().line());
            toNext.add(scope.builder.position() - 2);
            bindPattern(arm.pattern(), slot);
            if (arm.guard() != null) {
                expr(arm.guard());
                emit(Op.JMP_IF_FALSE, 0, arm.guard().line());
                toNext.add(scope.builder.position() - 2);
            }
            expr(arm.body());
            emit(Op.JMP, 0, arm.pattern().line());
            toEnd.add(scope.builder.position() - 2);
            for (int jump : toNext) {
                scope.builder.patch(jump, scope.builder.position());
            }
            scope.endBlock(armMark);
        }
        emit(Op.GET_LOCAL, slot, m.line());
        emit(Op.ERROR_NO_MATCH, m.line());
        for (int jump : toEnd) {
            scope.builder.patch(jump, scope.builder.position());
        }
        scope.endBlock(mark);
    }

    /** Pushes true / false for one pattern, reading the scrutinee from its slot. */
    private void testPattern(Ast.Pattern pattern, int slot) {
        switch (pattern) {
            case Ast.Pattern.Any ignored -> emit(Op.LOAD_CONST,
                    constant(Boolean.TRUE), pattern.line());
            case Ast.Pattern.Named ignored -> emit(Op.LOAD_CONST,
                    constant(Boolean.TRUE), pattern.line());
            case Ast.Pattern.LiteralOf lit -> {
                emit(Op.GET_LOCAL, slot, lit.line());
                expr(lit.literal());
                emit(Op.EQ, lit.line());
            }
            case Ast.Pattern.TypeOf t -> {
                emit(Op.GET_LOCAL, slot, t.line());
                emit(Op.IS_TYPE, constant(t.typeName()), t.line());
            }
            case Ast.Pattern.Destructure d -> {
                emit(Op.GET_LOCAL, slot, d.line());
                if (d.typeName().isEmpty()) {
                    emit(Op.MATCH_LIST, d.names().size(), d.line());
                } else {
                    emit(Op.IS_TYPE, constant(d.typeName()), d.line());
                }
            }
        }
    }

    /** Binds one pattern's names from the scrutinee slot, after a successful test. */
    private void bindPattern(Ast.Pattern pattern, int slot) {
        switch (pattern) {
            case Ast.Pattern.Any ignored -> {
            }
            case Ast.Pattern.Named n -> {
                emit(Op.GET_LOCAL, slot, n.line());
                bindName(n.name(), n.line(), n.line(), true);
            }
            case Ast.Pattern.LiteralOf ignored -> {
            }
            case Ast.Pattern.TypeOf t -> {
                if (t.bind() != null) {
                    emit(Op.GET_LOCAL, slot, t.line());
                    bindName(t.bind(), t.line(), t.line(), true);
                }
            }
            case Ast.Pattern.Destructure d -> {
                for (int i = 0; i < d.names().size(); i++) {
                    emit(Op.GET_LOCAL, slot, d.line());
                    if (d.typeName().isEmpty()) {
                        emit(Op.LOAD_CONST, constant((long) i), d.line());
                        emit(Op.GET_INDEX, d.line());
                    } else {
                        emit(Op.GET_FIELD, i, d.line());
                    }
                    bindEntry(d.names().get(i), d, true);
                }
            }
        }
    }

    /** {@code a?.b}: evaluate {@code a} once, then read the member or yield null. */
    private void safeMember(Ast.SafeMember m) {
        int mark = scope.beginBlock();
        int slot = scope.allocateTemp(1);
        expr(m.target());
        emit(Op.SET_LOCAL, slot, m.line());
        emit(Op.GET_LOCAL, slot, m.line());
        emit(Op.IS_TYPE, constant("Null"), m.line());
        emit(Op.JMP_IF_TRUE, 0, m.line());
        int toNull = scope.builder.position() - 2;
        emit(Op.GET_LOCAL, slot, m.line());
        emit(Op.GET_MEMBER, constant(m.name()), m.line());
        emit(Op.JMP, 0, m.line());
        int toEnd = scope.builder.position() - 2;
        scope.builder.patch(toNull, scope.builder.position());
        pushNull(m.line());
        scope.builder.patch(toEnd, scope.builder.position());
        scope.endBlock(mark);
    }

    private void call(Ast.Call c) {
        boolean named = c.argNames() != null && !c.argNames().isEmpty();
        if (!named && c.callee() instanceof Ast.Name receiver
                && resolveLexical(receiver.name(), receiver.line(), receiver.column()) == null
                && isClassMethod(receiver.name())) {
            // A bare method name inside a class is a send to `this`, the way it
            // reads in Kotlin; the receiver is loaded rather than looked up by name.
            loadThis(c.line());
            for (Ast.Expr arg : c.args()) {
                expr(arg);
            }
            emit(Op.CALL_METHOD,
                    (constant(receiver.name()) << 8) | c.args().size(), c.line());
            return;
        }
        if (named) {
            emit(Op.LOAD_CONST, constant(c.argNames().toArray(new String[0])), c.line());
        }
        expr(c.callee());
        for (Ast.Expr arg : c.args()) {
            expr(arg);
        }
        emit(named ? Op.CALL_KW : Op.CALL, c.args().size(), c.line());
    }

    /**
     * {@code target.name(args)}: the name's constant index and the argument count
     * share one operand word, so a call stays two words wide in the stream.
     */
    private void methodCall(Ast.Expr target, String name, List<Ast.Expr> args, int line) {
        expr(target);
        for (Ast.Expr arg : args) {
            expr(arg);
        }
        emit(Op.CALL_METHOD, (constant(name) << 8) | args.size(), line);
    }

    /** {@code target?.name(args)}: nothing past the null check is evaluated. */
    private void safeMethodCall(Ast.SafeMethodCall c) {
        int mark = scope.beginBlock();
        int slot = scope.allocateTemp(1);
        expr(c.target());
        emit(Op.SET_LOCAL, slot, c.line());
        emit(Op.GET_LOCAL, slot, c.line());
        emit(Op.IS_TYPE, constant("Null"), c.line());
        emit(Op.JMP_IF_TRUE, 0, c.line());
        int toNull = scope.builder.position() - 2;
        emit(Op.GET_LOCAL, slot, c.line());
        for (Ast.Expr arg : c.args()) {
            expr(arg);
        }
        emit(Op.CALL_METHOD, (constant(c.name()) << 8) | c.args().size(), c.line());
        emit(Op.JMP, 0, c.line());
        int toEnd = scope.builder.position() - 2;
        scope.builder.patch(toNull, scope.builder.position());
        pushNull(c.line());
        scope.builder.patch(toEnd, scope.builder.position());
        scope.endBlock(mark);
    }

    private void superMethod(Ast.SuperMethod m) {
        if (classes.isEmpty()) {
            throw fail("'super' is only valid inside a class", m.line(), m.column());
        }
        for (Ast.Expr arg : m.args()) {
            expr(arg);
        }
        emit(Op.SUPER_CALL, (constant(m.name()) << 8) | m.args().size(), m.line());
    }

    // -- assignment ----------------------------------------------------------

    /**
     * Compiles an assignment or a compound assignment.
     *
     * <p>A plain assignment to a variable stores straight from the stack. Anything
     * with an evaluated target &mdash; a member, an element, a class field &mdash;
     * parks the pieces in temporary slots first, so the target is evaluated once
     * and in source order even when the right-hand side writes to it.</p>
     */
    private void assign(Ast.Expr target, Ast.Expr value, String op, Ast.Stmt stmt,
                        boolean compound, boolean keepValue) {
        int line = stmt.line();
        switch (target) {
            case Ast.Name n -> {
                Slot slot = resolveForStore(n.name(), line, n.column());
                if (slot.kind() == R_FIELD) {
                    assignMember(() -> loadThis(line), slot.name(), value, op,
                            compound, keepValue, line);
                    return;
                }
                if (compound) {
                    loadSlot(slot, line);
                    expr(value);
                    emit(arith(op, line), line);
                } else {
                    expr(value);
                }
                if (keepValue) {
                    emit(Op.DUP, line);
                }
                storeSlot(slot, line);
            }
            case Ast.Member m -> assignMember(() -> expr(m.target()), m.name(), value,
                    op, compound, keepValue, line);
            case Ast.Index i -> {
                int mark = scope.beginBlock();
                int objSlot = scope.allocateTemp(3);
                expr(i.target());
                emit(Op.SET_LOCAL, objSlot, line);
                expr(i.index());
                emit(Op.SET_LOCAL, objSlot + 1, line);
                if (compound) {
                    emit(Op.GET_LOCAL, objSlot, line);
                    emit(Op.GET_LOCAL, objSlot + 1, line);
                    emit(Op.GET_INDEX, line);
                    expr(value);
                    emit(arith(op, line), line);
                } else {
                    expr(value);
                }
                emit(Op.SET_LOCAL, objSlot + 2, line);
                emit(Op.GET_LOCAL, objSlot, line);
                emit(Op.GET_LOCAL, objSlot + 1, line);
                emit(Op.GET_LOCAL, objSlot + 2, line);
                emit(Op.SET_INDEX, line);
                if (keepValue) {
                    emit(Op.GET_LOCAL, objSlot + 2, line);
                }
                scope.endBlock(mark);
            }
            default -> throw fail("the left-hand side of an assignment must be a"
                    + " name, a field or an element", line, stmt.column());
        }
    }

    private void assignMember(Runnable emitTarget, String name, Ast.Expr value, String op,
                              boolean compound, boolean keepValue, int line) {
        int mark = scope.beginBlock();
        int objSlot = scope.allocateTemp(2);
        emitTarget.run();
        emit(Op.SET_LOCAL, objSlot, line);
        if (compound) {
            emit(Op.GET_LOCAL, objSlot, line);
            emit(Op.GET_MEMBER, constant(name), line);
            expr(value);
            emit(arith(op, line), line);
        } else {
            expr(value);
        }
        emit(Op.SET_LOCAL, objSlot + 1, line);
        emit(Op.GET_LOCAL, objSlot, line);
        emit(Op.GET_LOCAL, objSlot + 1, line);
        emit(Op.SET_MEMBER, constant(name), line);
        if (keepValue) {
            emit(Op.GET_LOCAL, objSlot + 1, line);
        }
        scope.endBlock(mark);
    }

    // -- name resolution -----------------------------------------------------

    private void loadName(String name, int line, int column) {
        loadSlot(resolve(name, line, column), line);
    }

    private void loadSlot(Slot slot, int line) {
        switch (slot.kind()) {
            case R_LOCAL -> emit(Op.GET_LOCAL, slot.index(), line);
            case R_BOX -> emit(Op.GET_LOCAL_BOX, slot.index(), line);
            case R_UPVALUE -> emit(Op.GET_UPVALUE, slot.index(), line);
            case R_GLOBAL -> emit(Op.GET_GLOBAL, constant(slot.name()), line);
            case R_FIELD -> {
                loadThis(line);
                emit(Op.GET_MEMBER, constant(slot.name()), line);
            }
            default -> throw new IllegalStateException("unhandled reference kind");
        }
    }

    private void storeSlot(Slot slot, int line) {
        switch (slot.kind()) {
            case R_LOCAL -> emit(Op.SET_LOCAL, slot.index(), line);
            case R_BOX -> emit(Op.SET_LOCAL_BOX, slot.index(), line);
            case R_UPVALUE -> emit(Op.SET_UPVALUE, slot.index(), line);
            case R_GLOBAL -> emit(Op.SET_GLOBAL, constant(slot.name()), line);
            default -> throw new IllegalStateException(
                    "field stores are lowered through assignMember");
        }
    }

    /** Reads the method receiver, which may be a capture inside a nested lambda. */
    private void loadThis(int line) {
        Slot slot = resolveLexical(Scope.THIS, line, line);
        if (slot == null) {
            throw fail("'this' is only valid inside a class", line, 1);
        }
        loadSlot(slot, line);
    }

    /** Reads the base-class view that the machine installs in a method frame. */
    private void loadSuper(int line) {
        if (classes.isEmpty()) {
            throw fail("'super' is only valid inside a class", line, 1);
        }
        Slot slot = resolveLexical(Scope.SUPER, line, line);
        if (slot == null) {
            throw fail("'super' needs a class with a base to stand on", line, 1);
        }
        loadSlot(slot, line);
    }

    /**
     * Resolves a bare name: this function's locals, then an enclosing function's
     * (registering the capture chain), then the enclosing class's fields including
     * the inherited ones, then a global. Nothing is looked up by string at run time.
     */
    private Slot resolve(String name, int line, int column) {
        Slot lexical = resolveLexical(name, line, column);
        if (lexical != null) {
            return lexical;
        }
        if (!classes.isEmpty() && methodBarrier != null
                && (classes.peek().fields.contains(name)
                        || classes.peek().inheritedFields.contains(name))) {
            return new Slot(R_FIELD, -1, name, false);
        }
        if (globals.contains(name)) {
            return new Slot(R_GLOBAL, -1, name, !Boolean.TRUE.equals(globalMutable.get(name)));
        }
        if (Keywords.isReserved(name)) {
            throw fail("'" + name + "' is a reserved word", line, column);
        }
        throw fail("undefined-name", "unknown name '" + name + "'; declare it with 'let' or 'var'"
                + " before using it", line, column);
    }

    /** Resolves a name as an assignment target, enforcing immutability. */
    private Slot resolveForStore(String name, int line, int column) {
        Slot slot = resolve(name, line, column);
        if (slot.immutable()) {
            throw fail("'" + name + "' is a 'let' binding and cannot be reassigned;"
                    + " declare it with 'var' to change it", line, column);
        }
        return slot;
    }

    /** @return the lexical binding for {@code name}, or null when none owns it */
    private Slot resolveLexical(String name, int line, int column) {
        Lex lex = findLexical(name);
        if (lex == null) {
            return null;
        }
        boolean boxed = lex.binding().boxed();
        int kind = boxed ? R_BOX : R_LOCAL;
        int index = lex.binding().slot();
        if (lex.owner() != scope) {
            index = captureFrom(lex.owner(), scope, name);
            kind = R_UPVALUE;
        }
        if (index < 0) {
            throw fail("cannot reach '" + name + "' from here", line, column);
        }
        return new Slot(kind, index, name, !lex.binding().mutable());
    }

    /** A binding and the function scope that owns it. */
    private record Lex(Scope.Binding binding, Scope owner) { }

    /** Walks the function scopes outward, stopping at a method's own frame. */
    private Lex findLexical(String name) {
        Scope s = scope;
        while (s != null) {
            Scope.Binding b = s.find(name);
            if (b != null) {
                return new Lex(b, s);
            }
            if (s == methodBarrier) {
                return null;
            }
            s = s.parent;
        }
        return null;
    }

    /**
     * Registers {@code name} as an upvalue of {@code child}, boxing the owner's
     * slot and recording the intermediate captures on the way down.
     *
     * @return the upvalue index inside {@code child}, or -1 when nobody owns it
     */
    private int captureFrom(Scope owner, Scope child, String name) {
        Scope.Binding b = owner.find(name);
        if (b != null) {
            owner.box(b.slot());
            return child.capture(name, true, b.slot());
        }
        if (owner == methodBarrier || owner.parent == null) {
            return -1;
        }
        int outer = captureFrom(owner.parent, owner, name);
        if (outer < 0) {
            return -1;
        }
        return child.capture(name, false, outer);
    }

    /** @return true when {@code name} is a method of the enclosing class or of a base */
    private boolean isClassMethod(String name) {
        return !classes.isEmpty() && methodBarrier != null
                && (classes.peek().methods.contains(name)
                        || classes.peek().inheritedMethods.contains(name));
    }

    // -- functions -----------------------------------------------------------

    /** Compiles a body and returns its constant-pool index in the enclosing scope. */
    private int compileFunction(Ast.Function function, String name, boolean method) {
        return constant(compileBody(function, name, method));
    }

    /**
     * Compiles one function body in its own scope.
     *
     * <p>A nested body starts a fresh statement context: the {@code break} and
     * {@code continue} lists and the open {@code try} scopes of the enclosing
     * function belong to that frame, not to this one, so they are saved and
     * cleared for the duration. A method additionally becomes the capture
     * barrier: its own locals are the outermost ones anybody inside may reach.</p>
     */
    private CompiledFunction compileBody(Ast.Function function, String name, boolean method) {
        Scope outer = scope;
        Scope outerBarrier = methodBarrier;
        List<LoopCtx> savedLoops = List.copyOf(loops);
        List<TryCtx> savedTrys = List.copyOf(trys);
        loops.clear();
        trys.clear();

        Scope inner = new Scope(outer, name, sourceName, function.line(), method);
        scope = inner;
        if (method) {
            inner.bindReceiver();
            methodBarrier = inner;
        }
        inner.beginBlock();
        int minArgs = emitParams(function.params());
        inner.builder.params(function.params().size(), minArgs);

        List<Ast.Stmt> body = function.body().statements();
        if (body.isEmpty()) {
            pushNull(function.line());
        } else {
            for (int i = 0; i < body.size() - 1; i++) {
                statement(body.get(i), false);
            }
            statement(body.get(body.size() - 1), true);
        }
        emit(Op.RETURN, lastLine(body) == 0 ? function.line() : lastLine(body));

        CompiledFunction compiled = inner.finish(inner.slotsNeeded());
        scope = outer;
        methodBarrier = outerBarrier;
        loops.clear();
        loops.addAll(savedLoops);
        trys.clear();
        trys.addAll(savedTrys);
        lastFunctionScope = inner;
        return compiled;
    }

    /**
     * Declares the parameters and emits the default-argument prologue.
     *
     * <p>A caller that omits a trailing argument leaves the
     * {@link org.jdesktop.lg3d.mandela.values.Values#ABSENT} marker in its slot,
     * and the body overwrites it here. That keeps arity checks in one place (the
     * machine, at the call site) and costs a defaulted parameter two instructions
     * per call instead of a search at the call site.</p>
     *
     * @return the number of arguments a call must supply
     */
    private int emitParams(List<Ast.Param> params) {
        int minArgs = params.size();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).defaultExpr() != null) {
                minArgs = i;
                break;
            }
        }
        boolean seenDefault = false;
        for (Ast.Param p : params) {
            if (p.defaultExpr() != null) {
                seenDefault = true;
            } else if (seenDefault) {
                warn("param-order", "the required parameter '" + p.name() + "'"
                        + " follows one with a default; give it a default too",
                        p.line(), p.column());
            }
            int slot = scope.declare(p.name(), true, true);
            if (p.defaultExpr() == null) {
                continue;
            }
            emit(Op.GET_LOCAL, slot, p.line());
            emit(Op.IS_ABSENT, p.line());
            emit(Op.JMP_IF_FALSE, 0, p.line());
            int skip = scope.builder.position() - 2;
            expr(p.defaultExpr());
            emit(Op.SET_LOCAL, slot, p.line());
            scope.builder.patch(skip, scope.builder.position());
        }
        return minArgs;
    }

    // -- types, classes and enums --------------------------------------------

    private void typeDeclaration(Ast.Stmt.TypeDecl decl, boolean keepValue) {
        ClassCtx ctx = new ClassCtx();
        for (Ast.Param field : decl.fields()) {
            requireField(ctx, field.name(), decl.name(), field.line());
        }
        Map<String, CompiledFunction> methods = collectMethods(decl.methods(), ctx, decl.name());
        classes.push(ctx);
        CompiledFunction ctor = compileConstructor(decl.name(), decl.fields(), "",
                List.of(), List.of(), List.of(), decl.line());
        for (Ast.Stmt.Fun method : decl.methods()) {
            methods.put(method.function().name(),
                    compileBody(method.function(), method.function().name(), true));
        }
        classes.pop();
        ClassDef def = new ClassDef(decl.name(), MandelaClass.Kind.TYPE, null,
                List.copyOf(ctx.fields), ctor.minArgs(), methods, ctor, List.of(), sourceName);
        declareType(decl.name(), def, decl.exported(), decl.line(), keepValue);
    }

    private void classDeclaration(Ast.Stmt.ClassDecl decl, boolean keepValue) {
        ClassCtx ctx = new ClassCtx();
        for (Ast.Param param : decl.params()) {
            requireField(ctx, param.name(), decl.name(), param.line());
        }
        for (Ast.Stmt.Let field : decl.fields()) {
            requireField(ctx, fieldName(field), decl.name(), field.line());
        }
        Map<String, CompiledFunction> methods = collectMethods(decl.methods(), ctx, decl.name());
        seedInherited(ctx, decl.parent());
        classes.push(ctx);
        CompiledFunction ctor = compileConstructor(decl.name(), decl.params(), decl.parent(),
                decl.parentArgs(), decl.fields(), decl.initializers(), decl.line());
        for (Ast.Stmt.Fun method : decl.methods()) {
            methods.put(method.function().name(),
                    compileBody(method.function(), method.function().name(), true));
        }
        classes.pop();
        ClassDef def = new ClassDef(decl.name(), MandelaClass.Kind.CLASS,
                decl.parent().isEmpty() ? null : decl.parent(),
                List.copyOf(ctx.fields), ctor.minArgs(), methods, ctor, List.of(), sourceName);
        declareType(decl.name(), def, decl.exported(), decl.line(), keepValue);
    }

    private void enumDeclaration(Ast.Stmt.EnumDecl decl, boolean keepValue) {
        if (decl.constants().isEmpty()) {
            throw fail("an enum needs at least one constant", decl.line(), decl.column());
        }
        // An enum's constants carry no state beyond name and ordinal, so the
        // grammar gives enums no method bodies; behaviour lives in free functions.
        ClassDef def = new ClassDef(decl.name(), MandelaClass.Kind.ENUM, null,
                List.of("name", "ordinal"), 0, Map.of(), null, decl.constants(), sourceName);
        declareType(decl.name(), def, decl.exported(), decl.line(), keepValue);
    }

    /**
     * Collects the member names a base class contributes to bare-name resolution.
     *
     * <p>Only types declared earlier in this same compile are visible; the walk
     * simply stops at a base whose shape is unknown (a host-registered class, or a
     * forward reference), so an inherited member of one still has to be spelled
     * {@code this.name}. The own members collected by {@link #requireField} and
     * {@link #collectMethods} are deliberately kept in separate sets, so a name
     * declared here shadows the base instead of colliding with it.</p>
     *
     * @param ctx    the class being compiled, already holding its own members
     * @param parent the {@code extends} base name, empty when there is none
     */
    private void seedInherited(ClassCtx ctx, String parent) {
        Set<String> seen = new HashSet<>();
        String name = parent;
        while (name != null && !name.isEmpty() && seen.add(name)) {
            ClassDef base = declaredTypes.get(name);
            if (base == null) {
                return;
            }
            ctx.inheritedFields.addAll(base.fieldNames());
            ctx.inheritedMethods.addAll(base.methods().keySet());
            if (base.isEnum() || base.isValue()) {
                return; // neither can be extended, so the chain ends here
            }
            name = base.parentName();
        }
    }

    /** Registers the declared method names, rejecting a hand-written {@code init}. */
    private Map<String, CompiledFunction> collectMethods(List<Ast.Stmt.Fun> declared,
                                                         ClassCtx ctx, String className) {
        Map<String, CompiledFunction> methods = new LinkedHashMap<>();
        for (Ast.Stmt.Fun method : declared) {
            String name = method.function().name();
            if (name.isEmpty()) {
                throw fail("a method needs a name", method.line(), method.column());
            }
            if (name.equals("init")) {
                throw fail("'" + className + "' may not declare a method named 'init'"
                        + "; the constructor is built from the declaration's"
                        + " parameters and 'init' blocks", method.line(), method.column());
            }
            if (!ctx.methods.add(name)) {
                throw fail("'" + className + "' already declares a method named"
                        + " '" + name + "'", method.line(), method.column());
            }
            methods.put(name, null);
        }
        return methods;
    }

    /**
     * Builds a class's synthesised constructor: base call, then the primary
     * parameters copied into fields, then the declared field initialisers, then the
     * {@code init} blocks, in that order, and finally {@code this} as the result.
     */
    private CompiledFunction compileConstructor(String className, List<Ast.Param> params,
                                               String parent, List<Ast.Expr> parentArgs,
                                               List<Ast.Stmt.Let> fields,
                                               List<Ast.Stmt.Block> inits, int line) {
        Scope outer = scope;
        List<LoopCtx> savedLoops = List.copyOf(loops);
        List<TryCtx> savedTrys = List.copyOf(trys);
        loops.clear();
        trys.clear();

        Scope inner = new Scope(outer, "init", sourceName, line, true);
        scope = inner;
        methodBarrier = inner;
        inner.bindReceiver();
        inner.beginBlock();
        int minArgs = emitParams(params);
        inner.builder.params(params.size(), minArgs);

        if (parent != null && !parent.isEmpty()) {
            // An empty `extends Base` forwards the primary parameters, which is the
            // common case; naming arguments is opt-in.
            List<Ast.Expr> args = parentArgs;
            if (args.isEmpty()) {
                List<Ast.Expr> forwarded = new ArrayList<>(params.size());
                for (Ast.Param p : params) {
                    forwarded.add(new Ast.Name(p.name(), p.line(), p.column()));
                }
                args = forwarded;
            }
            for (Ast.Expr arg : args) {
                expr(arg);
            }
            emit(Op.SUPER_CALL, (constant("init") << 8) | args.size(), line);
        }
        for (Ast.Param p : params) {
            emit(Op.GET_LOCAL, 0, p.line());
            emit(Op.GET_LOCAL, inner.slotOf(p.name()), p.line());
            emit(Op.INIT_FIELD, constant(p.name()), p.line());
        }
        for (Ast.Stmt.Let field : fields) {
            emit(Op.GET_LOCAL, 0, field.line());
            if (field.value() == null) {
                pushNull(field.line());
            } else {
                expr(field.value());
            }
            emit(Op.INIT_FIELD, constant(fieldName(field)), field.line());
        }
        for (Ast.Stmt.Block init : inits) {
            block(init, false);
        }
        emit(Op.GET_LOCAL, 0, line);
        emit(Op.RETURN, line);

        CompiledFunction ctor = inner.finish(inner.slotsNeeded());
        scope = outer;
        loops.clear();
        loops.addAll(savedLoops);
        trys.clear();
        trys.addAll(savedTrys);
        lastFunctionScope = inner;
        return ctor;
    }

    /** Emits the class value and binds the declared name to it. */
    private void declareType(String name, ClassDef def, boolean exported, int line,
                             boolean keepValue) {
        declaredTypes.put(name, def);
        emit(Op.DEFINE_CLASS, constant(def), line);
        if (scope.parent == null) {
            declareGlobal(name, false, line);
            emit(Op.DEFINE_GLOBAL, constant(name), line);
        } else {
            int slot = scope.declare(name, false, false);
            emit(Op.SET_LOCAL, slot, line);
        }
        if (exported) {
            exports.add(name);
        }
        if (keepValue) {
            pushNull(line);
        }
    }

    /** The single name a field declaration binds. */
    private String fieldName(Ast.Stmt.Let field) {
        if (field.pattern() instanceof Ast.Pattern.Named n) {
            return n.name();
        }
        throw fail("a class field must be one name; a destructuring pattern is"
                + " not a field declaration", field.line(), field.column());
    }

    private void requireField(ClassCtx ctx, String name, String className, int line) {
        if (!ctx.fields.add(name)) {
            throw fail("'" + className + "' declares the field '" + name + "' twice",
                    line, 1);
        }
    }
}
