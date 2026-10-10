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

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

/**
 * The discovery half of the JSR-223 pair: what a registry reads before anyone has
 * an engine.
 *
 * <p>{@code ScriptEngineManager} finds this class through
 * {@code META-INF/services/javax.script.ScriptEngineFactory} and uses
 * {@link #getNames()}, {@link #getExtensions()} and {@link #getMimeTypes()} to
 * answer {@code getEngineByName("mandela")}, {@code getEngineByExtension("mnd")}
 * and a content-type lookup. Nothing here constructs a runtime, holds state or
 * reads a file, so the class is safe for a registry to instantiate speculatively
 * and is cheap to have on a classpath.</p>
 *
 * <p>The identity it reports is the identity in {@link Mandela} &mdash; one place
 * decides what the language is called, what version it is and what a file ends
 * with &mdash; so a host that asks the registry and a host that imports the facade
 * cannot be told different names for the same engine.</p>
 *
 * @see MandelaScriptEngine the engine this factory hands out
 */
public final class MandelaScriptEngineFactory implements ScriptEngineFactory {

    /** Every extension a Mandela file may use, in the order a host should prefer. */
    public static final List<String> EXTENSIONS = List.of(
            Mandela.FILE_EXTENSION, "mandela");

    /** The MIME types a host can register the language under. */
    public static final List<String> MIME_TYPES = Mandela.MIME_TYPES;

    /** The names an engine is looked up by; the first is the canonical one. */
    public static final List<String> NAMES = Mandela.ENGINE_NAMES;

    @Override
    public String getEngineName() {
        return MandelaScriptEngine.ENGINE_NAME;
    }

    @Override
    public String getEngineVersion() {
        return Mandela.IMPLEMENTATION_VERSION;
    }

    @Override
    public List<String> getExtensions() {
        return EXTENSIONS;
    }

    @Override
    public List<String> getMimeTypes() {
        return MIME_TYPES;
    }

    @Override
    public List<String> getNames() {
        return NAMES;
    }

    @Override
    public String getLanguageName() {
        return Mandela.LANGUAGE_NAME;
    }

    @Override
    public String getLanguageVersion() {
        return Mandela.LANGUAGE_VERSION;
    }

    /**
     * Answers the attributes a registry and a generic tool ask for.
     *
     * <p>{@code null} for anything unknown is the documented contract, so a tool
     * probing for engine-specific parameters simply gets nothing back rather than a
     * guess.</p>
     *
     * @param key the attribute name
     * @return the value, or null when this engine has no such attribute
     */
    @Override
    public Object getParameter(String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case "SCRIPT_NAME":
                return MandelaScriptEngine.ENGINE_NAME;
            case "SCRIPT_EXTENSIONS":
                return EXTENSIONS;
            case "SCRIPT_MIME_TYPES":
                return MIME_TYPES;
            case "javax.script.name":
                return MandelaScriptEngine.NAME;
            case "javax.script.engine":
                return MandelaScriptEngine.ENGINE_NAME;
            case "javax.script.engine_version":
                return Mandela.IMPLEMENTATION_VERSION;
            case "javax.script.language":
                return Mandela.LANGUAGE_NAME;
            case "javax.script.language_version":
                return Mandela.LANGUAGE_VERSION;
            case "javax.script.filename":
                // The extension a generated file should use, which is the only
                // thing a generic tool can do with a language it does not know.
                return "." + Mandela.FILE_EXTENSION;
            case "tck":
                // The TCK wants a version marker it can print; the language version
                // is the honest answer because that is what a conformance claim
                // would be about.
                return Mandela.LANGUAGE_VERSION;
            default:
                return null;
        }
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
        StringBuilder call = new StringBuilder(obj).append('.').append(method).append('(');
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    call.append(", ");
                }
                call.append(args[i]);
            }
        }
        return call.append(')').toString();
    }

    /**
     * @param toDisplay the text to show, already a Mandela string literal or not
     * @return a statement that prints it
     */
    @Override
    public String getOutputStatement(String toDisplay) {
        // println is the statement that ends its own line; print would leave the
        // host's output running together, and a tool generating a snippet cannot
        // know what comes next.
        return "println(" + ((toDisplay == null) ? "null" : toDisplay) + ")";
    }

    /**
     * @param statements the statements to run, in order
     * @return a program that runs them, one per line
     */
    @Override
    public String getProgram(String... statements) {
        if (statements == null || statements.length == 0) {
            return "";
        }
        StringBuilder program = new StringBuilder();
        for (String statement : statements) {
            if (statement == null || statement.isEmpty()) {
                continue;
            }
            program.append(statement);
            // Mandela ends a statement at a newline, but a tool that pasted
            // fragments together is not obliged to know that; an explicit
            // terminator keeps one generated line from swallowing the next, and the
            // lexer accepts either form.
            if (!statement.endsWith(";")) {
                program.append(';');
            }
            program.append('\n');
        }
        return program.toString();
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new MandelaScriptEngine(this);
    }
}
