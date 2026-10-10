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
package org.jdesktop.lg3d.mandela.rt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.jdesktop.lg3d.mandela.code.Compiler;
import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.code.Disassembler;
import org.jdesktop.lg3d.mandela.lang.Ast;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.lang.Parser;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.Values;
import org.jdesktop.lg3d.mandela.vm.VM;

/**
 * One Mandela engine: the pair a host actually holds, a {@link VM} and the
 * {@link StandardLibrary} it asks questions of, wired to the host's console and
 * capability set.
 *
 * <p>This is the class behind every other door into the language &mdash; the CLI,
 * the REPL, the JSR-223 engine, an Espresso panel, a Web Browser page &mdash; and
 * it exists so those doors stay three-line wrappers. A host configures a
 * {@link Builder}, then either {@link #run(String)}s a program or {@link #call}s
 * one named function after a setup script has defined it. Nothing here decides
 * policy: {@link Capabilities} does that, and this class hands the same object to
 * the machine and to the library so a refusal reads the same from both.</p>
 *
 * <h2>Why one engine is not one run</h2>
 *
 * <p>The global table is deliberately retained between calls, which is what makes
 * a REPL session and an editor that re-runs a selection behave: {@code let x = 1}
 * at the top level compiles to a {@code DEFINE_GLOBAL}, so the binding outlives
 * the body that made it. A {@code run} resets the machine's instruction budget and
 * stack, not the names. A host that wants a clean slate builds a second
 * engine &mdash; cheap, since the library tables are read-only after construction
 * and may be shared through {@link Builder#library}.</p>
 *
 * <h2>Modules</h2>
 *
 * <p>{@code import} runs a file in a <em>child</em> engine that starts with a copy
 * of this engine's names and keeps whatever it binds. The child is a separate
 * machine, so a module cannot write back into the importer's globals, cannot
 * inherit a half-finished operand stack, and its budget is charged to the same
 * {@link Capabilities} the importer was given. Cycles are refused rather than
 * resolved by a half-built module map, because a language that answers
 * {@code undefined} for a circular import turns a typo into a runtime bug.</p>
 */
public final class Runtime {

    /** The name a snippet without a file is reported under. */
    public static final String STRING_SOURCE = "<mandela>";

    /** How deep an import chain may go before it is refused. */
    public static final int MAX_IMPORT_DEPTH = 32;

    private final Capabilities caps;
    private final StandardLibrary library;
    private final Map<String, Object> globals;
    private final VM machine;
    private final java.nio.file.Path importRoot;

    /** Files currently being imported, so a cycle is caught at the second level. */
    private final Set<java.nio.file.Path> loading = new HashSet<>();

    /** The console sinks the child engines of a module load write through. */
    private VM.Output childOut;
    private VM.Output childErr;

    /** The depth of the current import chain. */
    private int importDepth;

    private Runtime(Builder builder) {
        this.caps = builder.capabilities;
        this.globals = new LinkedHashMap<>(builder.globals);
        this.importRoot = builder.importRoot;
        this.library = (builder.library != null) ? builder.library
                : new StandardLibrary(builder.files);
        this.machine = new VM(caps, globals);
        this.machine.setOutput(builder.out, builder.err);
        this.childOut = builder.out;
        this.childErr = builder.err;
        this.machine.setBuiltins(library);
        // The library's names go in first, so anything the host passed wins over
        // them and a host may replace `println` with its own console.
        Map<String, Object> host = new LinkedHashMap<>(builder.globals);
        library.install(machine, globals, host);
        library.setImporter(this::loadModule);
    }

