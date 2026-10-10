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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * Mandela as a {@code javax.script} engine, so a host that already embeds one
 * script engine gets a second without learning a new API.
 *
 * <p>The class is a translation layer, not a second implementation: one
 * {@link Runtime} does the parsing, compiling and running, and everything here
 * converts the JSR-223 shapes &mdash; {@link Bindings}, {@link ScriptContext},
 * {@link ScriptException} &mdash; into the Mandela shapes they correspond to. Two
 * engines would drift, and the desktop's own hosts (Espresso, the Web Browser)
 * drive a {@link Runtime} directly, so what a third-party integration sees through
 * JSR-223 is exactly what the desktop runs.</p>
 *
 * <h2>What one {@code eval} does</h2>
 *
 * <ol>
 *   <li>announces the engine's own attributes into the engine scope, as every
 *       compliant engine does;</li>
 *   <li>pushes the engine and global {@link Bindings} into the runtime's globals,
 *       each converted with {@link Interop#toMandela};</li>
 *   <li>re-points the console at {@link ScriptContext#getWriter()} and
 *       {@link ScriptContext#getErrorWriter()}, so {@code println} lands where the
 *       host asked for it;</li>
 *   <li>compiles, then runs: a {@link LangException} or {@link MandelaError}
 *       becomes a {@link ScriptException} carrying the real file, line and column,
 *       which is what lets an IDE gutter show it;</li>
 *   <li>writes back into the engine scope, in plain Java form, every name the run
 *       created, replaced or received as a binding, and returns the program's last
 *       value through {@link Interop#toJava}.</li>
 * </ol>
 *
 * <h2>What a binding cannot be</h2>
 *
 * <p>A binding with no Mandela form &mdash; an arbitrary POJO, which
 * {@link Interop} refuses on purpose &mdash; stays on the host's side of the
 * window: {@code engine.get(key)} still returns it and the script sees an
 * undefined name. Skipping it quietly beats throwing at the boundary for a value
 * the script never mentions, and beats reflecting on it, which is how a sandbox
 * gets bypassed.</p>
 *
 * <h2>Sandbox and threads</h2>
 *
 * <p>The default engine is {@link Capabilities#console()}: a console, no files, no
 * environment, no network, no threads, no JVM. A host that means more passes its
 * own {@link Capabilities}, or the {@link Runtime} it already configured, to the
 * constructor. Every entry point synchronizes on the engine, which is the
 * single-threaded serial model JSR-223 assumes; a host that wants concurrency
 * builds one engine per thread, which is cheap.</p>
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * ScriptEngine engine = new ScriptEngineManager().getEngineByName("mandela");
 * engine.put("prices", List.of(12.5, 40.0));
 * engine.put("rate", 0.05);
 * Object total = engine.eval("""
 *         let tax = { v -> v * rate }
 *         prices.map(tax).sum()
 *         """);
 * // total is a Double, and ((Invocable) engine).invokeFunction("tax", 9.0) works
 * }</pre>
 *
 * @see Mandela the facade this engine is registered under
 * @see MandelaScriptEngineFactory the discovery half of the pair
 */
public final class MandelaScriptEngine extends AbstractScriptEngine
        implements Compilable, Invocable {

    /** The name a host looks this engine up by. */
    public static final String NAME = "mandela";

    /** The engine name shown in an about box or a version string. */
    public static final String ENGINE_NAME = Mandela.LANGUAGE_NAME;

    /**
     * The binding key that points back at the engine, which the {@code javax.script}
     * API documents but does not name with a constant.
     */
    private static final String SCRIPT_ENGINE = "javax.script.scriptengine";

    /** The keys JSR-223 itself owns; they are never pushed into a script. */
    private static final Set<String> RESERVED = Set.of(
            SCRIPT_ENGINE, ScriptEngine.ENGINE, ScriptEngine.ENGINE_VERSION,
            ScriptEngine.NAME, ScriptEngine.LANGUAGE, ScriptEngine.LANGUAGE_VERSION,
            ScriptEngine.ARGV, ScriptEngine.FILENAME);

    private final ScriptEngineFactory factory;
    private final Runtime runtime;

    /**
     * Builds the default engine: a console and no files, which is the sandbox a
     * generic JSR-223 host should get until it asks for more, and the standard
     * library the {@code mandela} command line uses.
     *
     * @param factory the factory that asked for this engine, kept for
     *                {@link #getFactory()}
     */
    public MandelaScriptEngine(ScriptEngineFactory factory) {
        this(factory, Capabilities.console());
    }

    /**
     * Builds an engine over a sandbox the host chose.
     *
     * @param factory the factory that asked for this engine
     * @param caps    the grants and budgets every run is measured against
     */
    public MandelaScriptEngine(ScriptEngineFactory factory, Capabilities caps) {
        this(factory, Runtime.builder().capabilities(caps).build());
    }

    /**
     * Wraps an engine the host already configured.
     *
     * <p>This is the constructor Espresso and the Web Browser use: they hold a
     * {@link Runtime} with their own capabilities, console and host functions, and
     * they add the JSR-223 door to it rather than build a second engine that would
     * keep a separate set of names from the one their panels show.</p>
     *
     * @param factory the factory that asked for this engine
     * @param runtime the engine to drive, reused as-is
     */
    public MandelaScriptEngine(ScriptEngineFactory factory, Runtime runtime) {
        super();
        this.factory = Objects.requireNonNull(factory, "factory");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        announce(getBindings(ScriptContext.ENGINE_SCOPE));
    }

    /** @return the engine behind every call on this one */
    public Runtime runtime() {
        return runtime;
    }

    /** @return the sandbox the runtime enforces */
    public Capabilities capabilities() {
        return runtime.capabilities();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

    /**
     * A fresh engine scope for one call, which is how a host keeps two evaluations
     * from seeing each other's names while sharing this engine.
     *
     * @return an empty, modifiable binding map
     */
    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    /**
     * The names a program on this engine may read without declaring them: the
     * library's globals plus every binding and host function pushed so far.
     *
     * <p>An editor reads this for completion. It is why binding a host function
     * before compiling matters &mdash; a name the compiler knows is a name the
     * editor can offer.</p>
     *
     * @return a snapshot of the visible names
     */
    public Set<String> knownGlobals() {
        return runtime.knownGlobals();
    }

    // -- javax.script entry points -------------------------------------------

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        Objects.requireNonNull(script, "script");
        return execute(new Request(script, checked(context), sourceNameOf(context),
                null, false));
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        Objects.requireNonNull(reader, "reader");
        ScriptContext valid = checked(context);
        return execute(new Request(drain(reader), valid, sourceNameOf(valid),
                null, false));
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        Objects.requireNonNull(script, "script");
        // The run below stops after compiling. Bindings are pushed first because
        // the compiler resolves names: a host that puts a value and then compiles
        // gets a body that can see it, and a host that compiles first gets its
        // error at compile time, which is the only useful moment for it.
        return (CompiledScript) execute(new Request(script, checked(getContext()),
                sourceNameOf(getContext()), null, true));
    }

    @Override
    public CompiledScript compile(Reader reader) throws ScriptException {
        Objects.requireNonNull(reader, "reader");
        return compile(drain(reader));
    }

    /** Runs one previously compiled body against one context's bindings. */
    Object evalCompiled(MandelaCompiledScript body, ScriptContext context)
            throws ScriptException {
        ScriptContext valid = checked(context);
        return execute(new Request(body.text(), valid, sourceNameOf(valid),
                body.body(), false));
    }

    /**
     * The one path every call takes.
     *
     * @param request what to run, where, and under what name
     * @return the program's value in Java form, or the compiled script when
     *         {@link Request#compileOnly} asked to stop at the compiler
     */
    private Object execute(Request request) throws ScriptException {
        synchronized (this) {
            Bindings engineScope = request.context.getBindings(ScriptContext.ENGINE_SCOPE);
            Bindings globalScope = request.context.getBindings(ScriptContext.GLOBAL_SCOPE);
            announce(engineScope);
            Map<String, Object> pushed = new LinkedHashMap<>();
            pushBindings(globalScope, pushed);
            pushBindings(engineScope, pushed);
            Map<String, Object> before = new LinkedHashMap<>(runtime.globals());
            routeConsole(request.context);
            CompiledFunction body;
            try {
                body = (request.precompiled != null) ? request.precompiled
                        : runtime.compile(request.script, request.sourceName);
            } catch (MandelaError | LangException refused) {
                throw toScriptException(refused, request.sourceName);
            }
            if (request.compileOnly) {
                return new MandelaCompiledScript(this, request.script, body);
            }
            Object value;
            try {
                value = runtime.run(body);
            } catch (MandelaError | LangException failure) {
                // Names the run did define before failing are still worth handing
                // back: a host that catches the exception may want to inspect them.
                pullBack(engineScope, pushed, before);
                flush(request.context);
                throw toScriptException(failure, request.sourceName);
            }
            pullBack(engineScope, pushed, before);
            flush(request.context);
            return forHost(value);
        }
    }

    // -- bindings -------------------------------------------------------------

    @Override
    public void put(String key, Object value) {
        super.put(key, value);
        if (key == null || RESERVED.contains(key)) {
            return;
        }
        try {
            runtime.bind(key, Interop.toMandela(value));
        } catch (RuntimeException refused) {
            // Host-side only, as the class javadoc explains: the binding stays in
            // the scope map for the host to read, and the script has no name to
            // reach it with.
        }
    }

    @Override
    public Object get(String key) {
        Object fromScope = super.get(key);
        if (fromScope != null) {
            return fromScope;
        }
        // A name only the script knows is still worth reading from Java, so a host
        // can `eval("let version = \"1.0\"")` and then `get("version")` without
        // asking for it in the bindings first.
        return forHost(runtime.get(key));
    }

    /**
     * The form a value takes on the host's side of the window.
     *
     * <p>Data reads as Java &mdash; a list becomes an {@code ArrayList}, a map a
     * {@code LinkedHashMap}. A script's class instance stays itself, because the
     * reason a host wants an instance is to call its methods through
     * {@link #invokeMethod} or an interface proxy, and a field map has none. Use
     * {@link Interop#toJava} when the host wants the data form of either.</p>
     */
    private static Object forHost(Object value) {
        return (value instanceof MandelaInstance) ? value : Interop.toJava(value);
    }

    /**
     * Copies one scope's bindings into the engine's globals.
     *
     * @param scope  the bindings, possibly null
     * @param pushed receives each name to the Mandela value actually bound, so
     *               {@link #pullBack} can tell a host binding from a script name
     */
    private void pushBindings(Bindings scope, Map<String, Object> pushed) {
        if (scope == null) {
            return;
        }
        for (String key : new ArrayList<>(scope.keySet())) {
            if (key == null || RESERVED.contains(key)) {
                continue;
            }
            Object value;
            try {
                value = Interop.toMandela(scope.get(key));
            } catch (RuntimeException refused) {
                continue;
            }
            runtime.bind(key, value);
            pushed.put(key, value);
        }
    }

    /**
     * Writes the script's names back into the engine scope, in Java form.
     *
     * <p>A name crosses when the run created it, when it came in as a binding (its
     * container may have been mutated in place, so the host's copy is stale), or
     * when the script replaced the value that was there. Everything else &mdash;
     * the library's {@code println}, a module map from an earlier run &mdash; stays
     * out of the host's map, which is what keeps {@code engine.getBindings(..)}
     * readable after a session.</p>
     *
     * <p>Functions do not cross as values either: a script function is reached with
     * {@link #invokeFunction} or {@link #getInterface}, because a {@link Callable}
     * in the bindings looks like an object the host could hold but not call. A
     * script's list or map does cross as a {@code java.util} copy; its class
     * instance crosses as itself, which is what keeps its methods callable.</p>
     *
     * @param scope    the engine scope to write into, possibly null or immutable
     * @param pushed   the names that came in as bindings for this run
     * @param before   the globals as they stood before the run
     */
    private void pullBack(Bindings scope, Map<String, Object> pushed,
                          Map<String, Object> before) {
        if (scope == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : runtime.globals().entrySet()) {
            String key = entry.getKey();
            if (key == null || RESERVED.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            boolean created = !before.containsKey(key);
            boolean rebound = before.get(key) != value;
            if (!created && !rebound && !pushed.containsKey(key)) {
                continue;
            }
            if (value instanceof Callable) {
                continue;
            }
            try {
                scope.put(key, forHost(value));
            } catch (RuntimeException readOnly) {
                // An unmodifiable scope is the host's choice, and a run that
                // succeeded is not invalidated by a name it cannot receive.
            }
        }
    }

    // -- console --------------------------------------------------------------

    /**
     * Points the script's {@code print} and {@code println} at the context's
     * writers, for this call only.
     *
     * <p>A null writer means the host gave the engine nowhere to write, and the
     * machine then fails a print the way it fails a missing capability &mdash;
     * loudly, at the statement, rather than discarding the text.</p>
     */
    private void routeConsole(ScriptContext context) {
        runtime.setOutput(sink(context.getWriter()), sink(context.getErrorWriter()));
    }

    private static Consumer<String> sink(Writer target) {
        if (target == null) {
            return null;
        }
        return text -> {
            try {
                target.write(text);
            } catch (IOException broken) {
                throw MandelaError.io("the host's console refused a write: "
                        + broken.getMessage());
            }
        };
    }

    private static void flush(ScriptContext context) {
        tryFlush(context.getWriter());
        tryFlush(context.getErrorWriter());
    }

    private static void tryFlush(Writer target) {
        if (target == null) {
            return;
        }
        try {
            target.flush();
        } catch (IOException ignored) {
            // A writer that cannot flush already had its chance to fail a write;
            // trying again would only change which exception the host sees.
        }
    }

    // -- Invocable ------------------------------------------------------------

    @Override
    public Object invokeFunction(String name, Object... args)
            throws ScriptException, NoSuchMethodException {
        Objects.requireNonNull(name, "name");
        synchronized (this) {
            Object target = runtime.globals().get(name);
            if (target == null) {
                throw new NoSuchMethodException("'" + name
                        + "' is not defined by any script run on this engine");
            }
            if (!(target instanceof Callable)) {
                throw new NoSuchMethodException("'" + name + "' is a "
                        + Values.typeName(target) + ", not a function");
            }
            try {
                return forHost(runtime.call(name, argvOf(args)));
            } catch (MandelaError | LangException failure) {
                throw toScriptException(failure, Runtime.STRING_SOURCE);
            }
        }
    }

    @Override
    public Object invokeMethod(Object self, String name, Object... args)
            throws ScriptException, NoSuchMethodException {
        Objects.requireNonNull(name, "name");
        synchronized (this) {
            Callable method = methodOf(self, name);
            if (method == null) {
                throw new NoSuchMethodException("a " + Values.typeName(self)
                        + " has no method '" + name + "'");
            }
            try {
                return forHost(runtime.machine().invoke(method, self, argvOf(args)));
            } catch (MandelaError | LangException failure) {
                throw toScriptException(failure, Runtime.STRING_SOURCE);
            }
        }
    }

    private static Object[] argvOf(Object... args) {
        return (args == null || args.length == 0) ? new Object[0]
                : Interop.toMandelaAll(args).toArray();
    }

    /**
     * Finds the callable a method name means on one script value.
     *
     * <p>An instance's method comes from its class table, which is how inheritance
     * is already resolved for the script itself; a field holding a function works
     * too, because a record may carry a closure, and a map with a callable value is
     * the hand-built version of the same thing. A Java object answers null: there
     * is no reflective method call in this language, on either side of the
     * window.</p>
     */
    private static Callable methodOf(Object self, String name) {
        if (self instanceof MandelaInstance instance) {
            MandelaClass owner = instance.owner();
            Callable declared = (owner == null) ? null : owner.lookup(name);
            if (declared != null) {
                return declared;
            }
            Object field = instance.get(name);
            return (field instanceof Callable callable) ? callable : null;
        }
        if (self instanceof MandelaMap map) {
            Object held = map.get(name);
            return (held instanceof Callable callable) ? callable : null;
        }
        if (self instanceof Map<?, ?> hostMap) {
            Object held = hostMap.get(name);
            return (held instanceof Callable callable) ? callable : null;
        }
        return null;
    }

    @Override
    public <T> T getInterface(Class<T> cl) {
        return interfaceOver(null, cl);
    }

    @Override
    public <T> T getInterface(Object self, Class<T> cl) {
        return interfaceOver(self, cl);
    }

    /**
     * Adapts script functions to a Java interface with a dynamic proxy.
     *
     * <p>This is the call that makes a script a plug-in: the host owns an interface
     * and the script supplies the methods, so nothing is compiled against anything.
     * The answer is null when the class is not an interface or one of its methods
     * has no script function behind it &mdash; a half-built proxy that throws three
     * clicks later is worse than no proxy, and null is the answer the
     * {@link Invocable} contract offers for "cannot".</p>
     *
     * @param self the script value whose methods to use, or null for the globals
     * @param cl   the interface to implement
     * @return the proxy, or null when it cannot be honoured
     */
    private <T> T interfaceOver(Object self, Class<T> cl) {
        if (cl == null || !cl.isInterface()) {
            return null;
        }
        for (Method method : cl.getMethods()) {
            if (method.getDeclaringClass() == Object.class
                    || Modifier.isStatic(method.getModifiers())
                    || method.isDefault()) {
                continue;
            }
            boolean has = (self == null)
                    ? runtime.globals().get(method.getName()) instanceof Callable
                    : methodOf(self, method.getName()) != null;
            if (!has) {
                return null;
            }
        }
        Object proxy = Proxy.newProxyInstance(classLoaderOf(cl),
                new Class<?>[] { cl }, new ScriptHandler(self));
        return cl.cast(proxy);
    }

    private static ClassLoader classLoaderOf(Class<?> cl) {
        ClassLoader loader = cl.getClassLoader();
        return (loader != null) ? loader : ClassLoader.getSystemClassLoader();
    }

    /** Routes one interface method to the script function of the same name. */
    private final class ScriptHandler implements InvocationHandler {

        private final Object self;

        ScriptHandler(Object self) {
            this.self = self;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                // toString, hashCode and equals belong to the proxy; a script that
                // means them defines them on its own object.
                switch (name) {
                    case "hashCode":
                        return Integer.valueOf(System.identityHashCode(proxy));
                    case "equals":
                        return Boolean.valueOf(proxy == args[0]);
                    default:
                        return "MandelaScriptProxy(" + ENGINE_NAME + ")";
                }
            }
            Object[] argv = (args == null) ? new Object[0] : args;
            Object result = (self == null)
                    ? invokeFunction(name, argv)
                    : invokeMethod(self, name, argv);
            Class<?> returns = method.getReturnType();
            if (returns == void.class || returns == Void.class) {
                return null;
            }
            return Interop.toJava(result, returns);
        }
    }

    // -- plumbing -------------------------------------------------------------

    /** Puts the engine's own attributes into one scope, as a compliant engine must. */
    private void announce(Bindings scope) {
        if (scope == null) {
            return;
        }
        putIfAbsent(scope, SCRIPT_ENGINE, this);
        putIfAbsent(scope, ScriptEngine.ENGINE, ENGINE_NAME);
        putIfAbsent(scope, ScriptEngine.ENGINE_VERSION, Mandela.IMPLEMENTATION_VERSION);
        putIfAbsent(scope, ScriptEngine.NAME, firstOf(factory.getNames(), NAME));
        putIfAbsent(scope, ScriptEngine.LANGUAGE, Mandela.LANGUAGE_NAME);
        putIfAbsent(scope, ScriptEngine.LANGUAGE_VERSION, Mandela.LANGUAGE_VERSION);
    }

    private static void putIfAbsent(Bindings scope, String key, Object value) {
        if (scope.get(key) != null) {
            return;
        }
        try {
            scope.put(key, value);
        } catch (RuntimeException readOnly) {
            // The attributes are a courtesy to the host, not a precondition of the
            // run; an immutable scope simply does not receive them.
        }
    }

    private static String firstOf(List<String> values, String fallback) {
        return (values == null || values.isEmpty()) ? fallback : values.get(0);
    }

    /**
     * @param context the call's context
     * @return the name diagnostics should use: the host's filename if it set one,
     *         otherwise the runtime's snippet name
     */
    private static String sourceNameOf(ScriptContext context) {
        Bindings scope = (context == null) ? null : context.getBindings(
                ScriptContext.ENGINE_SCOPE);
        Object named = (scope == null) ? null : scope.get(ScriptEngine.FILENAME);
        return (named instanceof String text && !text.isBlank()) ? text
                : Runtime.STRING_SOURCE;
    }

    /**
     * Rejects a context the engine cannot run against, in the terms JSR-223 uses:
     * an argument problem is an {@link IllegalArgumentException}, not a script
     * failure.
     */
    private static ScriptContext checked(ScriptContext context) {
        if (context == null || context.getBindings(ScriptContext.ENGINE_SCOPE) == null) {
            throw new IllegalArgumentException("a ScriptContext with engine-scope"
                    + " bindings is required");
        }
        return context;
    }

    /**
     * Turns a language or runtime failure into the checked exception JSR-223 hosts
     * catch, keeping the position so an editor can show it in a gutter.
     */
    private static ScriptException toScriptException(Throwable failure, String fallback) {
        if (failure instanceof LangException language) {
            Diagnostic found = language.toDiagnostic();
            return new ScriptException(found.message(), found.sourceName(),
                    found.line(), found.column());
        }
        if (failure instanceof MandelaError error) {
            String where = (error.sourceName() == null || error.sourceName().isBlank())
                    ? fallback : error.sourceName();
            ScriptException out = new ScriptException(error.kind() + ": " + error.getMessage(),
                    where, Math.max(0, error.line()), 0);
            if (error.getCause() != null) {
                out.initCause(error.getCause());
            }
            return out;
        }
        ScriptException other = new ScriptException(String.valueOf(failure.getMessage()));
        other.initCause(failure);
        return other;
    }

    private static String drain(Reader reader) throws ScriptException {
        StringBuilder text = new StringBuilder(1024);
        char[] chunk = new char[4096];
        try {
            int read;
            while ((read = reader.read(chunk)) > 0) {
                text.append(chunk, 0, read);
            }
        } catch (IOException broken) {
            throw new ScriptException("the script could not be read: "
                    + broken.getMessage());
        }
        return text.toString();
    }

    // -- compiled scripts -----------------------------------------------------

    /** A body compiled once and runnable against many contexts. */
    static final class MandelaCompiledScript extends CompiledScript {

        private final MandelaScriptEngine engine;
        private final String text;
        private final CompiledFunction body;

        MandelaCompiledScript(MandelaScriptEngine engine, String text,
                              CompiledFunction body) {
            this.engine = engine;
            this.text = text;
            this.body = body;
        }

        @Override
        public Object eval() throws ScriptException {
            return engine.evalCompiled(this, engine.getContext());
        }

        @Override
        public Object eval(ScriptContext context) throws ScriptException {
            return engine.evalCompiled(this, context);
        }

        @Override
        public ScriptEngine getEngine() {
            return engine;
        }

        /** @return the body to run, already resolved against compile-time names */
        CompiledFunction body() {
            return body;
        }

        /** @return the source text, kept so a host can show what a script ran */
        String text() {
            return text;
        }
    }

    /** One call's worth of arguments, so {@link #execute} has a single shape. */
    private record Request(String script, ScriptContext context, String sourceName,
                           CompiledFunction precompiled, boolean compileOnly) {

        Request {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(script, "script");
        }
    }
}
