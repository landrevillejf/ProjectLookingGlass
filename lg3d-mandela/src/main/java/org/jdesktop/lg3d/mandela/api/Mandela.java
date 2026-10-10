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

import java.util.List;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;

/**
 * The one class a host needs to meet, and the single place the language's public
 * identity lives.
 *
 * <p>Everything here is sugar over {@link Runtime}. That is the point: the
 * embedding API is the same surface the CLI and the editor extension use, so a
 * snippet in a guide is a snippet that actually runs, and a host cannot drift into
 * a private corner of the implementation. A desktop app that wants to run a script
 * writes two lines:</p>
 *
 * <pre>{@code
 * Object total = Mandela.engine()
 *         .bind("tax", Interop.fn1("tax", amount -> ((Double) amount) * 0.15))
 *         .build()
 *         .run("price(100) + tax(100)");
 * }</pre>
 *
 * <h2>Choosing a sandbox</h2>
 *
 * <p>{@link #engine()} hands out {@link Capabilities#console()} &mdash; a console
 * and nothing else, no files, no environment, no network, no threads, no JVM
 * &mdash; because the failure mode of a default is a script that reaches something
 * it should not have. A host that means to allow more says so on the builder, from
 * the place that knows which document or applet asked for it:</p>
 *
 * <ul>
 *   <li>{@link Capabilities#console()} &mdash; stdout and stderr only, the
 *       default;</li>
 *   <li>{@link Capabilities#webPage()} &mdash; what a browser page script gets:
 *       computation with no console at all, since the page's own console is a host
 *       function;</li>
 *   <li>{@link Capabilities#desktop(java.nio.file.Path)} &mdash; a real app whose
 *       files live under one root;</li>
 *   <li>{@link Capabilities#open()} &mdash; everything, which is the
 *       {@code mandela} command line running a script its own author wrote and
 *       nothing an embedder should reach for;</li>
 *   <li>{@link Capabilities#builder()} &mdash; a host with its own policy, which is
 *       what Espresso's per-project trust decision is.</li>
 * </ul>
 *
 * <h2>Three doors, one engine</h2>
 *
 * <p>A host picks whichever door fits its language and its build. This class is the
 * Java and Kotlin door. {@link MandelaScriptEngine} is
 * {@code javax.script}, so a tool that already embeds JSR-223 finds Mandela with
 * {@code new ScriptEngineManager().getEngineByName("mandela")} and needs no new
 * dependency. And the {@code mandela} command line is the same runtime launched
 * from a shell, which is what makes a script testable before anything embeds
 * it.</p>
 */
public final class Mandela {

    /** The language's name, as it is shown to a person. */
    public static final String LANGUAGE_NAME = "Mandela";

    /** The project name behind the language, for an about box and for docs. */
    public static final String PROJECT_NAME = "Project Mandela";

    /**
     * The language version. This is the version of the language and its runtime,
     * not of the desktop that ships them, so a host can state what a script may
     * assume.
     */
    public static final String LANGUAGE_VERSION = "1.0";

    /** The implementation version, kept with the module's own build information. */
    public static final String IMPLEMENTATION_VERSION = "1.0";

    /** The file extension a Mandela source file uses, without the dot. */
    public static final String FILE_EXTENSION = "mnd";

    /** Every name this engine answers to under JSR-223; the first is canonical. */
    public static final List<String> ENGINE_NAMES = List.of(MandelaScriptEngine.NAME,
            "Mandela", "mn", "java-mandela", "x-mandela");

    /** MIME types a host can register the language under. */
    public static final List<String> MIME_TYPES = List.of("text/x-mandela",
            "application/x-mandela");

    private Mandela() {
        // Static namespace.
    }

    // -- engines --------------------------------------------------------------

    /**
     * A builder for an engine with the safest defaults: a console, no files.
     *
     * @return a builder, ready for {@code .build()}
     */
    public static Runtime.Builder engine() {
        return Runtime.builder().capabilities(Capabilities.console());
    }

    /**
     * A builder for an engine over a sandbox the host already decided on.
     *
     * @param caps the grants, budgets and file root for every run
     * @return a builder
     */
    public static Runtime.Builder engine(Capabilities caps) {
        return Runtime.builder().capabilities(caps);
    }

