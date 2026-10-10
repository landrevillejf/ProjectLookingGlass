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

/**
 * A structured language problem: what the tool chain found, where, and how
 * urgent it is.
 *
 * <p>This is the diagnostic type every Mandela host consumes. The desktop's
 * Espresso editor maps it one-to-one onto its own gutter/problem-panel record
 * ({@code org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic}); the command line
 * prints it as {@code file:line:col: severity: message}. Keeping the position
 * arithmetic inside the language module means a host never has to re-lex a file
 * to find a column.</p>
 *
 * <p>Positions are 1-based. A diagnostic with {@code endLine == line} and
 * {@code endColumn == column} is a point; the editor then underlines the single
 * character there.</p>
 *
 * @param sourceName the logical source name (file path, buffer title, {@code <repl>})
 * @param line       the 1-based start line
 * @param column     the 1-based start column
 * @param endLine    the 1-based end line
 * @param endColumn  the 1-based end column
 * @param severity   the urgency
 * @param message    the human-readable text, without any position prefix
 * @param rule       the stable check identifier; the engine emits {@code "syntax"}
 *                   for every lexical or grammar problem, {@code "undefined-name"}
 *                   for a name with no declaration and {@code "param-order"} for a
 *                   required parameter behind a defaulted one. It is empty only on a
 *                   diagnostic a host built itself, so branch on it rather than on
 *                   the message text, which is prose meant for a human
 */
public record Diagnostic(String sourceName, int line, int column,
                         int endLine, int endColumn,
                         Severity severity, String message, String rule) {

    /** How urgent a diagnostic is, most urgent first. */
    public enum Severity {
        /** The script cannot run. */
        ERROR,
        /** The script runs but probably does not say what the author meant. */
        WARNING,
        /** An observation about style or clarity. */
        INFO,
        /** A suggestion the author may ignore. */
        HINT
    }

    public Diagnostic {
        sourceName = (sourceName == null || sourceName.isBlank()) ? "<script>" : sourceName;
        severity = (severity == null) ? Severity.ERROR : severity;
        message = (message == null) ? "" : message;
        rule = (rule == null) ? "" : rule;
        line = Math.max(1, line);
        column = Math.max(1, column);
        endLine = (endLine > 0) ? endLine : line;
        endColumn = (endColumn > 0) ? endColumn : column;
    }

    /** A single-point error at {@code line}:{@code column}. */
    public static Diagnostic error(String sourceName, int line, int column, String message) {
        return new Diagnostic(sourceName, line, column, line, column,
                Severity.ERROR, message, "syntax");
    }

    /** A single-point problem of the given severity. */
    public static Diagnostic of(String sourceName, int line, int column,
                                Severity severity, String rule, String message) {
        return new Diagnostic(sourceName, line, column, line, column,
                severity, message, rule);
    }

    /** @return true when this problem blocks execution. */
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** @return the {@code file:line:col} prefix a command-line host prints. */
    public String location() {
        return sourceName + ":" + line + ":" + column;
    }

    @Override
    public String toString() {
        return location() + ": " + severity.name().toLowerCase(java.util.Locale.ROOT)
                + ": " + message;
    }
}
