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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileReader;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import org.jdesktop.lg3d.mandela.api.Interop;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.api.MandelaScriptEngine;
import org.jdesktop.lg3d.mandela.api.MandelaScriptEngineFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JSR-223 door: everything a host gets for free by already knowing how to run a
 * {@code javax.script} engine, which is the API most Java tooling speaks.
 *
 * <p>Mandela's own {@link Mandela} facade is the preferred door &mdash; it is smaller
 * and it types its failures &mdash; but Espresso's script runner, any IDE console and
 * the desktop's plug-in loaders all reach languages through this SPI, so the
 * translation has to be exact: names both ways, the context's writers, compiled
 * bodies re-run against new bindings, {@code Invocable} dispatch, and a
 * {@link ScriptException} that keeps the language's error kind in its text.</p>
 */
class ScriptEngineTest {

    private static ScriptEngine engine() {
        return Mandela.scriptEngine();
    }

    /** Runs a snippet with host names bound first. */
    private static Object eval(String source, String name, Object value)
            throws ScriptException {
        ScriptEngine target = engine();
        target.put(name, value);
        return target.eval(source);
    }

    @Test
    @DisplayName("the registry finds the engine by name, extension and mime type")
    void registry() {
        ScriptEngineManager manager =
                new ScriptEngineManager(ScriptEngineTest.class.getClassLoader());
        assertNotNull(manager.getEngineByName("mandela"));
        assertNotNull(manager.getEngineByExtension("mnd"));
        assertNotNull(manager.getEngineByMimeType("text/x-mandela"));
        assertTrue(manager.getEngineFactories().stream()
                .anyMatch(factory -> "Mandela".equals(factory.getEngineName())));
        assertInstanceOf(MandelaScriptEngine.class, engine());
        assertNotNull(Mandela.scriptEngineDirect());
    }

    @Test
    @DisplayName("the factory advertises the language the way a tool lists it")
    void factoryMetadata() {
        ScriptEngine target = engine();
        MandelaScriptEngineFactory factory = new MandelaScriptEngineFactory();
        assertEquals("Mandela", factory.getLanguageName());
        assertEquals("mnd", factory.getExtensions().get(0));
        assertEquals(Mandela.ENGINE_NAMES, factory.getNames());
        assertEquals(".mnd", factory.getParameter("javax.script.filename"));
        assertEquals(Mandela.LANGUAGE_VERSION, factory.getLanguageVersion());
        // The engine an application got must advertise the same language as the
        // factory it came from, or a tool's list and its runner disagree.
        assertEquals(factory.getLanguageName(), target.getFactory().getLanguageName());
        assertEquals(factory.getLanguageVersion(), target.getFactory().getLanguageVersion());
    }

    @Test
    @DisplayName("the factory builds statements in Mandela, not in some other dialect")
    void factorySyntaxHelpers() {
        MandelaScriptEngineFactory factory = new MandelaScriptEngineFactory();
        assertEquals("println(\"x\")", factory.getOutputStatement("\"x\""));
        assertEquals("let a = 1;\nlet b = 2;\n",
                factory.getProgram("let a = 1", "let b = 2;"));
        assertEquals("obj.m(1, \"a\")",
                factory.getMethodCallSyntax("obj", "m", "1", "\"a\""));
    }

    @Test
    @DisplayName("eval runs the language, not a subset of it")
    void evaluates() throws ScriptException {
        assertEquals(3L, engine().eval("1 + 2"));
        assertEquals("A", engine().eval("\"a\".upper()"));
        assertEquals(25L, engine().eval("let x = 5\nx * x"));
    }

    @Test
    @DisplayName("bindings cross both ways, in place")
    void bindings() throws ScriptException {
        ScriptEngine target = engine();
        target.put("prices", List.of(12.5, 40.0));
        target.put("rate", 0.05);
        assertEquals(2.625, target.eval("""
                let tax = { v -> v * rate }
                prices.map(tax).sum()
                """));
        assertEquals(0.05, target.get("rate"));
        // A name a script declared is readable by the host, which is how a tool
        // collects the answer of a macro it just ran.
        target.eval("let total = 9");
        assertEquals(9L, target.get("total"));

        // A java.util list crossed in is the same list the script mutated, so a
        // host that handed over its own data sees the edit.
        target.put("bag", new ArrayList<>(List.of("x")));
        target.eval("bag.push(\"y\")");
        assertEquals("[x, y]", target.get("bag").toString());
    }

    @Test
    @DisplayName("the context's writers receive the script's console")
    void console() throws ScriptException {
        ScriptEngine target = engine();
        ScriptContext ctx = new SimpleScriptContext();
        StringWriter shown = new StringWriter();
        StringWriter erred = new StringWriter();
        ctx.setWriter(shown);
        ctx.setErrorWriter(erred);
        ctx.setBindings(target.createBindings(), ScriptContext.ENGINE_SCOPE);
        target.eval("print(\"hello\")\neprintln(\"bad\")", ctx);
        assertEquals("hello", shown.toString());
        assertTrue(erred.toString().contains("bad"), erred.toString());
    }

