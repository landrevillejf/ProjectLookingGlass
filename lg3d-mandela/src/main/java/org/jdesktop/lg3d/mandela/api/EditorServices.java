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
package org.jdesktop.lg3d.mandela.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jdesktop.lg3d.mandela.lang.Ast;
import org.jdesktop.lg3d.mandela.lang.Keywords;
import org.jdesktop.lg3d.mandela.lang.Parser;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaMap;

/**
 * The language's editor services: the answers a tool needs <em>about</em> a buffer
 * rather than <em>from</em> a program.
 *
 * <p>This is the API the desktop's Espresso editor is built on, and it is public for
 * exactly that reason &mdash; an editor should not have to re-implement Mandela's
 * grammar to offer an outline or a completion list, and a third-party tool that
 * writes its own parser will drift from the language the moment the grammar grows a
 * form. Everything here reads the real {@link Parser} and the real
 * {@code StandardLibrary} tables, so a name offered is a name the language
 * accepts.</p>
 *
 * <p>Three contracts make this usable on a keystroke:</p>
 * <ul>
 *   <li><b>Nothing throws.</b> A buffer is usually mid-edit, so a parse failure is
 *       the normal case, not an exception: the outline and the vocabulary answer
 *       from the part of the tree that did build, and an unparsable buffer answers
 *       empty rather than an error the editor would have to explain.</li>
 *   <li><b>Nothing runs.</b> Services read syntax and the library's tables. The
 *       private engine used for the global and module vocabularies is never asked to
 *       evaluate a program, so a completion popup cannot execute a script, cannot
 *       touch a file and needs no capability decision.</li>
 *   <li><b>Nothing is invented.</b> Members are offered only where the receiver's
 *       type is knowable from the buffer &mdash; a {@code use}d module, an annotated
 *       or obviously-constructed binding, {@code this} inside a declared class, an
 *       enum's own name. Where it is a guess, the answer is empty, because a wrong
 *       candidate costs the developer more attention than a missing one.</li>
 * </ul>
 *
 * @see Mandela the language's embedding facade
 */
public final class EditorServices {

    /** The name a buffer without a file is reported under. */
    public static final String BUFFER = "<buffer>";

    /** How many candidates one call may return; a popup cannot scroll forever. */
    public static final int MAX_CANDIDATES = 80;

    /**
     * One declaration in a buffer, as an outline row.
     *
     * @param name   the declared name, spelled as the author wrote it
     * @param kind   a short label: {@code fun}, {@code method}, {@code class},
     *               {@code type}, {@code enum}, {@code constant}, {@code field},
     *               {@code let} or {@code var}
     * @param line   the 1-based line to jump to
     * @param column the 1-based column of the declaration keyword
     */
    public record Entry(String name, String kind, int line, int column) {

        /** @return the label an outline node shows. */
        public String display() {
            return kind.isEmpty() ? name : name + " : " + kind;
        }
    }

    /** The value types whose members this class can list. */
    private static final List<String> VALUE_TYPES =
            List.of("Str", "List", "Map", "Range", "Int", "Num", "Bool");

    /**
     * The engine the vocabulary lookups read their tables from, built on first use.
     *
     * <p>Console capabilities and no output sink, because nothing is ever run on it:
     * it exists to answer "which globals does the language install" and "what does
     * {@code std.fs} export", both of which are questions about a library instance.</p>
     */
    private static volatile Runtime host;

    private EditorServices() {
        // Static services.
    }

    // -- vocabulary the language owns ---------------------------------------

    /**
     * Every keyword and contextual word, in the order the language declares them.
     *
     * <p>Both sets are included because an editor colours both and a completion list
     * should offer both; {@link Keywords#RESERVED} is the subset a name may not use.</p>
     *
     * @return the words, never empty
     */
    public static List<String> keywords() {
        return List.copyOf(Keywords.ALL);
    }