    /** @return a builder with a full-grant desktop engine */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A one-shot engine for a caller that has nothing to configure.
     *
     * <p>The capabilities are {@link Capabilities#console()} &mdash; stdout and
     * stderr and nothing else, never a file, a socket, a thread or the JVM. Use
     * {@link #builder()} the moment the run needs more than that.</p>
     *
     * @param source the program
     * @return the value of its last expression
     */
    public static Object evaluate(String source) {
        return builder().capabilities(Capabilities.console())
                .output(System.out::print).error(System.err::print)
                .build().run(source);
    }

    // -- running --------------------------------------------------------------

    /**
     * Parses, compiles and runs one program on this engine's names.
     *
     * @param source the program text
     * @return the value of the last expression, or null
     * @throws MandelaError when the script fails and nothing catches it
     * @throws LangException when the text does not parse or compile
     */
    public Object run(String source) {
        return run(source, STRING_SOURCE);
    }

    /**
     * Runs one program, reporting problems under the name the host chose.
     *
     * @param source     the program text
     * @param sourceName where it came from, in diagnostics and traces
     * @return the value of the last expression
     */
    public Object run(String source, String sourceName) {
        return run(compile(source, sourceName));
    }

    /**
     * Runs a body this engine compiled earlier.
     *
     * <p>Kept public because a host that compiles once and runs many times &mdash; a
     * template, a per-keystroke check, a saved Espresso run configuration &mdash;
     * should not have to re-parse the text on every pass. The names the body reads
     * are resolved when it was compiled, so anything bound after that is invisible
     * to it; compile last, run often.</p>
     *
     * @param body the compiled program
     * @return the value of its last expression
     */
    public Object run(CompiledFunction body) {
        return machine.run(body);
    }

    /**
     * Re-points the console after the engine was built.
     *
     * <p>The builder sets the sinks once, which is right for a CLI; a JSR-223
     * engine and a browser page get a different writer per call, so they need to
     * move the console without rebuilding the engine. Module children inherit the
     * new sinks, which is what makes {@code import}ed code print into the same
     * panel as its importer.</p>
     *
     * @param out where {@code print} and {@code println} go, or null for no console
     * @param err where {@code eprintln} and uncaught errors go, or null for none
     */
    public void setOutput(Consumer<String> out, Consumer<String> err) {
        this.childOut = (out == null) ? null : out::accept;
        this.childErr = (err == null) ? null : err::accept;
        this.machine.setOutput(this.childOut, this.childErr);
    }

    /**
     * Runs a file, using its path as the source name.
     *
     * @param file the script to read
     * @return the value of the last expression
     */
    public Object runFile(java.nio.file.Path file) {
        String text = readFile(file);
        return run(text, file.toString());
    }

    /**
     * Compiles without running, which is what {@code mandela check} and an
     * editor's on-save hook need.
     *
     * @param source     the program text
     * @param sourceName the name to report under
     * @return the body, ready to run
     */
    public CompiledFunction compile(String source, String sourceName) {
        Parser.Program program = Parser.parse(source, sourceName);
        if (program.hasErrors()) {
            throw firstError(program.diagnostics(), sourceName);
        }
        return Compiler.compile(program.statements(), sourceName, compileTimeGlobals());
    }

    /**
     * Reports what is wrong with a program, without throwing.
     *
     * <p>The editor calls this on every keystroke, so it is the one entry point
     * that must never raise: a parse failure becomes a diagnostic, a compile
     * failure becomes a diagnostic, and the caller gets the list either way.</p>
     *
     * @param source     the program text
     * @param sourceName the name to report under
     * @return the findings, empty when the program is sound
     */
    public List<Diagnostic> check(String source, String sourceName) {
        Parser.Program program = Parser.parse(source, sourceName);
        List<Diagnostic> found = new ArrayList<>(program.diagnostics());
        if (program.hasErrors()) {
            return found;
        }
        try {
            Compiler.Result result = Compiler.compileLenient(program.statements(),
                    sourceName, compileTimeGlobals());
            found.addAll(result.diagnostics());
        } catch (LangException failure) {
            found.add(failure.toDiagnostic());
        }
        return found;
    }

    /**
     * @param source the program text
     * @return the instruction stream, the form {@code mandela dump} prints
     */
    public String disassemble(String source) {
        return Disassembler.dump(compile(source, STRING_SOURCE));
    }

    // -- names ----------------------------------------------------------------

    /**
     * Publishes one host value or function under a name.
     *
     * <p>Call it before {@code run} for the best diagnostics: a name the compiler
     * knows is a name it can type-check and an editor can complete.</p>
     *
     * <p>An ordinary Java value is widened the way {@link HostValues} does, so
     * {@code bind("limit", 10)} means what it looks like and a Kotlin or Java lambda
     * becomes a callable the script can call. A value with no Mandela form &mdash; a
     * domain object &mdash; is refused here rather than surfacing three statements
     * later as an arithmetic error about a {@code Host}.</p>
     *
     * @param name  the name a script will read
     * @param value the value, or anything with an obvious Mandela form
     * @throws MandelaError when the value has no Mandela form
     */
    public void bind(String name, Object value) {
        Objects.requireNonNull(name, "name");
        globals.put(name, HostValues.hostValue(name, value));
    }

    /**
     * Reads back a name the script defined, after a run.
     *
     * @param name the global to look up
     * @return its value, or null when it was never bound
     */
    public Object get(String name) {
        return globals.get(name);
    }

    /**
     * Calls a script function by name from Java.
     *
     * <p>This is the seam an event handler uses: the setup script defines
     * {@code onOpen}, the host calls it when the window opens, and the arguments
     * cross in Mandela form.</p>
     *
     * @param name the global to call
     * @param args the arguments, in any form {@link Values#fromHost} understands
     * @return what the call returned
     * @throws MandelaError when the name is not callable or an argument has no
     *         Mandela form
     */
    public Object call(String name, Object... args) {
        Object target = globals.get(name);
        if (!(target instanceof Callable callable)) {
            if (target == null) {
                throw MandelaError.value("'" + name + "' is not defined in this script");
            }
            throw MandelaError.type("'" + name + "' is a " + Values.typeName(target)
                    + " and cannot be called");
        }
        return machine.invoke(callable, null, HostValues.hostArgs(name, args));
    }

    /** @return the live global table; the map is the engine's own, not a copy */
    public Map<String, Object> globals() {
        return globals;
    }

    /** @return the interpreter behind this engine, for a debugger or a profiler */
    public VM machine() {
        return machine;
    }

    /** @return the library this engine asks for members and modules */
    public StandardLibrary library() {
        return library;
    }

    /** @return the sandbox every side effect is measured against */
    public Capabilities capabilities() {
        return caps;
    }

    /** @return the frames now executing, outermost first, for a crash report */
    public List<String> trace() {
        return machine.trace();
    }

    /**
     * The frames a failure ran through on its way out of the last run.
     *
     * <p>{@link #trace()} answers the frames that are live <em>now</em>, which is
     * nothing once the machine has unwound &mdash; so a host that reports a crash
     * after catching it, the way the CLI does under {@code --trace}, has to ask for
     * the snapshot the machine kept instead.</p>
     *
     * @return the frames of the last uncaught failure, outermost first, or empty
     */
    public List<String> failureTrace() {
        return machine.failureTrace();
    }

    /**
     * The names a program on this engine may read without declaring them: the
     * library's globals, the host's bindings, and the {@code use} / {@code import}
     * hooks.
     *
     * <p>An editor builds its completion list and its "unknown name" squiggle from
     * exactly this set, which is why it is public: an embedder that adds a host
     * function should have its editor offer that function the moment the engine that
     * will run it exists.</p>
     *
     * @return a snapshot of the visible names, in no particular order
     */
    public Set<String> knownGlobals() {
        return new HashSet<>(compileTimeGlobals());
    }

    // -- the REPL -------------------------------------------------------------

    /**
     * Runs one REPL entry.
     *
     * <p>Bindings survive because a top-level {@code let} compiles to
     * {@code DEFINE_GLOBAL}; the instruction budget resets because each entry is
     * its own run. The caller prints the value it returns, and prints nothing when
     * the entry only declared something.</p>
     *
     * @param line the entry, which may span several statements
     * @return the value the entry produced, possibly null
     */
    public Object evalLine(String line) {
        return run(line, "<repl>");
    }

    /**
     * Whether one REPL entry is worth echoing. A run that produced no value at all
     * is silent, and a run that produced {@code null} prints {@code null}: the
     * distinction is the difference between a declaration and an expression.
     *
     * @param source the entry
     * @param name   the name to report under
     * @return the value, or {@link #REPL_SILENT} when nothing should be printed
     */
    public Object evalEntry(String source, String name) {
        Parser.Program program = Parser.parse(source, name);
        if (program.hasErrors()) {
            throw firstError(program.diagnostics(), name);
        }
        List<Ast.Stmt> statements = program.statements();
        if (statements.isEmpty()) {
            return REPL_SILENT;
        }
        // A declaration form has no value to show, so the REPL keeps quiet about it
        // instead of printing a misleading `null`.
        if (isDeclaration(statements.get(statements.size() - 1))) {
            run(source, name);
            return REPL_SILENT;
        }
        return run(source, name);
    }

    /** The marker {@link #evalEntry} returns when the caller should print nothing. */
    public static final Object REPL_SILENT = new Object();

    private static boolean isDeclaration(Ast.Stmt statement) {
        return statement instanceof Ast.Stmt.Let
                || statement instanceof Ast.Stmt.Fun
                || statement instanceof Ast.Stmt.TypeDecl
                || statement instanceof Ast.Stmt.ClassDecl
                || statement instanceof Ast.Stmt.EnumDecl
                || statement instanceof Ast.Stmt.Import
                || statement instanceof Ast.Stmt.Use
                || statement instanceof Ast.Stmt.Assign;
    }

    // -- modules --------------------------------------------------------------

    /**
     * Loads a Mandela file as a module and returns its exported names.
     *
     * <p>The path is resolved through {@link Capabilities#resolve}, so a module can
     * only be imported from inside the directory the host granted, and the
     * {@code FILE_READ} grant is asserted first: a browser page that reaches
     * {@code import} is told no before a single byte is read.</p>
     *
     * @param path the literal path from the {@code import} statement
     * @return a map of the names the module exported
     */
    public MandelaMap loadModule(org.jdesktop.lg3d.mandela.vm.Machine from,
                                 String path) {
        caps.require(Capabilities.Grant.FILE_READ, "import a module");
        java.nio.file.Path file = resolveImport(path);
        if (importDepth >= MAX_IMPORT_DEPTH) {
            throw MandelaError.limit("the import chain is deeper than "
                    + MAX_IMPORT_DEPTH + " levels; something is circular");
        }
        if (!loading.add(file)) {
            throw MandelaError.value("'" + path + "' imports itself, directly or"
                    + " through a cycle; Mandela refuses circular imports");
        }
        importDepth++;
        try {
            return runModule(file, path);
        } finally {
            importDepth--;
            loading.remove(file);
        }
    }

    /**
     * Runs a module body in a child engine and hands back its exports.
     *
     * @param file the module's file
     * @param path the name the importer wrote, for diagnostics
     * @return the exported names
     */
    private MandelaMap runModule(java.nio.file.Path file, String path) {
        String source = readFile(file);
        Map<String, Object> childGlobals = new LinkedHashMap<>(globals);
        VM child = new VM(caps, childGlobals);
        child.setOutput(childOut, childErr);
        child.setBuiltins(library);
        // A module starts from the importer's names, which is what lets a shared
        // helper library work, but the child's own bindings never travel back.
        Parser.Program program = Parser.parse(source, file.toString());
        if (program.hasErrors()) {
            throw firstError(program.diagnostics(), file.toString());
        }
        Set<String> known = new HashSet<>(childGlobals.keySet());
        known.add(Compiler.USE_HOOK);
        known.add(Compiler.IMPORT_HOOK);
        Compiler.Result result = Compiler.compileModule(program.statements(),
                file.toString(), known);
        child.run(result.main());
        MandelaMap exported = new MandelaMap(Math.max(4, result.exports().size()));
        if (result.exports().isEmpty()) {
            // A module with no `export` publishes nothing, and saying so beats a
            // silent empty map that the importer will index and puzzle over.
            throw MandelaError.value("'" + path + "' exports nothing; add 'export'"
                    + " to the names it means to share");
        }
        for (String name : result.exports()) {
            if (!childGlobals.containsKey(name)) {
                throw MandelaError.value("'" + path + "' exports '" + name
                        + "', which the module never bound");
            }
            exported.put(name, childGlobals.get(name));
        }
        return exported;
    }

    private java.nio.file.Path resolveImport(String path) {
        String wanted = path;
        if (!wanted.endsWith(".mnd")) {
            wanted = wanted + ".mnd";
        }
        if (importRoot != null) {
            java.nio.file.Path direct = importRoot.resolve(wanted).normalize();
            if (java.nio.file.Files.exists(direct)) {
                return direct;
            }
        }
        return caps.resolve(wanted, "import module '" + path + "'");
    }

    private static MandelaError firstError(List<Diagnostic> diagnostics,
                                           String sourceName) {
        for (Diagnostic entry : diagnostics) {
            if (entry.isError()) {
                return MandelaError.of("SyntaxError", entry.sourceName() + ":" + entry.line()
                        + ":" + entry.column() + ": " + entry.message());
            }
        }
        return MandelaError.of("SyntaxError", sourceName + ": the program did not parse");
    }

    /** @return every name the compiler may accept without a declaration */
    private Set<String> compileTimeGlobals() {
        Set<String> known = new HashSet<>(globals.keySet());
        known.add(Compiler.USE_HOOK);
        known.add(Compiler.IMPORT_HOOK);
        return known;
    }

    private String readFile(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException failure) {
            throw MandelaError.io("cannot read '" + file + "': " + failure.getMessage());
        }
    }

    /** The builder {@link #builder()} returns. */
    public static final class Builder {

        // The safe end, not the CLI end: a host that forgets to name a sandbox gets
        // a console and no files, which is the mistake that cannot hurt anyone.
        private Capabilities capabilities = Capabilities.console();
        private Files files;
        private java.nio.file.Path importRoot;
        private final Map<String, Object> globals = new LinkedHashMap<>();
        private StandardLibrary library;
        private VM.Output out;
        private VM.Output err;

        private Builder() {
        }

        /**
         * Sets the sandbox, and adopts the file root it carries.
         *
         * <p>The default is {@link Capabilities#console()}. A command line that runs
         * its own author's script says so with {@link Capabilities#open()}.</p>
         *
         * <p>A root that arrives with the grants becomes the engine's filesystem, in
         * this method and nowhere else: the path checks are made against
         * {@link Capabilities#fileRoot()}, so a host that granted a directory and then
         * had to name it a second time is a host that will forget and hand out a
         * sandbox {@code std.fs} cannot open. A caller that wants a different view
         * calls {@link #files(java.nio.file.Path)} afterwards.</p>
         *
         * @param caps the grants, budgets and file root for every run
         * @return this builder
         */
        public Builder capabilities(Capabilities caps) {
            this.capabilities = Objects.requireNonNull(caps, "caps");
            if (caps.fileRoot() != null) {
                this.files = new Files(caps.fileRoot());
                this.importRoot = caps.fileRoot();
            }
            return this;
        }

        /**
         * Gives the engine a filesystem, which is what {@code std.fs} reads and
         * writes through &mdash; always after the capabilities have said yes.
         *
         * @param root the directory to confine files to, or null for no filesystem
         * @return this builder
         */
        public Builder files(java.nio.file.Path root) {
            this.files = (root == null) ? null : new Files(root);
            this.importRoot = root;
            return this;
        }

        /**
         * Where a bare {@code import "name"} looks before the granted root.
         *
         * @param root the module directory, or null to use only the sandbox root
         * @return this builder
         */
        public Builder importRoot(java.nio.file.Path root) {
            this.importRoot = root;
            return this;
        }

        /**
         * Publishes a host value or function before the first compile.
         *
         * <p>Values are widened the way {@link HostValues} describes, so a
         * {@code Map} of configuration or a {@code List} of rows can be passed
         * through as written, and a lambda arrives as something the script can call.
         * A Java object with no Mandela form is refused.</p>
         *
         * @param name  the name a script will read
         * @param value the value
         * @return this builder
         */
        public Builder bind(String name, Object value) {
            this.globals.put(name, HostValues.hostValue(name, value));
            return this;
        }

        /**
         * Publishes several names at once, each converted as {@link #bind} does.
         *
         * @param values the names and their values
         * @return this builder
         */
        public Builder bindAll(Map<String, Object> values) {
            if (values != null) {
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    this.globals.put(entry.getKey(), HostValues.hostValue(
                            entry.getKey(), entry.getValue()));
                }
            }
            return this;
        }

        /**
         * Shares a library between engines, which is what a host that builds one
         * engine per document does to keep the member tables warm.
         *
         * <p>The member tables are read-only after construction and safe to share.
         * The one thing that is not is {@code import}: a library has a single
         * importer, so the engine built here last is the one whose module paths a
         * shared library resolves. A host that imports files from more than one
         * engine gives each engine its own library.</p>
         *
         * @param table the library, or null to build this engine's own
         * @return this builder
         */
        public Builder library(StandardLibrary table) {
            this.library = table;
            return this;
        }

        /**
         * Sets the console sink. It receives characters, not lines: {@code println}
         * ends its own lines, so a host that wants whole lines splits them itself.
         *
         * @param sink where {@code print} and {@code println} go
         * @return this builder
         */
        public Builder output(Consumer<String> sink) {
            this.out = (sink == null) ? null : sink::accept;
            return this;
        }

        /**
         * Sets the error sink, in the same terms as {@link #output}.
         *
         * @param sink where {@code eprintln} and uncaught errors go
         * @return this builder
         */
        public Builder error(Consumer<String> sink) {
            this.err = (sink == null) ? null : sink::accept;
            return this;
        }

        /** @return the wired-up engine */
        public Runtime build() {
            return new Runtime(this);
        }
    }
}