    @Test
    @DisplayName("a compiled body re-runs against other bindings")
    void compiled() throws ScriptException {
        ScriptEngine target = engine();
        target.put("x", 21L);
        CompiledScript body = assertInstanceOf(Compilable.class, target).compile("x * 2");
        assertEquals(42L, body.eval());
        Bindings other = target.createBindings();
        other.put("x", 50L);
        ScriptContext fresh = new SimpleScriptContext();
        fresh.setBindings(other, ScriptContext.ENGINE_SCOPE);
        assertEquals(100L, body.eval(fresh));
        assertEquals(target, body.getEngine());
    }

    @Test
    @DisplayName("Invocable calls a script function or method with converted arguments")
    void invocable() throws ScriptException, NoSuchMethodException {
        ScriptEngine target = engine();
        target.eval("fun add(a: Int, b: Int) -> Int { a + b }\n"
                + "class Counter(total: Int) {\n  fun bump() -> Int { total + 1 }\n}\n"
                + "let c = Counter(5)");
        Invocable invocable = (Invocable) target;
        assertEquals(5L, invocable.invokeFunction("add", 2, 3));
        // The host's doubles reach an Int parameter as a Double, which is the
        // language's own rule for a host number, not a silent rounding.
        assertEquals(4.0, invocable.invokeFunction("add", 1.5, 2.5));
        assertEquals(6L, invocable.invokeMethod(target.get("c"), "bump"));
        NoSuchMethodException absent = assertThrows(NoSuchMethodException.class,
                () -> invocable.invokeFunction("noSuchFn"));
        assertTrue(absent.getMessage().contains("noSuchFn"), absent.getMessage());
        assertNotNull(assertThrows(NoSuchMethodException.class,
                () -> invocable.invokeMethod(target.get("add"), "bump")).getMessage());
    }

    @Test
    @DisplayName("getInterface builds a proxy only when the script really implements it")
    void interfaceProxy() throws ScriptException {
        ScriptEngine target = engine();
        boolean[] flagged = new boolean[1];
        target.put("mark", Interop.consumer("mark", v -> flagged[0] = true));
        target.eval("fun run() -> Str { mark(\"ran\"); \"done\" }");
        Runnable runnable = assertInstanceOf(Invocable.class, target)
                .getInterface(Runnable.class);
        assertNotNull(runnable);
        runnable.run();
        assertTrue(flagged[0]);
        // A method with no script twin yields no proxy rather than a half-built one
        // that throws on the first call.
        assertNull(assertInstanceOf(Invocable.class, target)
                .getInterface(Callable.class));
    }

    @Test
    @DisplayName("the engine exposes the runtime underneath it")
    void runtimeIsReachable() {
        MandelaScriptEngine direct = (MandelaScriptEngine) engine();
        assertNotNull(direct.runtime());
        assertEquals("console", direct.capabilities().label());
        direct.runtime().run("fun helper() -> Int { 1 }", "engine.mnd");
        assertTrue(direct.knownGlobals().contains("helper"));
    }

    @Test
    @DisplayName("a failure crosses as a ScriptException that keeps the kind")
    void failures() {
        ScriptException broken = assertThrows(ScriptException.class,
                () -> engine().eval("let x = "));
        assertNotNull(broken.getMessage());
        ScriptException raised = assertThrows(ScriptException.class,
                () -> engine().eval("class Empty()\nEmpty().oops()"));
        assertTrue(raised.getMessage().contains("TypeError"), raised.getMessage());
    }

    @Test
    @DisplayName("a binding the bridge cannot convert stays host-side")
    void unconvertibleBinding() {
        ScriptEngine target = engine();
        // The host may still hold the object; the script simply never sees it, which
        // is what keeps an arbitrary Java graph out of a sandboxed page.
        assertThrows(ScriptException.class, () -> target.eval("logger.info(\"x\")"));
        target.put("logger", new Object());
        assertNotNull(target.get("logger"));
        assertThrows(ScriptException.class, () -> target.eval("logger.info(\"x\")"));
    }

    @Test
    @DisplayName("a script file on disk runs through the same door")
    void readsAFile() throws IOException, ScriptException {
        Path script = Files.createTempFile("mandela-", ".mnd");
        try {
            Files.writeString(script, "1 + 2\n");
            assertEquals(3L, engine().eval(new FileReader(script.toFile())));
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("eval with a bare name argument goes through Invocable-style lookup")
    void hostFunctionFromABinding() throws ScriptException {
        assertEquals(7L, eval("k(6) + 1", "k", Interop.fn1("k", v -> v)));
    }
}