    /**
     * A builder for an app that owns a directory: the desktop shape, where a script
     * may read and write under one root and print to a console.
     *
     * @param root the directory the scripts may touch
     * @return a builder with the desktop capabilities and that file root
     */
    public static Runtime.Builder application(java.nio.file.Path root) {
        return Runtime.builder()
                .capabilities(Capabilities.desktop(root))
                .files(root)
                .output(System.out::print)
                .error(System.err::print);
    }

    // -- one-shot runs --------------------------------------------------------

    /**
     * Runs one program in a throwaway engine, printing to {@code System.out}.
     *
     * <p>The convenience call for a test, a one-liner and a documentation example.
     * It runs in {@link Capabilities#console()} &mdash; it cannot touch a file, the
     * environment, a socket or a thread, so nothing here needs a capability
     * decision; anything that does should build an engine and keep it.</p>
     *
     * @param source the program
     * @return the value of its last expression
     * @throws MandelaError when the script fails
     * @throws org.jdesktop.lg3d.mandela.lang.LangException when it does not compile
     */
    public static Object eval(String source) {
        return engine().output(System.out::print).error(System.err::print)
                .build().run(source);
    }

    /**
     * Runs one program with host values already bound.
     *
     * <p>Each value goes through {@link Interop#toMandela}, so a Java
     * {@code List}, {@code Map}, {@code String}, number or lambda is usable
     * directly and the host does not have to convert first.</p>
     *
     * @param source the program
     * @param values the names to bind before running
     * @return the value of the program's last expression
     */
    public static Object eval(String source, Map<String, Object> values) {
        Runtime.Builder builder = engine()
                .output(System.out::print).error(System.err::print);
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                builder.bind(entry.getKey(), Interop.toMandela(entry.getValue()));
            }
        }
        return builder.build().run(source);
    }

    /**
     * Reports what is wrong with a program, without running it and without
     * throwing.
     *
     * <p>The editor's diagnostic line and {@code mandela check} both read this, so
     * an IDE-quality answer needs no engine, no capabilities and no side
     * effect.</p>
     *
     * @param source the program text
     * @return the findings, empty when the program is sound
     */
    public static List<Diagnostic> check(String source) {
        return engine().build().check(source, Runtime.STRING_SOURCE);
    }

    /**
     * Reports what is wrong with a named program.
     *
     * @param source     the program text
     * @param sourceName where it came from, in the findings
     * @return the findings, empty when the program is sound
     */
    public static List<Diagnostic> check(String source, String sourceName) {
        return engine().build().check(source, sourceName);
    }

    // -- discovery ------------------------------------------------------------

    /**
     * The JSR-223 engine, for a host that already speaks {@code javax.script}.
     *
     * <p>Uses the platform's registry, so it works when the jar is on the classpath
     * like any other script engine; {@link #scriptEngineDirect()} is the same
     * engine for a host that cannot rely on the registry (a shaded jar, a modular
     * launch, an applet with an odd loader).</p>
     *
     * @return a fresh engine
     * @throws IllegalStateException when this jar's service registration is missing
     */
    public static ScriptEngine scriptEngine() {
        ScriptEngine found = new ScriptEngineManager(Mandela.class.getClassLoader())
                .getEngineByName(MandelaScriptEngine.NAME);
        if (found == null) {
            throw new IllegalStateException("the Mandela JSR-223 registration is not"
                    + " visible to this class loader; use Mandela.scriptEngineDirect()");
        }
        return found;
    }

    /**
     * @return a fresh JSR-223 engine, built directly rather than through the registry
     */
    public static ScriptEngine scriptEngineDirect() {
        return new MandelaScriptEngine(new MandelaScriptEngineFactory());
    }

    /**
     * A summary of what this jar provides, in values a host can show in an about
     * box or a status line.
     *
     * @return the language name, project name, version, extension and the module
     *         names a {@code use} statement can ask a default engine for
     */
    public static Map<String, Object> about() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("language", LANGUAGE_NAME);
        out.put("project", PROJECT_NAME);
        out.put("version", LANGUAGE_VERSION);
        out.put("extension", FILE_EXTENSION);
        out.put("names", ENGINE_NAMES);
        out.put("mimes", MIME_TYPES);
        out.put("modules", engine().build().library().moduleNames());
        return out;
    }
}