    /**
     * The names a Mandela program may read without declaring them: the library's
     * globals ({@code println}, {@code str}, {@code typeOf}, &hellip;).
     *
     * <p>The engine's own view of its global table is a compile-time question
     * &mdash; which names may a program mention &mdash; and it includes the hooks the
     * compiler installs to make {@code use} and {@code import} work. A completion
     * list is an authoring question, and {@code __use__} is not something a
     * developer should be offered, so every double-underscore name is dropped: that
     * prefix is the language's marker for a name a script never writes.</p>
     *
     * @return the names, sorted so a tool's list is stable between calls
     */
    public static List<String> globals() {
        List<String> out = new ArrayList<>();
        for (String name : host().knownGlobals()) {
            if (name != null && !name.startsWith("__")) {
                out.add(name);
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * The modules a {@code use std.<name>} statement can ask for.
     *
     * @return the module names, in the order the library declares them
     */
    public static List<String> moduleNames() {
        return host().library().moduleNames();
    }

    /**
     * The members one of the language's own value types answers to.
     *
     * @param typeName {@code Str}, {@code List}, {@code Map}, {@code Range},
     *                 {@code Int}, {@code Num} or {@code Bool}; anything else
     *                 answers empty
     * @return the member names, in the order the library registers them
     */
    public static List<String> valueMembers(String typeName) {
        return host().library().valueMemberNames(typeName);
    }

    /**
     * The names a standard module exports, which is what makes {@code json.} and
     * {@code fs.} completable.
     *
     * <p>The lookup asks the library for the names it would install, never for the
     * module object itself, so a gated module such as {@code std.fs} contributes its
     * vocabulary without this call needing a capability grant or touching a file.</p>
     *
     * @param module the module as written after {@code std.}, or with the prefix
     * @return its exported names, empty for a module this host does not have
     */
    public static List<String> moduleMembers(String module) {
        if (module == null || module.isBlank()) {
            return List.of();
        }
        try {
            MandelaMap installed =
                    host().library().stdlibNames(host().machine(), module.trim());
            String shortName = module.trim().startsWith("std.")
                    ? module.trim().substring(4) : module.trim();
            Object value = installed.get(shortName);
            return (value instanceof MandelaMap map) ? map.keys() : List.of();
        } catch (RuntimeException absent) {
            // A module this host refuses to name answers no candidates rather than
            // the MandelaError the library throws for an unknown `std.<name>`: a
            // squiggle in the buffer is the language's job, not the popup's, and
            // both that error and a parser failure are RuntimeExceptions.
            return List.of();
        }
    }

    // -- outline ------------------------------------------------------------

    /**
     * The declarations in a buffer, in source order, without descending into bodies.
     *
     * <p>Class and type members are included and sit next to their owner, because an
     * editor's outline tree is built from the flat order: a method row follows the
     * class row that declares it.</p>
     *
     * @param source the buffer text; null is empty
     * @return the rows, empty when nothing parsed
     */
    public static List<Entry> outline(String source) {
        List<Entry> out = new ArrayList<>();
        for (Ast.Stmt statement : statements(source)) {
            outlineTop(out, statement);
        }
        return out;
    }

    private static void outlineTop(List<Entry> out, Ast.Stmt statement) {
        switch (statement) {
            case Ast.Stmt.Fun fun -> out.add(new Entry(
                    fun.function().name(), "fun", fun.line(), fun.column()));
            case Ast.Stmt.ClassDecl cls -> {
                out.add(new Entry(cls.name(), "class", cls.line(), cls.column()));
                for (Ast.Stmt.Let field : cls.fields()) {
                    outlineBinding(out, field.pattern(), "field", field);
                }
                for (Ast.Stmt.Fun method : cls.methods()) {
                    out.add(new Entry(method.function().name(), "method",
                            method.line(), method.column()));
                }
            }
            case Ast.Stmt.TypeDecl type -> {
                out.add(new Entry(type.name(), "type", type.line(), type.column()));
                for (Ast.Param field : type.fields()) {
                    out.add(new Entry(field.name(), "field",
                            type.line(), type.column()));
                }
                for (Ast.Stmt.Fun method : type.methods()) {
                    out.add(new Entry(method.function().name(), "method",
                            method.line(), method.column()));
                }
            }
            case Ast.Stmt.EnumDecl enumType -> {
                out.add(new Entry(enumType.name(), "enum",
                        enumType.line(), enumType.column()));
                for (String constant : enumType.constants()) {
                    out.add(new Entry(constant, "constant",
                            enumType.line(), enumType.column()));
                }
            }
            case Ast.Stmt.Let binding -> outlineBinding(out, binding.pattern(),
                    binding.mutable() ? "var" : "let", binding);
            default -> {
                // Control flow, imports and expressions are not declarations.
            }
        }
    }

    private static void outlineBinding(List<Entry> out, Ast.Pattern pattern,
                                       String kind, Ast.Stmt at) {
        for (String name : boundNames(pattern)) {
            out.add(new Entry(name, kind, at.line(), at.column()));
        }
    }

    // -- the buffer's own names ----------------------------------------------

    /**
     * Every name a buffer binds: declared types, functions, methods, fields,
     * constants, and every {@code let} / {@code var} / loop / parameter binding in
     * any scope.
     *
     * <p>This is the vocabulary a completion list offers before the host's own names,
     * and it is deliberately scope-blind: an editor that offered only the innermost
     * scope would stop suggesting a name the moment the caret moved, and a script
     * that reads a name out of order is a bug the language reports, not the editor.
     * </p>
     *
     * @param source the buffer text; null is empty
     * @return the names, in first-declaration order, without duplicates
     */
    public static List<String> vocabulary(String source) {
        Set<String> names = new LinkedHashSet<>();
        List<Ast.Stmt> statements = statements(source);
        for (Ast.Stmt statement : statements) {
            collectDeclared(statement, names);
        }
        // The walk reaches bodies the outline skips, so a local binding inside a
        // function is a name the buffer mentions and can complete.
        new Ast.Walk(new Ast.Visitor() {
            @Override
            public void visitStmt(Ast.Stmt stmt) {
                if (stmt instanceof Ast.Stmt.Let binding) {
                    names.addAll(boundNames(binding.pattern()));
                } else if (stmt instanceof Ast.Stmt.Fun fun) {
                    names.add(fun.function().name());
                    addParams(fun.function().params(), names);
                } else if (stmt instanceof Ast.Stmt.For loop) {
                    names.addAll(boundNames(loop.pattern()));
                }
            }
        }).program(statements);
        return List.copyOf(names);
    }

    private static void collectDeclared(Ast.Stmt statement, Set<String> names) {
        switch (statement) {
            case Ast.Stmt.ClassDecl cls -> {
                names.add(cls.name());
                for (Ast.Param param : cls.params()) {
                    names.add(param.name());
                }
                for (Ast.Stmt.Let field : cls.fields()) {
                    names.addAll(boundNames(field.pattern()));
                }
                for (Ast.Stmt.Fun method : cls.methods()) {
                    names.add(method.function().name());
                    addParams(method.function().params(), names);
                }
            }
            case Ast.Stmt.TypeDecl type -> {
                names.add(type.name());
                for (Ast.Param param : type.fields()) {
                    names.add(param.name());
                }
                for (Ast.Stmt.Fun method : type.methods()) {
                    names.add(method.function().name());
                    addParams(method.function().params(), names);
                }
            }
            case Ast.Stmt.EnumDecl enumType -> {
                names.add(enumType.name());
                names.addAll(enumType.constants());
            }
            case Ast.Stmt.Fun fun -> {
                names.add(fun.function().name());
                addParams(fun.function().params(), names);
            }
            case Ast.Stmt.Let binding -> names.addAll(boundNames(binding.pattern()));
            default -> {
                // Nothing bindable.
            }
        }
    }

    private static void addParams(List<Ast.Param> params, Set<String> names) {
        for (Ast.Param param : params) {
            names.add(param.name());
        }
    }

    private static List<String> boundNames(Ast.Pattern pattern) {
        List<String> out = new ArrayList<>(2);
        if (pattern instanceof Ast.Pattern.Named named) {
            out.add(named.name());
        } else if (pattern instanceof Ast.Pattern.Destructure destructured) {
            out.addAll(destructured.names());
        } else if (pattern instanceof Ast.Pattern.TypeOf typed) {
            if (typed.bind() != null && !typed.bind().isEmpty()) {
                out.add(typed.bind());
            }
        }
        return out;
    }

    // -- completion ----------------------------------------------------------

    /**
     * The completion candidates for a buffer at a caret, over the language's
     * keywords, the buffer's own names and the library.
     *
     * @param source the whole buffer
     * @param caret  the 0-based caret offset
     * @return ranked candidates, at most {@link #MAX_CANDIDATES}
     */
    public static List<String> complete(String source, int caret) {
        return complete(source, caret, List.of());
    }

    /**
     * The same, with the names the embedding host has bound.
     *
     * <p>A host passes what it gave its engine ({@code doc}, {@code args}, a
     * callback) so those complete exactly like the language's own globals; the
     * language cannot see them, which is why the parameter exists.</p>
     *
     * @param source      the whole buffer
     * @param caret       the 0-based caret offset
     * @param hostGlobals extra names the host bound, in priority order
     * @return ranked candidates, at most {@link #MAX_CANDIDATES}
     */
    public static List<String> complete(String source, int caret,
                                        Collection<String> hostGlobals) {
        if (source == null || source.isEmpty() || caret < 0 || caret > source.length()) {
            return List.of();
        }
        int at = Math.min(caret, source.length());
        String prefix = prefixAt(source, at);

        // After a dot the question is "what does this receiver answer to", and
        // only the names that fit the receiver's type belong in the list.
        int dot = at - prefix.length() - 1;
        if (dot >= 0 && source.charAt(dot) == '.' && !isInComment(source, dot)) {
            String receiver = prefixAt(source, dot);
            if (receiver.isEmpty()) {
                return List.of();
            }
            return rank(prefix, membersOf(receiver, source));
        }

        Set<String> pool = new LinkedHashSet<>();
        if (hostGlobals != null) {
            for (String name : hostGlobals) {
                if (name != null && !name.isBlank()) {
                    pool.add(name);
                }
            }
        }
        pool.addAll(vocabulary(source));
        pool.addAll(globals());
        pool.addAll(moduleNames());
        for (String alias : usedModules(source)) {
            pool.add(alias);
        }
        pool.addAll(keywords());
        return rank(prefix, pool);
    }

    /**
     * @return the identifier run immediately before {@code caret} (possibly empty)
     */
    public static String prefixAt(String source, int caret) {
        int start = Math.min(caret, source.length());
        int i = start;
        while (i > 0 && isWordChar(source.charAt(i - 1))) {
            i--;
        }
        return source.substring(i, start);
    }

    /**
     * The members a receiver answers to, as far as the buffer can prove it: a
     * {@code use std.<module>} alias, an enum's own name, {@code this} /
     * {@code super} inside a declared class, a binding whose value is a declared
     * type's constructor call, or a binding with a {@code : Type} annotation.
     *
     * @param receiver the name the dot follows
     * @param source   the whole buffer
     * @return the member names, empty when the receiver's type is not knowable
     */
    public static List<String> membersOf(String receiver, String source) {
        if (receiver == null || receiver.isEmpty()) {
            return List.of();
        }
        List<Ast.Stmt> statements = statements(source);

        // `std.json.parse` — the dotted spelling completes on the module list.
        if ("std".equals(receiver)) {
            return moduleNames();
        }
        // `fs.read` — the alias a `use std.fs` bound.
        String module = moduleFor(receiver, statements);
        if (module != null) {
            return moduleMembers(module);
        }
        if ("this".equals(receiver) || "super".equals(receiver)) {
            // The outline cannot know which class encloses the caret, and the
            // members of the buffer's own types are a better answer than nothing.
            Set<String> members = new LinkedHashSet<>();
            for (Ast.Stmt statement : statements) {
                collectMembers(statement, members);
            }
            return List.copyOf(members);
        }

        // An enum is named as itself: `Color.Red`.
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.Stmt.EnumDecl enumType
                    && enumType.name().equals(receiver)) {
                return enumType.constants();
            }
        }

        // A declared type's constructor call gives the binding a known owner.
        String owner = ownerOf(receiver, statements);
        if (owner != null) {
            List<String> members = new ArrayList<>();
            for (Ast.Stmt statement : statements) {
                if (statement instanceof Ast.Stmt.ClassDecl cls
                        && cls.name().equals(owner)) {
                    collectMembers(cls, members);
                } else if (statement instanceof Ast.Stmt.TypeDecl type
                        && type.name().equals(owner)) {
                    collectMembers(type, members);
                }
            }
            return members;
        }
        // An annotation is the author telling us the type: `let s: Str = ...`.
        String annotated = annotatedTypeOf(receiver, statements);
        if (annotated != null && VALUE_TYPES.contains(annotated)) {
            return valueMembers(valueTypeFamily(annotated));
        }
        return List.of();
    }

    // -- helpers -------------------------------------------------------------

    /** The names a value-type family contributes to {@code recv.} completion. */
    private static String valueTypeFamily(String annotation) {
        return switch (annotation) {
            case "Int", "Num", "Double" -> "Num";
            default -> annotation;
        };
    }

    private static void collectMembers(Ast.Stmt statement, Collection<String> out) {
        if (statement instanceof Ast.Stmt.ClassDecl cls) {
            for (Ast.Stmt.Fun method : cls.methods()) {
                out.add(method.function().name());
            }
            for (Ast.Stmt.Let field : cls.fields()) {
                out.addAll(boundNames(field.pattern()));
            }
        } else if (statement instanceof Ast.Stmt.TypeDecl type) {
            for (Ast.Stmt.Fun method : type.methods()) {
                out.add(method.function().name());
            }
            for (Ast.Param field : type.fields()) {
                out.add(field.name());
            }
        }
    }

    /**
     * @return the module name {@code receiver} stands for, or null when no {@code use}
     *         statement in the buffer bound it. Only a module the program actually
     *         asked for is offered, because a name the script never {@code use}d is a
     *         name the language will refuse at runtime.
     */
    private static String moduleFor(String receiver, List<Ast.Stmt> statements) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.Stmt.Use use) {
                List<String> segments = use.segments();
                if (segments.size() == 2 && segments.get(1).equals(receiver)) {
                    return segments.get(0) + "." + segments.get(1);
                }
                if (segments.size() == 1 && segments.get(0).equals(receiver)) {
                    return receiver;
                }
            }
        }
        return null;
    }

    /** @return the aliases every {@code use} statement in the buffer bound */
    private static Set<String> usedModules(String source) {
        Set<String> aliases = new LinkedHashSet<>();
        for (Ast.Stmt statement : statements(source)) {
            if (statement instanceof Ast.Stmt.Use use && !use.segments().isEmpty()) {
                aliases.add(use.segments().get(use.segments().size() - 1));
            }
        }
        return aliases;
    }

    /**
     * @return the declared type {@code receiver} was bound to by {@code let recv =
     *         Owner(...)} anywhere in the buffer, or null when its owner is unknown
     */
    private static String ownerOf(String receiver, List<Ast.Stmt> statements) {
        Binder binder = new Binder(receiver);
        new Ast.Walk(new Ast.Visitor() {
            @Override
            public void visitStmt(Ast.Stmt stmt) {
                binder.accept(stmt);
            }
        }).program(statements);
        return binder.owner;
    }

    /** The type name a {@code let recv: Type} annotation carries. */
    private static String annotatedTypeOf(String receiver, List<Ast.Stmt> statements) {
        Set<String> names = new TreeSet<>();
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.Stmt.Let binding
                    && binding.typeName() != null && !binding.typeName().isEmpty()
                    && boundNames(binding.pattern()).contains(receiver)) {
                names.add(binding.typeName());
            }
        }
        return names.isEmpty() ? null : names.iterator().next();
    }

    /** Collects the owner of {@code let recv = Owner(...)} in one walk. */
    private static final class Binder {
        private final String receiver;
        private String owner;

        Binder(String receiver) {
            this.receiver = receiver;
        }

        void accept(Ast.Stmt stmt) {
            if (stmt instanceof Ast.Stmt.Let binding
                    && binding.value() instanceof Ast.Call callee
                    && callee.callee() instanceof Ast.Name name
                    && boundNames(binding.pattern()).contains(receiver)) {
                owner = name.name();
            }
        }
    }

    /**
     * Puts the prefix's own matches first, then the rest, capped for a popup.
     *
     * @param prefix the text already typed (possibly empty)
     * @param pool   the candidates, in priority order
     * @return the ranked list, never more than {@link #MAX_CANDIDATES}
     */
    static List<String> rank(String prefix, Collection<String> pool) {
        // The pool's order is a priority order (the caller put the strongest signal
        // first), so it is captured once as a map instead of being rescanned per
        // comparison, which a Collection cannot even answer.
        Map<String, Integer> order = new HashMap<>();
        List<String> candidates = new ArrayList<>();
        for (String candidate : pool) {
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            if (order.putIfAbsent(candidate, order.size()) == null) {
                candidates.add(candidate);
            }
        }
        String lower = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        String exact = (prefix == null) ? "" : prefix;
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (lower.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        // An exact-case prefix match sorts first: typing `Print` means println, not
        // a buffer variable named `printer` that merely starts with the letters.
        out.sort((left, right) -> {
            boolean exactLeft = left.startsWith(exact);
            boolean exactRight = right.startsWith(exact);
            if (exactLeft != exactRight) {
                return exactLeft ? -1 : 1;
            }
            return Integer.compare(order.get(left), order.get(right));
        });
        return (out.size() <= MAX_CANDIDATES)
                ? out : new ArrayList<>(out.subList(0, MAX_CANDIDATES));
    }

    /**
     * @return the buffer's statements, or an empty list when it does not parse
     */
    private static List<Ast.Stmt> statements(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        try {
            Parser.Program program = Parser.parse(source, BUFFER);
            // A half-typed buffer is the normal case here, so its partial tree is
            // used as-is rather than discarded: the outline still shows what is
            // already complete, and an editor with an outline that vanishes on the
            // first keystroke of a declaration is worse than one that lags a line.
            return program.statements();
        } catch (RuntimeException broken) {
            return List.of();
        }
    }

    /**
     * @return true when the {@code dot} is inside a {@code //} comment, so
     *         {@code // see this.} does not open a completion popup
     */
    private static boolean isInComment(String source, int dot) {
        int lineStart = source.lastIndexOf('\n', dot) + 1;
        return source.substring(lineStart, dot).contains("//");
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static Runtime host() {
        Runtime existing = host;
        if (existing != null) {
            return existing;
        }
        synchronized (EditorServices.class) {
            if (host == null) {
                host = Mandela.engine().build();
            }
            return host;
        }
    }

    /**
     * Drops the cached engine, which only a test needs: the tables are constants, so
     * the engine survives an editor session.
     */
    static void resetHostForTesting() {
        synchronized (EditorServices.class) {
            host = null;
        }
    }

    /** @return the language's own name, for a tool's title text. */
    public static String languageName() {
        return Mandela.LANGUAGE_NAME;
    }

    /** @return the file extension a Mandela source uses, without the dot. */
    public static String fileExtension() {
        return Mandela.FILE_EXTENSION;
    }

    /**
     * The template text a "new file" action writes, so a fresh buffer starts with
     * something that runs.
     *
     * @return a short program, using the language's real syntax
     */
    public static Map<String, String> starterTemplates() {
        return Map.of(
                "script", """
                        #!/usr/bin/env mandela
                        // A Mandela script. Run it with `mandela run hello.mnd`.
                        println("Hello from Mandela")
                        """,
                "function", """
                        fun greet(name: Str) -> Str { "Hello, " + name }
                        println(greet("world"))
                        """,
                "class", """
                        class Counter(start: Int = 0) {
                            var total = start

                            fun bump(step: Int = 1) -> Int {
                                total += step
                                total
                            }
                        }

                        let c = Counter()
                        c.bump()
                        println(c.total)
                        """);
    }
}
