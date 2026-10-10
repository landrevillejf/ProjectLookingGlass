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
 * A language error carrying the source position that caused it: an unterminated
 * string from the {@link Lexer}, a syntax error from the {@link Parser}, or an
 * illegal construct rejected by the compiler.
 *
 * <p>Positions are 1-based, and {@link #line()} / {@link #column()} always point
 * at the offending token rather than at the whitespace after it, which is what
 * lets an editor place the caret exactly where the author has to type.
 * {@link #sourceName()} is the logical script name (a file path, a buffer title,
 * {@code "<repl>"}), never {@code null}.</p>
 *
 * <p>The message is deliberately free-form text; a host that needs structure
 * should use {@link #toDiagnostic()} (or {@code Mandela.analyze} at the API
 * level), which exposes the same information as a severity/position record.</p>
 */
public class LangException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sourceName;
    private final String bareMessage;
    private final int line;
    private final int column;
    private final int endLine;
    private final int endColumn;
    private final String rule;

    /**
     * Builds a point error at {@code line}:{@code column}.
     *
     * @param sourceName the logical source name (may be null, then {@code "<script>"})
     * @param line       the 1-based line, clamped to at least 1
     * @param column     the 1-based column, clamped to at least 1
     * @param message    the human-readable description
     */
    public LangException(String sourceName, int line, int column, String message) {
        this(sourceName, line, column, line, column, message);
    }

    /**
     * Builds a ranged error.
     *
     * @param sourceName the logical source name (may be null, then {@code "<script>"})
     * @param line       the 1-based start line
     * @param column     the 1-based start column
     * @param endLine    the 1-based end line (0 means "same as start")
     * @param endColumn  the 1-based end column (0 means "same as start")
     * @param message    the human-readable description
     */
    public LangException(String sourceName, int line, int column,
                         int endLine, int endColumn, String message) {
        this(sourceName, line, column, endLine, endColumn, message, "syntax");
    }

    /**
     * Builds a ranged error that names the check it came from.
     *
     * @param sourceName the logical source name
     * @param line       the 1-based start line
     * @param column     the 1-based start column
     * @param endLine    the 1-based end line (0 means "same as start")
     * @param endColumn  the 1-based end column (0 means "same as start")
     * @param message    the human-readable description
     * @param rule       the stable check identifier, see {@link Diagnostic}
     */
    public LangException(String sourceName, int line, int column,
                         int endLine, int endColumn, String message, String rule) {
        super(format(sourceName, line, column, message));
        this.sourceName = (sourceName == null || sourceName.isBlank()) ? "<script>" : sourceName;
        this.bareMessage = (message == null) ? "" : message;
        this.line = Math.max(1, line);
        this.column = Math.max(1, column);
        this.endLine = (endLine > 0) ? endLine : this.line;
        this.endColumn = (endColumn > 0) ? endColumn : this.column;
        this.rule = (rule == null || rule.isEmpty()) ? "syntax" : rule;
    }

    /**
     * Builds an error located at a token, spanning that token's columns.
     *
     * @param sourceName the logical source name
     * @param token      the offending token (may be null for a synthetic error)
     * @param message    the human-readable description
     */
    public LangException(String sourceName, Token token, String message) {
        this(sourceName,
                token == null ? 1 : token.line(),
                token == null ? 1 : token.column(),
                token == null ? 0 : token.line(),
                token == null ? 0 : token.endColumn(),
                message);
    }

    /** @return the logical source name, never null. */
    public String sourceName() {
        return sourceName;
    }

    /** @return the 1-based start line. */
    public int line() {
        return line;
    }

    /** @return the 1-based start column. */
    public int column() {
        return column;
    }

    /** @return the 1-based end line. */
    public int endLine() {
        return endLine;
    }

    /** @return the 1-based end column. */
    public int endColumn() {
        return endColumn;
    }

    /** @return the message without the {@code file:line:col: } prefix. */
    public String bareMessage() {
        return bareMessage;
    }

    /** @return the stable check identifier, {@code "syntax"} unless one was given */
    public String rule() {
        return rule;
    }

    /**
     * @return the same facts as a structured {@link Diagnostic} of
     *         {@link Diagnostic.Severity#ERROR} severity, for editor hosts that
     *         paint gutter markers rather than print stack traces
     */
    public Diagnostic toDiagnostic() {
        return new Diagnostic(sourceName, line, column, endLine, endColumn,
                Diagnostic.Severity.ERROR, bareMessage, rule);
    }

    private static String format(String sourceName, int line, int column, String message) {
        String name = (sourceName == null || sourceName.isBlank()) ? "<script>" : sourceName;
        return name + ":" + Math.max(1, line) + ":" + Math.max(1, column) + ": " + message;
    }
}
