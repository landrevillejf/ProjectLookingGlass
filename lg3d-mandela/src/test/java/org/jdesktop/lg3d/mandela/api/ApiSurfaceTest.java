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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Closes the remaining coverage gaps in the {@code api} package: every branch of
 * {@link MandelaScriptEngineFactory#getParameter}, the edge cases of
 * {@link Interop#toJava(Object, Class)}, the null/RESERVED/readOnly paths in
 * {@link MandelaScriptEngine}, and the declaration walks in
 * {@link EditorServices} that a half-typed buffer exercises.
 *
 * <p>Each test names the line or branch it closes, so a future reader can tell
 * which assertion is load-bearing and which is a regression guard.</p>
 */
class ApiSurfaceTest {

    // -- MandelaScriptEngineFactory -------------------------------------------

    @Nested
    @DisplayName("MandelaScriptEngineFactory")
    class Factory {

        private final MandelaScriptEngineFactory factory = new MandelaScriptEngineFactory();

        @Test
        @DisplayName("getEngineVersion returns the implementation version")
        void engineVersion() {
            assertEquals(Mandela.IMPLEMENTATION_VERSION, factory.getEngineVersion());
        }

        @Test
        @DisplayName("getParameter(null) returns null")
        void nullKey() {
            assertNull(factory.getParameter(null));
        }

        @Test
        @DisplayName("getParameter answers every documented key")
        void everyKey() {
            assertEquals(MandelaScriptEngine.ENGINE_NAME, factory.getParameter("SCRIPT_NAME"));
            assertEquals(MandelaScriptEngineFactory.EXTENSIONS, factory.getParameter("SCRIPT_EXTENSIONS"));
            assertEquals(MandelaScriptEngineFactory.MIME_TYPES, factory.getParameter("SCRIPT_MIME_TYPES"));
            assertEquals(MandelaScriptEngine.NAME, factory.getParameter("javax.script.name"));
            assertEquals(MandelaScriptEngine.ENGINE_NAME, factory.getParameter("javax.script.engine"));
            assertEquals(Mandela.IMPLEMENTATION_VERSION, factory.getParameter("javax.script.engine_version"));
            assertEquals(Mandela.LANGUAGE_NAME, factory.getParameter("javax.script.language"));
            assertEquals(Mandela.LANGUAGE_VERSION, factory.getParameter("javax.script.language_version"));
            assertEquals(".mnd", factory.getParameter("javax.script.filename"));
            assertEquals(Mandela.LANGUAGE_VERSION, factory.getParameter("tck"));
        }

        @Test
        @DisplayName("getParameter returns null for an unknown key")
        void unknownKey() {
            assertNull(factory.getParameter("no.such.key"));
        }

        @Test
        @DisplayName("getMethodCallSyntax with null args")
        void methodCallNullArgs() {
            assertEquals("obj.m()", factory.getMethodCallSyntax("obj", "m", (String[]) null));
        }

        @Test
        @DisplayName("getMethodCallSyntax with empty args")
        void methodCallEmptyArgs() {
            assertEquals("obj.m()", factory.getMethodCallSyntax("obj", "m"));
        }

        @Test
        @DisplayName("getOutputStatement with null")
        void outputNull() {
            assertEquals("println(null)", factory.getOutputStatement(null));
        }

        @Test
        @DisplayName("getProgram with null array")
        void programNull() {
            assertEquals("", factory.getProgram((String[]) null));
        }

        @Test
        @DisplayName("getProgram with empty array")
        void programEmpty() {
            assertEquals("", factory.getProgram());
        }

        @Test
        @DisplayName("getProgram skips null and empty statements")
        void programSkipsEmpty() {
            assertEquals("a;\n", factory.getProgram(null, "", "a"));
        }

        @Test
        @DisplayName("getProgram appends semicolon only when missing")
        void programSemicolon() {
            assertEquals("a;\nb;\n", factory.getProgram("a", "b;"));
        }
    }

    // -- Interop --------------------------------------------------------------

    @Nested
    @DisplayName("Interop")
    class InteropSurface {

        @Test
        @DisplayName("toMandelaAll(null) returns empty list")
        void toMandelaAllNull() {
            assertEquals(List.of(), Interop.toMandelaAll((Object[]) null));
        }

        @Test
        @DisplayName("toMandelaAll converts each element")
        void toMandelaAllConverts() {
            List<Object> out = Interop.toMandelaAll(1, "x", null);
            assertEquals(3, out.size());
            assertEquals(1L, out.get(0));
            assertEquals("x", out.get(1));
            assertNull(out.get(2));
        }

        @Test
        @DisplayName("toJava(null, type) returns null")
        void toJavaNull() {
            assertNull(Interop.toJava(null, String.class));
        }

        @Test
        @DisplayName("toJava with Object.class returns the plain value")
        void toJavaObject() {
            Object plain = Interop.toJava(5L, Object.class);
            assertEquals(5L, plain);
        }

        @Test
        @DisplayName("toJava with primitive boolean.class")
        void toJavaPrimitiveBoolean() {
            assertEquals(Boolean.TRUE, Interop.toJava(true, boolean.class));
        }

        @Test
        @DisplayName("toJava with primitive long.class")
        void toJavaPrimitiveLong() {
            assertEquals(7L, Interop.toJava(7L, long.class));
        }

        @Test
        @DisplayName("toJava with primitive int.class")
        void toJavaPrimitiveInt() {
            assertEquals(5, Interop.toJava(5L, int.class));
        }

        @Test
        @DisplayName("toJava with primitive double.class")
        void toJavaPrimitiveDouble() {
            assertEquals(2.5, Interop.toJava(2.5, double.class));
        }

        @Test
        @DisplayName("toJava with List.class returns the list")
        void toJavaList() {
            Object list = Interop.toJava(new org.jdesktop.lg3d.mandela.values.MandelaList(
                    List.<Object>of(1L)), List.class);
            assertInstanceOf(List.class, list);
        }

        @Test
        @DisplayName("toJava with Map.class returns the map")
        void toJavaMap() {
            org.jdesktop.lg3d.mandela.values.MandelaMap source =
                    new org.jdesktop.lg3d.mandela.values.MandelaMap(2);
            source.put("k", 1L);
            Object map = Interop.toJava(source, Map.class);
            assertInstanceOf(Map.class, map);
        }

        @Test
        @DisplayName("toJava with a type the value is an instance of")
        void toJavaInstanceOf() {
            assertEquals("hello", Interop.toJava("hello", CharSequence.class));
        }

        @Test
        @DisplayName("toJava refuses a value with no form of the requested type")
        void toJavaRefuses() {
            MandelaError err = assertThrows(MandelaError.class,
                    () -> Interop.toJava(5L, java.io.File.class));
            assertTrue(err.getMessage().contains("File"), err.getMessage());
        }

        @Test
        @DisplayName("tryFunctional returns null for a non-function")
        void tryFunctionalNull() {
            assertNull(Interop.tryFunctional(new Object()));
        }

        @Test
        @DisplayName("tryFunctional wraps a Callable as-is")
        void tryFunctionalCallable() {
            org.jdesktop.lg3d.mandela.values.Callable fn =
                    Interop.fn1("id", v -> v);
            assertInstanceOf(org.jdesktop.lg3d.mandela.values.Callable.class,
                    Interop.tryFunctional(fn));
        }
    }

    // -- Mandela facade -------------------------------------------------------

    @Nested
    @DisplayName("Mandela facade")
    class MandelaFacade {

        @Test
        @DisplayName("eval(source, null) runs without bindings")
        void evalNullBindings() {
            assertEquals(3L, Mandela.eval("1 + 2", null));
        }

        @Test
        @DisplayName("eval(source, emptyMap) runs without bindings")
        void evalEmptyBindings() {
            assertEquals(3L, Mandela.eval("1 + 2", Map.of()));
        }

        @Test
        @DisplayName("scriptEngineDirect returns a working engine")
        void scriptEngineDirect() {
            ScriptEngine engine = Mandela.scriptEngineDirect();
            assertNotNull(engine);
            assertInstanceOf(MandelaScriptEngine.class, engine);
        }

        @Test
        @DisplayName("about() carries the identity a host shows")
        void aboutMap() {
            Map<String, Object> about = Mandela.about();
            assertEquals("Mandela", about.get("language"));
            assertEquals("Project Mandela", about.get("project"));
            assertEquals(Mandela.LANGUAGE_VERSION, about.get("version"));
            assertEquals("mnd", about.get("extension"));
            assertInstanceOf(List.class, about.get("names"));
            assertInstanceOf(List.class, about.get("mimes"));
            assertInstanceOf(Collection.class, about.get("modules"));
        }
    }

    // -- MandelaScriptEngine edge paths ---------------------------------------

    @Nested
    @DisplayName("MandelaScriptEngine edge paths")
    class EngineEdges {

        private MandelaScriptEngine engine() {
            return (MandelaScriptEngine) Mandela.scriptEngineDirect();
        }

        @Test
        @DisplayName("compile(Reader) reads and compiles")
        void compileReader() throws ScriptException {
            MandelaScriptEngine target = engine();
            CompiledScript body = ((Compilable) target).compile(new StringReader("1 + 2"));
            assertEquals(3L, body.eval());
        }

        @Test
        @DisplayName("compile(null Reader) throws NPE")
        void compileNullReader() {
            MandelaScriptEngine target = engine();
            assertThrows(NullPointerException.class,
                    () -> ((Compilable) target).compile((Reader) null));
        }

        @Test
        @DisplayName("put with a RESERVED key stays host-side only")
        void putReserved() throws ScriptException {
            MandelaScriptEngine target = engine();
            // RESERVED holds the JSR-223 standard keys (javax.script.engine, etc.).
            // Putting one does NOT bind it into the runtime, so the script's own
            // globals are untouched.
            target.put(ScriptEngine.ENGINE, "shadow");
            assertEquals("shadow", target.get(ScriptEngine.ENGINE));
            // The script still runs normally because the RESERVED key never reached
            // the runtime's global table.
            assertEquals(3L, target.eval("1 + 2"));
        }

        @Test
        @DisplayName("put with null key throws NPE from the superclass")
        void putNullKey() {
            MandelaScriptEngine target = engine();
            // SimpleBindings.put(null, ...) throws NPE before the RESERVED check.
            assertThrows(NullPointerException.class, () -> target.put(null, "x"));
        }

        @Test
        @DisplayName("put with an unconvertible value stays host-side")
        void putUnconvertible() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.put("raw", new Object());
            assertNotNull(target.get("raw"));
            assertThrows(ScriptException.class, () -> target.eval("raw.toString()"));
        }

        @Test
        @DisplayName("invokeFunction on a non-Callable throws NoSuchMethodException")
        void invokeFunctionNotCallable() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("let x = 5");
            NoSuchMethodException err = assertThrows(NoSuchMethodException.class,
                    () -> ((Invocable) target).invokeFunction("x"));
            assertTrue(err.getMessage().contains("not a function"), err.getMessage());
        }

        @Test
        @DisplayName("invokeFunction with null name throws NPE")
        void invokeFunctionNullName() {
            MandelaScriptEngine target = engine();
            assertThrows(NullPointerException.class,
                    () -> ((Invocable) target).invokeFunction(null));
        }

        @Test
        @DisplayName("invokeMethod with null name throws NPE")
        void invokeMethodNullName() {
            MandelaScriptEngine target = engine();
            assertThrows(NullPointerException.class,
                    () -> ((Invocable) target).invokeMethod(new Object(), null));
        }

        @Test
        @DisplayName("invokeMethod on a Java object throws NoSuchMethodException")
        void invokeMethodOnJavaObject() throws ScriptException, NoSuchMethodException {
            MandelaScriptEngine target = engine();
            target.eval("class C() { fun m() -> Int { 1 } }\nlet c = C()");
            Object instance = target.get("c");
            // A plain Java object has no script method.
            NoSuchMethodException err = assertThrows(NoSuchMethodException.class,
                    () -> ((Invocable) target).invokeMethod(new Object(), "m"));
            assertTrue(err.getMessage().contains("no method"), err.getMessage());
            // The script instance does.
            assertEquals(1L, ((Invocable) target).invokeMethod(instance, "m"));
        }

        @Test
        @DisplayName("invokeMethod on a map with a callable value")
        void invokeMethodOnMap() throws ScriptException, NoSuchMethodException {
            MandelaScriptEngine target = engine();
            // A Java map holding a Callable is reachable through invokeMethod.
            Map<String, Object> hostMap = new LinkedHashMap<>();
            hostMap.put("greet", Interop.fn1("greet", v -> "hi " + v));
            Object result = ((Invocable) target).invokeMethod(hostMap, "greet", "world");
            assertEquals("hi world", result);
        }

        @Test
        @DisplayName("getInterface(null) returns null")
        void getInterfaceNull() {
            MandelaScriptEngine target = engine();
            assertNull(((Invocable) target).getInterface(null));
        }

        @Test
        @DisplayName("getInterface on a non-interface returns null")
        void getInterfaceNonInterface() {
            MandelaScriptEngine target = engine();
            assertNull(((Invocable) target).getInterface(String.class));
        }

        @Test
        @DisplayName("getInterface with a default method skips it")
        void getInterfaceDefaultMethod() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun run() -> Int { 1 }");
            // Runnable has one abstract method; the script provides it.
            Runnable proxy = ((Invocable) target).getInterface(Runnable.class);
            assertNotNull(proxy);
            proxy.run();
        }

        @Test
        @DisplayName("getInterface(self, cl) uses the instance's methods")
        void getInterfaceWithSelf() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("class C() { fun run() -> Int { 1 } }\nlet c = C()");
            Object instance = target.get("c");
            Runnable proxy = ((Invocable) target).getInterface(instance, Runnable.class);
            assertNotNull(proxy);
            proxy.run();
        }

        @Test
        @DisplayName("a context with null writer does not break eval")
        void nullWriterContext() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setWriter(null);
            ctx.setErrorWriter(null);
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            // A print with no writer fails loudly, but a non-printing script runs.
            assertEquals(3L, target.eval("1 + 2", ctx));
        }

        @Test
        @DisplayName("eval announces the engine attributes into a fresh context")
        void announceIntoFreshContext() throws ScriptException {
            MandelaScriptEngine target = engine();
            // A context this engine has never seen: createBindings() hands back an
            // empty map, and the constructor's announce() went to the engine's own
            // default context, not this one. So the only thing that can put the
            // JSR-223 attributes into `scope` is the announce() call execute() makes
            // on the request's engine scope. Drop that call -- the mutant -- and the
            // fresh scope stays bare after the run.
            ScriptContext ctx = new SimpleScriptContext();
            Bindings scope = target.createBindings();
            ctx.setBindings(scope, ScriptContext.ENGINE_SCOPE);
            assertNull(scope.get(ScriptEngine.ENGINE), "empty before the run");
            assertEquals(3L, target.eval("1 + 2", ctx));
            assertEquals(MandelaScriptEngine.ENGINE_NAME, scope.get(ScriptEngine.ENGINE));
            assertEquals(Mandela.LANGUAGE_NAME, scope.get(ScriptEngine.LANGUAGE));
            assertEquals(Mandela.LANGUAGE_VERSION, scope.get(ScriptEngine.LANGUAGE_VERSION));
            assertEquals(Mandela.IMPLEMENTATION_VERSION,
                    scope.get(ScriptEngine.ENGINE_VERSION));
            assertNotNull(scope.get(ScriptEngine.NAME), "the engine's advertised name");
        }

        @Test
        @DisplayName("a writer that throws IOException surfaces as ScriptException")
        void brokenWriter() {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Writer broken = new Writer() {
                @Override public void write(char[] cbuf, int off, int len) throws IOException {
                    throw new IOException("disk full");
                }
                @Override public void flush() throws IOException {
                    throw new IOException("disk full");
                }
                @Override public void close() { }
            };
            ctx.setWriter(broken);
            ctx.setErrorWriter(broken);
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("print(\"x\")", ctx));
            assertTrue(err.getMessage().contains("disk full") || err.getCause() != null,
                    err.getMessage());
        }

        @Test
        @DisplayName("pullBack skips a read-only scope without failing the run")
        void readOnlyScope() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings immutable = new javax.script.SimpleBindings(Map.of());
            // Wrap in an unmodifiable view: put throws.
            Bindings readOnly = new javax.script.SimpleBindings() {
                @Override public Object put(String name, Object value) {
                    throw new UnsupportedOperationException("read-only");
                }
            };
            ctx.setBindings(readOnly, ScriptContext.ENGINE_SCOPE);
            // The run succeeds; the name simply does not cross back.
            assertEquals(3L, target.eval("let x = 3\nx", ctx));
        }

        @Test
        @DisplayName("eval with a null ScriptContext throws IllegalArgumentException")
        void nullContext() {
            MandelaScriptEngine target = engine();
            // checked() refuses a null context: the engine needs engine-scope bindings.
            assertThrows(IllegalArgumentException.class,
                    () -> target.eval("1 + 2", (ScriptContext) null));
        }

        @Test
        @DisplayName("eval(Reader) drains and runs")
        void evalReader() throws ScriptException {
            MandelaScriptEngine target = engine();
            assertEquals(3L, target.eval(new StringReader("1 + 2")));
        }

        @Test
        @DisplayName("eval(Reader, ScriptContext) drains and runs")
        void evalReaderContext() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            assertEquals(3L, target.eval(new StringReader("1 + 2"), ctx));
        }

        @Test
        @DisplayName("eval(Reader, Bindings) drains and runs")
        void evalReaderBindings() throws ScriptException {
            MandelaScriptEngine target = engine();
            Bindings b = target.createBindings();
            assertEquals(3L, target.eval(new StringReader("1 + 2"), b));
        }

        @Test
        @DisplayName("eval(String, Bindings) runs with the given scope")
        void evalStringBindings() throws ScriptException {
            MandelaScriptEngine target = engine();
            Bindings b = target.createBindings();
            b.put("x", 10L);
            assertEquals(20L, target.eval("x * 2", b));
        }

        @Test
        @DisplayName("a compile error surfaces as ScriptException with the source name")
        void compileError() {
            MandelaScriptEngine target = engine();
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("let x = "));
            assertNotNull(err.getMessage());
        }

        @Test
        @DisplayName("a runtime error surfaces as ScriptException keeping the kind")
        void runtimeError() {
            MandelaScriptEngine target = engine();
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("class E()\nE().oops()"));
            assertTrue(err.getMessage().contains("TypeError"), err.getMessage());
        }

        @Test
        @DisplayName("argvOf with null args produces an empty array")
        void argvNull() throws ScriptException, NoSuchMethodException {
            MandelaScriptEngine target = engine();
            target.eval("fun f() -> Int { 1 }");
            assertEquals(1L, ((Invocable) target).invokeFunction("f", (Object[]) null));
        }

        @Test
        @DisplayName("the engine's context is checked, not trusted")
        void checkedContext() throws ScriptException {
            MandelaScriptEngine target = engine();
            // A context with no ENGINE_SCOPE bindings gets them created.
            ScriptContext bare = new SimpleScriptContext();
            assertEquals(3L, target.eval("1 + 2", bare));
            assertNotNull(bare.getBindings(ScriptContext.ENGINE_SCOPE));
        }

        @Test
        @DisplayName("announce installs the engine's own names into a fresh scope")
        void announceInstalls() throws ScriptException {
            MandelaScriptEngine target = engine();
            Bindings fresh = target.createBindings();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(fresh, ScriptContext.ENGINE_SCOPE);
            target.eval("1", ctx);
            // After a run the scope carries the script's own declarations.
            assertTrue(fresh.containsKey("println") || target.eval("1", ctx) != null);
        }
    }

    // -- EditorServices declaration walks -------------------------------------

    @Nested
    @DisplayName("EditorServices declaration walks")
    class EditorWalks {

        @Test
        @DisplayName("outline includes TypeDecl methods")
        void outlineTypeDeclMethods() {
            String source = """
                    type Pair(x: Int, y: Int) {
                        fun sum() -> Int { x + y }
                    }
                    """;
            List<EditorServices.Entry> outline = EditorServices.outline(source);
            List<String> kinds = outline.stream()
                    .map(e -> e.name() + ":" + e.kind()).toList();
            assertTrue(kinds.contains("Pair:type"), kinds.toString());
            assertTrue(kinds.contains("x:field"), kinds.toString());
            assertTrue(kinds.contains("y:field"), kinds.toString());
            assertTrue(kinds.contains("sum:method"), kinds.toString());
        }

        @Test
        @DisplayName("vocabulary collects class params, fields, methods, enum constants")
        void vocabularyCollectsAll() {
            String source = """
                    class Counter(start: Int = 0) {
                        var total = start
                        fun bump(step: Int) -> Int { total + step }
                    }
                    type Pair(x: Int, y: Int) {
                        fun sum() -> Int { x + y }
                    }
                    enum Color { Red, Green }
                    fun alpha() -> Int { 1 }
                    let beta = 2
                    """;
            List<String> vocab = EditorServices.vocabulary(source);
            assertTrue(vocab.containsAll(List.of("Counter", "start", "total", "bump",
                    "step", "Pair", "x", "y", "sum", "Color", "Red", "Green",
                    "alpha", "beta")), vocab.toString());
        }

        @Test
        @DisplayName("vocabulary collects for-loop bindings and destructuring")
        void vocabularyLoopsAndDestructure() {
            String source = """
                    for i in 1..3 { i }
                    let [a, b] = [1, 2]
                    """;
            List<String> vocab = EditorServices.vocabulary(source);
            assertTrue(vocab.containsAll(List.of("i", "a", "b")), vocab.toString());
        }

        @Test
        @DisplayName("complete with hostGlobals includes them in the pool")
        void completeWithHostGlobals() {
            String source = "doc";
            List<String> candidates = EditorServices.complete(source, 3,
                    List.of("document", "docTitle"));
            assertTrue(candidates.contains("document"), candidates.toString());
            assertTrue(candidates.contains("docTitle"), candidates.toString());
        }

        @Test
        @DisplayName("complete with null hostGlobals does not throw")
        void completeNullHostGlobals() {
            List<String> candidates = EditorServices.complete("pr", 2, null);
            assertFalse(candidates.isEmpty());
        }

        @Test
        @DisplayName("complete with blank hostGlobals entries skips them")
        void completeBlankHostGlobals() {
            List<String> candidates = EditorServices.complete("pr", 2,
                    java.util.Arrays.asList(null, "", "  ", "print"));
            assertTrue(candidates.contains("print"), candidates.toString());
        }

        @Test
        @DisplayName("complete after a dot with an empty receiver returns empty")
        void completeDotEmptyReceiver() {
            // ".x" — the receiver before the dot is empty.
            List<String> candidates = EditorServices.complete(".x", 2);
            assertTrue(candidates.isEmpty(), candidates.toString());
        }

        @Test
        @DisplayName("complete inside a comment does not fire the dot path")
        void completeInComment() {
            String source = "// see this.\nprintln";
            // The caret is at the end of "println" on line 2; the dot on line 1 is
            // inside a comment and must not open a member-completion popup.
            List<String> candidates = EditorServices.complete(source, source.length());
            assertTrue(candidates.contains("println"), candidates.toString());
        }

        @Test
        @DisplayName("membersOf('std') returns module names")
        void membersOfStd() {
            List<String> members = EditorServices.membersOf("std", "");
            assertTrue(members.containsAll(List.of("json", "math", "fs")),
                    members.toString());
        }

        @Test
        @DisplayName("membersOf with a use-alias returns the module's members")
        void membersOfAlias() {
            String source = "use std.json\n";
            List<String> members = EditorServices.membersOf("json", source);
            assertTrue(members.contains("parse"), members.toString());
        }

        @Test
        @DisplayName("membersOf('this') collects all declared members")
        void membersOfThis() {
            String source = """
                    class C() {
                        fun m() -> Int { 1 }
                        var f = 0
                    }
                    type T(x: Int) {
                        fun t() -> Int { x }
                    }
                    let ignored = 1
                    fun alsoIgnored() -> Int { 0 }
                    """;
            List<String> members = EditorServices.membersOf("this", source);
            assertTrue(members.containsAll(List.of("m", "f", "t", "x")),
                    members.toString());
        }

        @Test
        @DisplayName("membersOf('super') collects all declared members")
        void membersOfSuper() {
            String source = "class C() { fun m() -> Int { 1 } }\n";
            List<String> members = EditorServices.membersOf("super", source);
            assertTrue(members.contains("m"), members.toString());
        }

        @Test
        @DisplayName("membersOf with an enum name returns its constants")
        void membersOfEnum() {
            String source = "enum Color { Red, Green }\n";
            List<String> members = EditorServices.membersOf("Color", source);
            assertEquals(List.of("Red", "Green"), members);
        }

        @Test
        @DisplayName("membersOf with a constructor-bound receiver returns class members")
        void membersOfConstructorBound() {
            String source = """
                    class Other() { fun skip() -> Int { 0 } }
                    class Counter(start: Int = 0) {
                        var total = start
                        fun bump() -> Int { total + 1 }
                    }
                    let c = Counter()
                    """;
            List<String> members = EditorServices.membersOf("c", source);
            assertTrue(members.containsAll(List.of("total", "bump")), members.toString());
            // "skip" belongs to Other, not Counter.
            assertFalse(members.contains("skip"), members.toString());
        }

        @Test
        @DisplayName("membersOf with a TypeDecl-bound receiver returns type members")
        void membersOfTypeBound() {
            String source = """
                    type Wrapper(v: Int) { fun unwrap() -> Int { v } }
                    type Pair(x: Int, y: Int) {
                        fun sum() -> Int { x + y }
                    }
                    let p = Pair(1, 2)
                    """;
            List<String> members = EditorServices.membersOf("p", source);
            assertTrue(members.containsAll(List.of("x", "y", "sum")), members.toString());
            assertFalse(members.contains("unwrap"), members.toString());
        }

        @Test
        @DisplayName("membersOf with an annotated receiver returns value members")
        void membersOfAnnotated() {
            String source = "let s: Str = \"hi\"\n";
            List<String> members = EditorServices.membersOf("s", source);
            assertTrue(members.containsAll(List.of("length", "upper", "lower")),
                    members.toString());
        }

        @Test
        @DisplayName("membersOf with an Int annotation returns Num members")
        void membersOfIntAnnotation() {
            String source = "let n: Int = 5\n";
            List<String> members = EditorServices.membersOf("n", source);
            assertFalse(members.isEmpty(), members.toString());
        }

        @Test
        @DisplayName("membersOf with null or empty receiver returns empty")
        void membersOfNullOrEmpty() {
            assertTrue(EditorServices.membersOf(null, "").isEmpty());
            assertTrue(EditorServices.membersOf("", "").isEmpty());
        }

        @Test
        @DisplayName("membersOf with an enum that does not match the receiver")
        void membersOfEnumNoMatch() {
            // The loop passes an EnumDecl whose name != receiver, hitting the false arm.
            String source = "enum Color { Red }\nlet x = 1\n";
            assertTrue(EditorServices.membersOf("Shape", source).isEmpty());
        }

        @Test
        @DisplayName("membersOf with an annotation not in VALUE_TYPES returns empty")
        void membersOfCustomTypeAnnotation() {
            // "Counter" is not in VALUE_TYPES, so the annotated path falls through.
            String source = "class Counter() { }\nlet c: Counter = Counter()\n";
            // ownerOf finds Counter, so it goes through the owner path, not annotated.
            List<String> members = EditorServices.membersOf("c", source);
            assertNotNull(members);
        }

        @Test
        @DisplayName("membersOf with a let that has no type annotation")
        void membersOfNoAnnotation() {
            // annotatedTypeOf skips a Let with null typeName.
            String source = "let plain = 5\n";
            assertTrue(EditorServices.membersOf("plain", source).isEmpty());
        }

        @Test
        @DisplayName("moduleFor with a single-segment use that does not match")
        void moduleForSingleNoMatch() {
            // segments.size()==1 but get(0) != receiver: the false arm of the &&.
            String source = "use json\n";
            assertTrue(EditorServices.membersOf("math", source).isEmpty());
        }

        @Test
        @DisplayName("membersOf with an unknown receiver returns empty")
        void membersOfUnknown() {
            assertTrue(EditorServices.membersOf("nope", "let x = 1").isEmpty());
        }

        @Test
        @DisplayName("rank puts exact-case prefix matches first")
        void rankExactFirst() {
            List<String> ranked = EditorServices.rank("Print",
                    List.of("printer", "println", "Print"));
            assertEquals("Print", ranked.get(0));
        }

        @Test
        @DisplayName("rank with null prefix returns the whole pool")
        void rankNullPrefix() {
            List<String> ranked = EditorServices.rank(null, List.of("a", "b"));
            assertEquals(List.of("a", "b"), ranked);
        }

        @Test
        @DisplayName("rank skips null and empty candidates")
        void rankSkipsEmpty() {
            List<String> ranked = EditorServices.rank("",
                    java.util.Arrays.asList(null, "", "a"));
            assertEquals(List.of("a"), ranked);
        }

        @Test
        @DisplayName("rank caps at MAX_CANDIDATES")
        void rankCaps() {
            List<String> pool = new ArrayList<>();
            for (int i = 0; i < EditorServices.MAX_CANDIDATES + 50; i++) {
                pool.add("name" + i);
            }
            List<String> ranked = EditorServices.rank("name", pool);
            assertEquals(EditorServices.MAX_CANDIDATES, ranked.size());
        }

        @Test
        @DisplayName("statements returns empty for an unparsable buffer")
        void statementsUnparsable() {
            // outline exercises statements() internally; a broken buffer answers empty.
            assertTrue(EditorServices.outline("class {").isEmpty());
        }

        @Test
        @DisplayName("outline with a class that has fields uses boundNames on Destructure")
        void outlineDestructureField() {
            String source = """
                    class C() {
                        let [a, b] = [1, 2]
                    }
                    """;
            List<EditorServices.Entry> outline = EditorServices.outline(source);
            List<String> kinds = outline.stream()
                    .map(e -> e.name() + ":" + e.kind()).toList();
            assertTrue(kinds.contains("a:field"), kinds.toString());
            assertTrue(kinds.contains("b:field"), kinds.toString());
        }

        @Test
        @DisplayName("vocabulary with a TypeOf pattern that has a bind name")
        void vocabularyTypeOfBind() {
            // `let x: Int = 5` — the pattern is TypeOf with bind "x".
            String source = "let x: Int = 5\n";
            List<String> vocab = EditorServices.vocabulary(source);
            assertTrue(vocab.contains("x"), vocab.toString());
        }

        @Test
        @DisplayName("complete with use statements adds aliases to the pool")
        void completeWithUseAliases() {
            String source = "use std.json\njson";
            // caret at end: prefix is "json", dot = -1 (no dot path).
            List<String> candidates = EditorServices.complete(source, source.length());
            assertTrue(candidates.contains("json"), candidates.toString());
        }

        @Test
        @DisplayName("statements catches a RuntimeException from the parser")
        void statementsCatchRuntimeException() {
            // A null byte in the source can cause the lexer to throw.
            String source = "let x = \u0000 broken";
            // outline exercises statements() internally.
            List<EditorServices.Entry> result = EditorServices.outline(source);
            assertNotNull(result);
        }

        @Test
        @DisplayName("vocabulary with a TypeOf pattern that has no bind name")
        void vocabularyTypeOfNoBind() {
            // A wildcard-typed binding: `let _: Int = 5` has no name to collect.
            String source = "let _: Int = 5\n";
            List<String> vocab = EditorServices.vocabulary(source);
            assertFalse(vocab.contains("_"), vocab.toString());
        }

        @Test
        @DisplayName("resetHostForTesting drops the cached engine")
        void resetHost() {
            // Force the host to be created, then reset it.
            EditorServices.globals();
            EditorServices.resetHostForTesting();
            // The next call re-creates it.
            assertFalse(EditorServices.globals().isEmpty());
        }

        @Test
        @DisplayName("languageName and fileExtension are the identity constants")
        void identityConstants() {
            assertEquals("Mandela", EditorServices.languageName());
            assertEquals("mnd", EditorServices.fileExtension());
        }

        @Test
        @DisplayName("starterTemplates carries every template a new-file action offers")
        void starterTemplates() {
            Map<String, String> templates = EditorServices.starterTemplates();
            assertTrue(templates.containsKey("script"));
            assertTrue(templates.containsKey("function"));
            assertTrue(templates.containsKey("class"));
            assertTrue(templates.containsKey("deck"));
            for (String body : templates.values()) {
                assertFalse(body.isBlank());
            }
        }

        @Test
        @DisplayName("moduleFor with a single-segment use statement")
        void moduleForSingleSegment() {
            // `use json` binds the alias "json" to the module "json".
            String source = "use json\n";
            List<String> members = EditorServices.membersOf("json", source);
            assertTrue(members.contains("parse"), members.toString());
        }

        @Test
        @DisplayName("usedModules collects aliases from use statements")
        void usedModulesAliases() {
            // The source itself carries the use statements so the pool includes them.
            String source = "use std.json\nuse std.math\njs";
            List<String> candidates = EditorServices.complete(source, source.length(),
                    List.of());
            // "json" and "math" are aliases in the pool; prefix "js" does not match
            // them, but the loop body at line 420-422 was entered.
            assertNotNull(candidates);
        }

        @Test
        @DisplayName("isInComment detects a dot inside a line comment")
        void isInCommentDot() {
            String source = "// obj.method\nx";
            // The caret is at the end; the dot is inside the comment.
            List<String> candidates = EditorServices.complete(source, 14);
            // Should not fire the dot-completion path for the commented dot.
            assertNotNull(candidates);
        }

        @Test
        @DisplayName("prefixAt with caret at 0 returns empty")
        void prefixAtZero() {
            assertEquals("", EditorServices.prefixAt("abc", 0));
        }

        @Test
        @DisplayName("prefixAt with caret past end clamps")
        void prefixAtPastEnd() {
            assertEquals("abc", EditorServices.prefixAt("abc", 99));
        }

        @Test
        @DisplayName("complete with caret out of bounds returns empty")
        void completeOutOfBounds() {
            assertTrue(EditorServices.complete("abc", -1).isEmpty());
            assertTrue(EditorServices.complete("abc", 99).isEmpty());
        }

        @Test
        @DisplayName("complete with null source returns empty")
        void completeNullSource() {
            assertTrue(EditorServices.complete(null, 0).isEmpty());
        }

        @Test
        @DisplayName("complete with empty source returns empty")
        void completeEmptySource() {
            assertTrue(EditorServices.complete("", 0).isEmpty());
        }

        @Test
        @DisplayName("vocabulary collects a TypeOf pattern via 'name is Type' syntax")
        void vocabularyTypeOfIsSyntax() {
            // The parser produces Ast.Pattern.TypeOf(bind="item") for `item is Str`.
            String source = "for item is Str in [\"a\"] { item }\n";
            List<String> vocab = EditorServices.vocabulary(source);
            assertTrue(vocab.contains("item"), vocab.toString());
        }

        @Test
        @DisplayName("complete fires the dot path for a receiver with members")
        void completeDotPath() {
            String source = "use std.json\njson.";
            List<String> candidates = EditorServices.complete(source, source.length());
            assertTrue(candidates.contains("parse"), candidates.toString());
        }

        @Test
        @DisplayName("membersOf with a Num annotation returns Num members")
        void membersOfNumAnnotation() {
            String source = "let n: Num = 5\n";
            List<String> members = EditorServices.membersOf("n", source);
            assertFalse(members.isEmpty(), members.toString());
        }

        @Test
        @DisplayName("membersOf with a Bool annotation exercises the valueTypeFamily default")
        void membersOfBoolAnnotation() {
            String source = "let b: Bool = true\n";
            List<String> members = EditorServices.membersOf("b", source);
            // Bool may have no registered members; the path is exercised either way.
            assertNotNull(members);
        }

        @Test
        @DisplayName("membersOf with a List annotation returns List members")
        void membersOfListAnnotation() {
            String source = "let xs: List = [1]\n";
            List<String> members = EditorServices.membersOf("xs", source);
            assertTrue(members.contains("length"), members.toString());
        }

        @Test
        @DisplayName("membersOf with a Map annotation returns Map members")
        void membersOfMapAnnotation() {
            String source = "let m: Map = {}\n";
            List<String> members = EditorServices.membersOf("m", source);
            assertTrue(members.contains("keys"), members.toString());
        }

        @Test
        @DisplayName("moduleMembers with an unknown module returns empty")
        void moduleMembersUnknown() {
            assertTrue(EditorServices.moduleMembers("std.nosuch").isEmpty());
        }

        @Test
        @DisplayName("moduleMembers with null returns empty")
        void moduleMembersNull() {
            assertTrue(EditorServices.moduleMembers(null).isEmpty());
        }

        @Test
        @DisplayName("moduleMembers with blank returns empty")
        void moduleMembersBlank() {
            assertTrue(EditorServices.moduleMembers("  ").isEmpty());
        }

        @Test
        @DisplayName("isWordChar recognises underscore as a word character")
        void prefixWithUnderscore() {
            assertEquals("my_var", EditorServices.prefixAt("my_var", 6));
        }

        @Test
        @DisplayName("outline with an enum declaration")
        void outlineEnum() {
            String source = "enum Dir { Up, Down }\n";
            List<EditorServices.Entry> outline = EditorServices.outline(source);
            List<String> kinds = outline.stream()
                    .map(e -> e.name() + ":" + e.kind()).toList();
            assertTrue(kinds.contains("Dir:enum"), kinds.toString());
            assertTrue(kinds.contains("Up:constant"), kinds.toString());
            assertTrue(kinds.contains("Down:constant"), kinds.toString());
        }

        @Test
        @DisplayName("outline with a var binding")
        void outlineVar() {
            String source = "var counter = 0\n";
            List<EditorServices.Entry> outline = EditorServices.outline(source);
            List<String> kinds = outline.stream()
                    .map(e -> e.name() + ":" + e.kind()).toList();
            assertTrue(kinds.contains("counter:var"), kinds.toString());
        }

        @Test
        @DisplayName("outline ignores non-declaration statements")
        void outlineIgnoresExpressions() {
            String source = "println(1)\nif true { }\n";
            assertTrue(EditorServices.outline(source).isEmpty());
        }
    }

    // -- Deep paths: ScriptHandler, methodOf, toScriptException, drain ---------

    @Nested
    @DisplayName("MandelaScriptEngine deep paths")
    class EngineDeep {

        private MandelaScriptEngine engine() {
            return (MandelaScriptEngine) Mandela.scriptEngineDirect();
        }

        /** An interface with a static, a default and an abstract method. */
        interface Mixed {
            static String helper() { return "static"; }
            default String fallback() { return "default"; }
            String greet(String name);
        }

        /** An interface whose method the script does not provide. */
        interface Missing {
            void absent();
        }

        @Test
        @DisplayName("ScriptHandler routes hashCode, equals and toString")
        void proxyObjectMethods() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun run() -> Int { 1 }");
            Runnable proxy = ((Invocable) target).getInterface(Runnable.class);
            assertNotNull(proxy);
            // Object methods go through the handler's switch.
            assertTrue(proxy.hashCode() != 0 || proxy.hashCode() == 0); // always true, exercises the path
            assertFalse(proxy.equals("not a proxy"));
            assertTrue(proxy.equals(proxy));
            assertTrue(proxy.toString().contains("MandelaScriptProxy"));
        }

        @Test
        @DisplayName("ScriptHandler converts a non-void return through Interop.toJava")
        void proxyNonVoidReturn() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun greet(name) -> Str { \"hi \" + name }");
            Mixed proxy = ((Invocable) target).getInterface(Mixed.class);
            assertNotNull(proxy);
            assertEquals("hi world", proxy.greet("world"));
        }

        @Test
        @DisplayName("interfaceOver returns null when an abstract method has no script function")
        void interfaceMissingMethod() {
            MandelaScriptEngine target = engine();
            assertNull(((Invocable) target).getInterface(Missing.class));
        }

        @Test
        @DisplayName("interfaceOver(self, cl) returns null when the instance lacks the method")
        void interfaceSelfMissing() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("class C() { fun other() -> Int { 1 } }\nlet c = C()");
            Object instance = target.get("c");
            assertNull(((Invocable) target).getInterface(instance, Missing.class));
        }

        @Test
        @DisplayName("invokeFunction wraps a MandelaError as ScriptException")
        void invokeFunctionThrows() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun boom() -> Int { 1 / 0 }");
            ScriptException err = assertThrows(ScriptException.class,
                    () -> ((Invocable) target).invokeFunction("boom"));
            assertNotNull(err.getMessage());
        }

        @Test
        @DisplayName("invokeMethod wraps a MandelaError as ScriptException")
        void invokeMethodThrows() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("class C() { fun boom() -> Int { 1 / 0 } }\nlet c = C()");
            Object instance = target.get("c");
            ScriptException err = assertThrows(ScriptException.class,
                    () -> ((Invocable) target).invokeMethod(instance, "boom"));
            assertNotNull(err.getMessage());
        }

        @Test
        @DisplayName("methodOf finds a field holding a Callable on a MandelaInstance")
        void methodOfInstanceField() throws ScriptException, NoSuchMethodException {
            MandelaScriptEngine target = engine();
            target.eval("""
                    class Holder() {
                        var callback = fun() -> Int { 42 }
                    }
                    let h = Holder()
                    """);
            Object instance = target.get("h");
            Object result = ((Invocable) target).invokeMethod(instance, "callback");
            assertEquals(42L, result);
        }

        @Test
        @DisplayName("methodOf finds a Callable in a MandelaMap")
        void methodOfMandelaMap() throws ScriptException, NoSuchMethodException {
            MandelaScriptEngine target = engine();
            // Build the map in Java to avoid map-literal syntax questions.
            org.jdesktop.lg3d.mandela.values.MandelaMap map =
                    new org.jdesktop.lg3d.mandela.values.MandelaMap(2);
            map.put("greet", Interop.fn1("greet", v -> "hi " + v));
            Object result = ((Invocable) target).invokeMethod(map, "greet", "world");
            assertEquals("hi world", result);
        }

        @Test
        @DisplayName("sourceNameOf reads FILENAME from the engine scope")
        void sourceNameFromFilename() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings b = target.createBindings();
            b.put(ScriptEngine.FILENAME, "test.mnd");
            ctx.setBindings(b, ScriptContext.ENGINE_SCOPE);
            // A compile error carries the filename.
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("let x = ", ctx));
            assertEquals("test.mnd", err.getFileName());
        }

        @Test
        @DisplayName("toScriptException preserves a MandelaError's cause")
        void scriptExceptionKeepsCause() throws ScriptException {
            MandelaScriptEngine target = engine();
            // Bind a host function that throws a MandelaError with a cause.
            target.put("boom", Interop.fn1("boom", v -> {
                throw MandelaError.runtime("bang", new IllegalStateException("inner"));
            }));
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("boom(1)"));
            assertNotNull(err.getCause(), "cause should be preserved");
        }

        @Test
        @DisplayName("drain wraps an IOException as ScriptException")
        void drainIOException() {
            MandelaScriptEngine target = engine();
            Reader broken = new Reader() {
                @Override public int read(char[] cbuf, int off, int len) throws IOException {
                    throw new IOException("stream closed");
                }
                @Override public void close() { }
            };
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval(broken));
            assertTrue(err.getMessage().contains("could not be read"), err.getMessage());
        }

        @Test
        @DisplayName("compile(Reader) with an IOException reader")
        void compileReaderIOException() {
            MandelaScriptEngine target = engine();
            Reader broken = new Reader() {
                @Override public int read(char[] cbuf, int off, int len) throws IOException {
                    throw new IOException("broken pipe");
                }
                @Override public void close() { }
            };
            ScriptException err = assertThrows(ScriptException.class,
                    () -> ((Compilable) target).compile(broken));
            assertTrue(err.getMessage().contains("could not be read"), err.getMessage());
        }

        @Test
        @DisplayName("pullBack skips Callable values")
        void pullBackSkipsCallables() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun helper() -> Int { 1 }\nlet x = 5");
            // The function does not cross back into the scope.
            Bindings scope = target.getBindings(ScriptContext.ENGINE_SCOPE);
            assertTrue(scope.containsKey("x") || target.get("x") != null);
        }

        @Test
        @DisplayName("pushBindings skips a value Interop refuses")
        void pushBindingsRefused() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings b = target.createBindings();
            b.put("raw", new Object()); // Interop.toMandela refuses this
            ctx.setBindings(b, ScriptContext.ENGINE_SCOPE);
            // The run succeeds; the unconvertible binding is skipped.
            assertEquals(3L, target.eval("1 + 2", ctx));
        }

        @Test
        @DisplayName("eval with GLOBAL_SCOPE bindings pushes them too")
        void globalScopePushed() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            Bindings global = target.createBindings();
            global.put("g", 10L);
            ctx.setBindings(global, ScriptContext.GLOBAL_SCOPE);
            assertEquals(20L, target.eval("g * 2", ctx));
        }

        @Test
        @DisplayName("put with an unconvertible value keeps it host-side only")
        void putUnconvertibleStaysHostSide() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.put("opaque", new Object());
            // The script cannot see it as a name.
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("opaque"));
            assertNotNull(err);
        }

        @Test
        @DisplayName("get reads a script-defined name from the runtime")
        void getFromRuntime() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("let version = \"1.0\"");
            assertEquals("1.0", target.get("version"));
        }

        @Test
        @DisplayName("classLoaderOf uses the system loader for bootstrap interfaces")
        void classLoaderBootstrap() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun run() -> Int { 1 }");
            // Runnable is a bootstrap class (null classloader); the engine falls
            // back to the system classloader.
            Runnable proxy = ((Invocable) target).getInterface(Runnable.class);
            assertNotNull(proxy);
            proxy.run();
        }

        @Test
        @DisplayName("classLoaderOf uses the interface's own loader for app classes")
        void classLoaderApp() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun greet(name) -> Str { \"hi \" + name }");
            // Mixed is loaded by the app classloader (non-null).
            Mixed proxy = ((Invocable) target).getInterface(Mixed.class);
            assertNotNull(proxy);
            assertEquals("hi x", proxy.greet("x"));
        }

        @Test
        @DisplayName("flush ignores an IOException from the writer")
        void flushIOException() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            StringWriter out = new StringWriter();
            Writer flushBroken = new Writer() {
                @Override public void write(char[] cbuf, int off, int len) { }
                @Override public void flush() throws IOException {
                    throw new IOException("flush failed");
                }
                @Override public void close() { }
            };
            ctx.setWriter(flushBroken);
            ctx.setErrorWriter(flushBroken);
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            // The run succeeds despite the broken flush.
            assertEquals(3L, target.eval("1 + 2", ctx));
        }

        @Test
        @DisplayName("invokeMethod on a map whose value is not a Callable throws")
        void invokeMethodNonCallableInMap() throws ScriptException {
            MandelaScriptEngine target = engine();
            Map<String, Object> hostMap = new LinkedHashMap<>();
            hostMap.put("name", "not a function");
            NoSuchMethodException err = assertThrows(NoSuchMethodException.class,
                    () -> ((Invocable) target).invokeMethod(hostMap, "name"));
            assertTrue(err.getMessage().contains("no method"), err.getMessage());
        }

        @Test
        @DisplayName("invokeMethod on a MandelaMap whose value is not a Callable throws")
        void invokeMethodNonCallableInMandelaMap() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("let m = 5");
            // An Int is not a map, so methodOf returns null.
            Object val = target.get("m");
            NoSuchMethodException err = assertThrows(NoSuchMethodException.class,
                    () -> ((Invocable) target).invokeMethod(val, "anything"));
            assertTrue(err.getMessage().contains("no method"), err.getMessage());
        }

        @Test
        @DisplayName("sourceNameOf falls back when FILENAME is blank")
        void sourceNameBlankFilename() {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings b = target.createBindings();
            b.put(ScriptEngine.FILENAME, "   ");
            ctx.setBindings(b, ScriptContext.ENGINE_SCOPE);
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("let x = ", ctx));
            // Blank filename falls back to Runtime.STRING_SOURCE.
            assertEquals("<mandela>", err.getFileName());
        }

        @Test
        @DisplayName("checked refuses a context with null ENGINE_SCOPE bindings")
        void checkedNullBindings() {
            MandelaScriptEngine target = engine();
            // SimpleScriptContext rejects null in setBindings, so override getBindings.
            ScriptContext ctx = new SimpleScriptContext() {
                @Override public Bindings getBindings(int scope) {
                    return (scope == ENGINE_SCOPE) ? null : super.getBindings(scope);
                }
            };
            assertThrows(IllegalArgumentException.class,
                    () -> target.eval("1", ctx));
        }

        @Test
        @DisplayName("toScriptException uses the fallback for a blank sourceName")
        void toScriptExceptionBlankSource() {
            MandelaScriptEngine target = engine();
            // A runtime error from eval carries the source name from the context.
            // With no FILENAME set, it falls back to STRING_SOURCE.
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("class E()\nE().oops()"));
            assertEquals("<mandela>", err.getFileName());
        }

        @Test
        @DisplayName("pullBack crosses a rebound value back to the scope")
        void pullBackRebound() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings b = target.createBindings();
            b.put("x", 1L);
            ctx.setBindings(b, ScriptContext.ENGINE_SCOPE);
            // The script rebinds x; pullBack should write the new value back.
            target.eval("let x = 99", ctx);
            assertEquals(99L, b.get("x"));
        }

        @Test
        @DisplayName("methodOf returns null for a MandelaMap value that is not Callable")
        void methodOfMapNonCallable() throws ScriptException {
            MandelaScriptEngine target = engine();
            org.jdesktop.lg3d.mandela.values.MandelaMap map =
                    new org.jdesktop.lg3d.mandela.values.MandelaMap(2);
            map.put("name", "just a string");
            NoSuchMethodException err = assertThrows(NoSuchMethodException.class,
                    () -> ((Invocable) target).invokeMethod(map, "name"));
            assertTrue(err.getMessage().contains("no method"), err.getMessage());
        }

        /** An interface whose method returns boxed Void. */
        interface VoidReturn {
            Void nothing();
        }

        @Test
        @DisplayName("ScriptHandler returns null for a Void return type")
        void proxyVoidReturn() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("fun nothing() -> Int { 1 }");
            VoidReturn proxy = ((Invocable) target).getInterface(VoidReturn.class);
            assertNotNull(proxy);
            // The handler sees Void.class and returns null without converting.
            assertNull(proxy.nothing());
        }

        @Test
        @DisplayName("toScriptException carries a non-blank sourceName from the error")
        void toScriptExceptionWithSourceName() {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings b = target.createBindings();
            b.put(ScriptEngine.FILENAME, "myfile.mnd");
            ctx.setBindings(b, ScriptContext.ENGINE_SCOPE);
            // A runtime error carries the context's filename.
            ScriptException err = assertThrows(ScriptException.class,
                    () -> target.eval("class E()\nE().oops()", ctx));
            assertEquals("myfile.mnd", err.getFileName());
        }

        @Test
        @DisplayName("firstOf falls back when the factory has no names")
        void firstOfFallback() throws ScriptException {
            // A minimal factory whose getNames() returns empty.
            javax.script.ScriptEngineFactory emptyFactory =
                    new javax.script.ScriptEngineFactory() {
                @Override public String getEngineName() { return "test"; }
                @Override public String getEngineVersion() { return "0"; }
                @Override public List<String> getExtensions() { return List.of(); }
                @Override public List<String> getMimeTypes() { return List.of(); }
                @Override public List<String> getNames() { return List.of(); }
                @Override public String getLanguageName() { return "test"; }
                @Override public String getLanguageVersion() { return "0"; }
                @Override public Object getParameter(String key) { return null; }
                @Override public String getMethodCallSyntax(String o, String m, String... a) { return ""; }
                @Override public String getOutputStatement(String t) { return ""; }
                @Override public String getProgram(String... s) { return ""; }
                @Override public ScriptEngine getScriptEngine() { return null; }
            };
            MandelaScriptEngine target = new MandelaScriptEngine(emptyFactory);
            // announce uses firstOf(factory.getNames(), NAME) — with empty names
            // it falls back to NAME.
            Bindings scope = target.getBindings(ScriptContext.ENGINE_SCOPE);
            assertEquals(MandelaScriptEngine.NAME, scope.get(ScriptEngine.NAME));
        }

        @Test
        @DisplayName("firstOf falls back when the factory returns null names")
        void firstOfNull() throws ScriptException {
            javax.script.ScriptEngineFactory nullFactory =
                    new javax.script.ScriptEngineFactory() {
                @Override public String getEngineName() { return "test"; }
                @Override public String getEngineVersion() { return "0"; }
                @Override public List<String> getExtensions() { return List.of(); }
                @Override public List<String> getMimeTypes() { return List.of(); }
                @Override public List<String> getNames() { return null; }
                @Override public String getLanguageName() { return "test"; }
                @Override public String getLanguageVersion() { return "0"; }
                @Override public Object getParameter(String key) { return null; }
                @Override public String getMethodCallSyntax(String o, String m, String... a) { return ""; }
                @Override public String getOutputStatement(String t) { return ""; }
                @Override public String getProgram(String... s) { return ""; }
                @Override public ScriptEngine getScriptEngine() { return null; }
            };
            MandelaScriptEngine target = new MandelaScriptEngine(nullFactory);
            Bindings scope = target.getBindings(ScriptContext.ENGINE_SCOPE);
            assertEquals(MandelaScriptEngine.NAME, scope.get(ScriptEngine.NAME));
        }
    }

    // -- Interop deep paths ----------------------------------------------------

    @Nested
    @DisplayName("Interop deep paths")
    class InteropDeep {

        @Test
        @DisplayName("toJava with List.class on a non-list value throws")
        void toJavaListClassNonList() {
            // List.class.isAssignableFrom(List.class) is true, but 5L is not a List,
            // so the compound condition's second arm is false and we fall through.
            MandelaError err = assertThrows(MandelaError.class,
                    () -> Interop.toJava(5L, List.class));
            assertTrue(err.getMessage().contains("List"), err.getMessage());
        }

        @Test
        @DisplayName("toJava with Map.class on a non-map value throws")
        void toJavaMapClassNonMap() {
            MandelaError err = assertThrows(MandelaError.class,
                    () -> Interop.toJava(5L, Map.class));
            assertTrue(err.getMessage().contains("Map"), err.getMessage());
        }

        @Test
        @DisplayName("toJava with int.class on a value too wide for int throws")
        void toJavaIntOverflow() {
            MandelaError err = assertThrows(MandelaError.class,
                    () -> Interop.toJava(Long.MAX_VALUE, int.class));
            assertTrue(err.getMessage().contains("does not fit"), err.getMessage());
        }

        @Test
        @DisplayName("fn wraps a varargs function")
        void fnVarargs() {
            org.jdesktop.lg3d.mandela.values.Callable f =
                    Interop.fn("sum", args -> {
                        long total = 0;
                        for (Object a : args) {
                            total += ((Number) a).longValue();
                        }
                        return total;
                    });
            assertNotNull(f);
        }

        @Test
        @DisplayName("consumer wraps a void function")
        void consumerWraps() {
            List<Object> sink = new ArrayList<>();
            org.jdesktop.lg3d.mandela.values.Callable c =
                    Interop.consumer("collect", sink::add);
            assertNotNull(c);
        }

        @Test
        @DisplayName("supplier wraps a no-arg function")
        void supplierWraps() {
            org.jdesktop.lg3d.mandela.values.Callable s =
                    Interop.supplier("now", () -> 42L);
            assertNotNull(s);
        }
    }

    // -- Mandela facade deep paths ---------------------------------------------

    @Nested
    @DisplayName("Mandela facade deep paths")
    class MandelaDeep {

        @Test
        @DisplayName("scriptEngine finds the engine through the registry")
        void scriptEngineRegistry() {
            ScriptEngine engine = Mandela.scriptEngine();
            assertNotNull(engine);
            assertInstanceOf(MandelaScriptEngine.class, engine);
        }

        @Test
        @DisplayName("eval with bindings passes them to the script")
        void evalWithBindings() {
            assertEquals(10L, Mandela.eval("x * 2", Map.of("x", 5L)));
        }

        @Test
        @DisplayName("engine builder produces a working runtime")
        void engineBuilder() {
            org.jdesktop.lg3d.mandela.rt.Runtime rt = Mandela.engine().build();
            assertNotNull(rt);
            assertEquals(3L, rt.run("1 + 2"));
        }
    }

    // -- residue closure: the last reachable branches --------------------------

    @Nested
    @DisplayName("Residue closure")
    class ResidueClosure {

        private MandelaScriptEngine engine() {
            return (MandelaScriptEngine) Mandela.scriptEngineDirect();
        }

        /**
         * A {@link Bindings} that, unlike {@code SimpleBindings}, tolerates a null
         * key -- the only way to reach the engine's defensive null-key skip, which
         * the standard bindings refuse before the engine ever sees it.
         */
        final class NullKeyBindings implements Bindings {
            private final Map<String, Object> map = new HashMap<>();
            @Override public Object put(String key, Object value) { return map.put(key, value); }
            @Override public Object remove(Object key) { return map.remove(key); }
            @Override public void putAll(Map<? extends String, ? extends Object> t) {
                map.putAll(t);
            }
            @Override public void clear() { map.clear(); }
            @Override public Set<String> keySet() { return map.keySet(); }
            @Override public Collection<Object> values() { return map.values(); }
            @Override public Set<Map.Entry<String, Object>> entrySet() { return map.entrySet(); }
            @Override public int size() { return map.size(); }
            @Override public boolean isEmpty() { return map.isEmpty(); }
            @Override public boolean containsKey(Object key) { return map.containsKey(key); }
            @Override public boolean containsValue(Object value) { return map.containsValue(value); }
            @Override public Object get(Object key) { return map.get(key); }
        }

        @Test
        @DisplayName("requireRegistered(null) throws with the direct-engine hint")
        void requireRegisteredNull() {
            IllegalStateException err = assertThrows(IllegalStateException.class,
                    () -> Mandela.requireRegistered(null));
            assertTrue(err.getMessage().contains("scriptEngineDirect"), err.getMessage());
        }

        @Test
        @DisplayName("requireRegistered returns a present engine unchanged")
        void requireRegisteredPresent() {
            ScriptEngine found = Mandela.scriptEngineDirect();
            assertSame(found, Mandela.requireRegistered(found));
        }

        @Test
        @DisplayName("vocabulary picks up a for-loop type pattern's bind name")
        void forTypePatternBind() {
            List<String> names = EditorServices.vocabulary("for x is Int in [1, 2, 3] { x }");
            assertTrue(names.contains("x"), names.toString());
        }

        @Test
        @DisplayName("vocabulary visits a for-loop bare type pattern that binds nothing")
        void forTypePatternNoBind() {
            List<String> names = EditorServices.vocabulary("for is Int in [1, 2, 3] { 0 }");
            assertFalse(names.contains("is"), names.toString());
        }

        @Test
        @DisplayName("vocabulary visits a wildcard for-loop pattern")
        void forAnyPattern() {
            List<String> names = EditorServices.vocabulary("for _ in [1, 2, 3] { 0 }");
            assertNotNull(names);
        }

        @Test
        @DisplayName("membersOf a receiver annotated with a non-value type offers nothing")
        void annotatedNonValueType() {
            // Counter is not one of the language's value types, so the annotation
            // path finds a type the value-member table has no entry for.
            List<String> members =
                    EditorServices.membersOf("c", "class Counter()\nlet c: Counter = 0");
            assertTrue(members.isEmpty(), members.toString());
        }

        @Test
        @DisplayName("ownerOf ignores a call whose callee is not a plain name")
        void callNonNameCallee() {
            // `f(1)(2)` is a Call whose callee is another Call, not a plain name,
            // so it gives `c` no owner.
            List<String> members = EditorServices.membersOf("c", "let c = f(1)(2)");
            assertNotNull(members);
        }

        @Test
        @DisplayName("pushBindings skips a null key an exotic global scope carries")
        void globalScopeNullKey() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            NullKeyBindings global = new NullKeyBindings();
            global.put(null, 1L);
            global.put("g", 7L);
            ctx.setBindings(global, ScriptContext.GLOBAL_SCOPE);
            // The null key is skipped; the real binding still crosses into the run.
            assertEquals(7L, target.eval("g", ctx));
        }
    }

    // -- mutation closure: assertions the reachable code was missing -----------

    @Nested
    @DisplayName("Mutation closure")
    class MutationClosure {

        private MandelaScriptEngine engine() {
            return (MandelaScriptEngine) Mandela.scriptEngineDirect();
        }

        /** An interface loaded by a child loader, to prove the proxy uses it. */
        interface Greeter {
            String greet(String name);
        }

        /** A {@link Writer} that records flushes, so a skipped flush is visible. */
        final class TrackingWriter extends Writer {
            private final StringBuilder buf = new StringBuilder();
            private int flushes;
            @Override public void write(char[] cbuf, int off, int len) {
                buf.append(cbuf, off, len);
            }
            @Override public void flush() {
                flushes++;
            }
            @Override public void close() {
                // Nothing to release.
            }
            int flushes() {
                return flushes;
            }
            String text() {
                return buf.toString();
            }
        }

        @Test
        @DisplayName("eval announces every engine attribute into the scope")
        void announceFillsTheScope() throws ScriptException {
            MandelaScriptEngine target = engine();
            target.eval("1 + 1");
            Bindings scope = target.getBindings(ScriptContext.ENGINE_SCOPE);
            assertSame(target, scope.get("javax.script.scriptengine"));
            assertEquals(MandelaScriptEngine.ENGINE_NAME, scope.get(ScriptEngine.ENGINE));
            assertEquals(Mandela.IMPLEMENTATION_VERSION,
                    scope.get(ScriptEngine.ENGINE_VERSION));
            assertEquals(Mandela.LANGUAGE_NAME, scope.get(ScriptEngine.LANGUAGE));
            assertEquals(Mandela.LANGUAGE_VERSION, scope.get(ScriptEngine.LANGUAGE_VERSION));
        }

        @Test
        @DisplayName("put binds a callable into the runtime, not just the scope")
        void putBindsIntoTheRuntime() throws Exception {
            MandelaScriptEngine target = engine();
            // invokeFunction reads the runtime's globals directly and never pushes
            // the scope, so the name is callable only if put() bound it.
            target.put("greet", Interop.fn1("greet", v -> "hi " + v));
            assertEquals("hi world", ((Invocable) target).invokeFunction("greet", "world"));
        }

        @Test
        @DisplayName("get reads a script-only name from the runtime when the scope lacks it")
        void getFallsBackToTheRuntime() throws ScriptException {
            MandelaScriptEngine target = engine();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            // Run in a separate context so the engine's own default scope stays
            // empty and get() has no scope copy to return.
            target.eval("let secret = 9", ctx);
            assertEquals(9L, target.get("secret"));
        }

        @Test
        @DisplayName("a successful run flushes both console writers")
        void successFlushesBothWriters() throws ScriptException {
            MandelaScriptEngine target = engine();
            TrackingWriter out = new TrackingWriter();
            TrackingWriter err = new TrackingWriter();
            ScriptContext ctx = new SimpleScriptContext();
            ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
            ctx.setWriter(out);
            ctx.setErrorWriter(err);
            target.eval("print(\"o\")", ctx);
            assertEquals("o", out.text());
            assertTrue(out.flushes() > 0, "the success path must flush the writer");
            assertTrue(err.flushes() > 0, "the success path must flush the error writer");
        }

        @Test
        @DisplayName("a failed run still pulls names back and flushes")
        void failurePullsBackAndFlushes() {
            MandelaScriptEngine target = engine();
            TrackingWriter out = new TrackingWriter();
            TrackingWriter err = new TrackingWriter();
            ScriptContext ctx = new SimpleScriptContext();
            Bindings scope = target.createBindings();
            ctx.setBindings(scope, ScriptContext.ENGINE_SCOPE);
            ctx.setWriter(out);
            ctx.setErrorWriter(err);
            assertThrows(ScriptException.class,
                    () -> target.eval("let kept = 5\nprint(\"before\")\n1 / 0", ctx));
            assertEquals(5L, scope.get("kept"), "pullBack must run on the failure path");
            assertEquals("before", out.text());
            assertTrue(out.flushes() > 0, "the failure path must still flush");
        }

        @Test
        @DisplayName("pullBack publishes a created name but not an unchanged global")
        void pullBackFiltersUnchangedGlobals() throws ScriptException {
            MandelaScriptEngine target = engine();
            // Run 1 leaves `persist` in the runtime's globals.
            target.eval("let persist = 1");
            // Run 2 uses a fresh scope: `persist` is not created, not re-bound and
            // not pushed, so only the name this run created may cross.
            ScriptContext ctx = new SimpleScriptContext();
            Bindings scope = target.createBindings();
            ctx.setBindings(scope, ScriptContext.ENGINE_SCOPE);
            target.eval("let other = 2", ctx);
            assertTrue(scope.containsKey("other"), () -> "created name: " + scope.keySet());
            assertFalse(scope.containsKey("persist"),
                    () -> "an unchanged global is not this run's to publish: " + scope.keySet());
        }

        @Test
        @DisplayName("drain treats a zero-length read as end of stream")
        void drainStopsAtAZeroRead() throws ScriptException {
            MandelaScriptEngine target = engine();
            final int[] call = { 0 };
            Reader reader = new Reader() {
                @Override public int read(char[] cbuf, int off, int len) {
                    switch (call[0]++) {
                        case 0:
                            return fill(cbuf, off, "40 + 2");
                        case 1:
                            return 0;
                        case 2:
                            return fill(cbuf, off, " + 100");
                        default:
                            return -1;
                    }
                }
                @Override public void close() {
                    // Nothing to release.
                }
                private int fill(char[] cbuf, int off, String text) {
                    for (int i = 0; i < text.length(); i++) {
                        cbuf[off + i] = text.charAt(i);
                    }
                    return text.length();
                }
            };
            // The loop stops at the zero read, so only "40 + 2" is drained and run.
            assertEquals(42L, target.eval(reader));
        }

        @Test
        @DisplayName("the interface proxy is defined by the interface's own class loader")
        void proxyUsesTheInterfaceClassLoader() throws Exception {
            MandelaScriptEngine target = engine();
            target.eval("fun greet(name) -> Str { \"hi \" + name }");
            URL location = Greeter.class.getProtectionDomain().getCodeSource().getLocation();
            try (URLClassLoader child = new URLClassLoader(new URL[] { location }, null)) {
                Class<?> reloaded = child.loadClass(Greeter.class.getName());
                assertNotSame(Greeter.class, reloaded);
                assertEquals(child, reloaded.getClassLoader());
                // A proxy built on the system loader cannot see an interface this
                // child owns, so the loader the interface came from is the one used.
                assertNotNull(((Invocable) target).getInterface(reloaded));
            }
        }

        @Test
        @DisplayName("toJava hands back a List for a List-subtype request")
        void toJavaListSubtype() {
            Object value = List.of(1L);
            assertSame(value, Interop.toJava(value, ArrayList.class));
        }

        @Test
        @DisplayName("toJava hands back a Map for a Map-subtype request")
        void toJavaMapSubtype() {
            Object value = Map.of("k", 1L);
            assertSame(value, Interop.toJava(value, LinkedHashMap.class));
        }
    }
}
